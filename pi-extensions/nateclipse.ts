import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { highlightCode, keyHint } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import { StringEnum } from "@mariozechner/pi-ai";
import { Text } from "@mariozechner/pi-tui";

const PORT = 9001;
const projectParams = false;
function optionalProject() { return projectParams ? { project: Type.Optional(Type.String({ description: "Eclipse project name" })) } : {}; }
const BASE = `http://localhost:${PORT}`;

export default function (pi: ExtensionAPI) {
	const cwd = process.cwd();

	// ---- java_grep ----
	pi.registerTool({
		name: "java_grep",
		label: "Java Grep",
		promptSnippet: "Grep source files of Java types matched by name or pattern",
		description: "Resolves type to file, then runs grep. All grep flags supported",
		promptGuidelines: [
			"The java_* tools are powerful and efficient, use them whenever possible",
			"Use java_grep instead of bash grep to find text in Java source",
		],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards to grep multiple files" }),
			pattern: Type.String({ description: "Grep pattern" }),
			flags: Type.Optional(Type.String({ description: "Grep flags eg: -i -n -A3 -B2 -C5. Default: -n" })),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_grep") + accent(params.type) + extra("project", params.project) + extra(params.pattern) + " " + (params.flags || "-n");
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const typeData = await jdt("/java_type", { type: params.type, project: params.project }, signal);
			if (typeData._error || !Array.isArray(typeData)) throw new Error(`Type not found: ${params.type}`);
			const files = [...new Set(typeData.filter((t: any) => t.file).map((t: any) => t.file as string))];
			if (files.length === 0) throw new Error(`Type not found: ${params.type}`);

			const flagStr = params.flags || "-n";
			const args = flagStr.split(/\s+/).filter(Boolean);
			args.push(params.pattern, ...files);
			const grepResult = await pi.exec("grep", args, { signal });
			const output = (grepResult.stdout || "").trim();
			if (!output) return result("No matches for: " + params.pattern + "\n" + files.join("\n"), { files });
			const cleaned = output.split("\n").map((l: string) => l.replace(/^(\d+):/, "$1  ")).join("\n");
			return result(cleaned, { lines: output.split("\n"), files });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.lines) {
				let text = r.content[0]?.text || "No matches found.";
				if (r.details?.files) text += "\n" + r.details.files.map((f: string) => filePath(f)).join("\n");
				return new Text(applyCollapse(text, expanded), 0, 0);
			}
			const lines = r.details.lines as string[];
			let w = 0;
			for (const l of lines) { const m = l.match(/^(\d+):/); if (m) w = Math.max(w, m[1].length); }
			const text = lines.map((l: string) =>
				l.replace(/^(\d+):/, (_: string, n: string) => paddedLine(n, w) + "  ")
			).join("\n");
			return new Text(applyCollapse(text, expanded), 0, 0);
		},
	});

	// ---- java_members ----
	pi.registerTool({
		name: "java_members",
		label: "Java Members",
		promptSnippet: "Show fields and methods of a Java type",
		description: "Shows signatures, return types, and modifiers. Includes inherited members",
		promptGuidelines: ["Use java_members to explore all fields/methods on a class"],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_members") + accent(params.type) + extra("project", params.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_members", params, signal);
			if (data._error) return result(data._error + ": " + params.type);
			if (!data.length) return result("Type has no members: " + params.type, { data });
			const parts: string[] = [];
			for (let i = 0; i < data.length; i++) {
				const entry = data[i];
				if (i === 0 && entry.file) {
					parts.push(relPath(entry.file, ctx.cwd));
				} else if (i > 0) {
					parts.push("");
					let header = `Inherited from ${entry.type}`;
					if (entry.file) header += `  ${relPath(entry.file, ctx.cwd)}`;
					parts.push(header);
				}
				if (entry.fields?.length) {
					parts.push("Fields");
					for (const f of entry.fields) {
						const flags = f.flags ? f.flags + " " : "";
						const line = f.line ? ` ${f.line}:` : " ";
						parts.push(`${line} ${flags}${f.type} ${f.name}`);
					}
				}
				if (entry.methods?.length) {
					parts.push("Methods");
					for (const m of entry.methods) {
						const flags = m.flags ? m.flags + " " : "";
						const ret = m.returnType ? m.returnType + " " : "";
						const line = m.line ? ` ${m.line}:` : " ";
						parts.push(`${line} ${flags}${ret}${m.name}(${m.parameters})`);
					}
				}
			}
			return result(parts.join("\n"), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Loading..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "Type not found.", 0, 0);
			if (!r.details.data.length) return new Text(r.content[0]?.text || "Type has no members.", 0, 0);
			const { data, cwd } = r.details;
			const allMembers = data.flatMap((e: any) => [...(e.fields || []), ...(e.methods || [])]);
			const w = maxLineWidth(allMembers);
			const parts: string[] = [];
			for (let i = 0; i < data.length; i++) {
				const e = data[i];
				if (i === 0 && e.file) parts.push(filePath(relPath(e.file, cwd)));
				else if (i > 0) {
					parts.push("");
					let h = accent(`Inherited from ${e.type}`);
					if (e.file) h += "\n" + filePath(relPath(e.file, cwd));
					parts.push(h);
				}
				if (e.fields?.length) {
					parts.push(accent("Fields"));
					for (const f of e.fields)
						parts.push(` ${f.line ? paddedLine(f.line, w) : " ".repeat(w)}  ` + javaCode(`${f.flags ? f.flags + " " : ""}${f.type} ${f.name}`));
				}
				if (e.methods?.length) {
					parts.push(accent("Methods"));
					for (const m of e.methods)
						parts.push(` ${m.line ? paddedLine(m.line, w) : " ".repeat(w)}  ` + javaCode(`${m.flags ? m.flags + " " : ""}${m.returnType ? m.returnType + " " : ""}${m.name}(${m.parameters})`));
				}
			}
			return new Text(applyCollapse(parts.join("\n"), expanded), 0, 0);
		},
	});

	// ---- java_method ----
	pi.registerTool({
		name: "java_method",
		label: "Java Method",
		promptSnippet: "Show the source code of a Java method",
		description: "Returns exact method body without over/under reading plus any super calls",
		promptGuidelines: ["Use java_method instead of read to see an entire Java method"],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			method: Type.String({ description: "Method name" }),
			paramTypes: Type.Optional(Type.String({ description: "Comma-separated param types for overloaded methods" })),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_method") + type(params) + extra("project", params.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_method", params, signal);
			if (data._error) return result(data._error + ": " + typePlain(params));
			const parts: string[] = [];
			if (data.file) parts.push(`${relPath(data.file, ctx.cwd)}` + (data.line ? `:${data.line}` : "") + (data.endLine ? `-${data.endLine}` : ""));
			parts.push(data.source);
			if (Array.isArray(data.supers)) {
				for (const s of data.supers) {
					parts.push("");
					const loc = s.file ? `${relPath(s.file, ctx.cwd)}` + (s.line ? `:${s.line}` : "") + (s.endLine ? `-${s.endLine}` : "") : `${s.type}#${s.method}`;
					parts.push(loc);
					parts.push(s.source);
				}
			}
			return result(parts.join("\n"), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Loading..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "Method not found.", 0, 0);
			const { data, cwd } = r.details;
			const renderBody = (file: string | undefined, line: number | undefined, endLine: number | undefined, src: string, fallback: string) => {
				const header = file
					? filePath(relPath(file, cwd)) + (line ? lineNumber(":" + line + (endLine ? "-" + endLine : "")) : "")
					: fallback;
				return header + "\n" + javaCode(stripIndent(src || ""));
			};
			const pieces: string[] = [];
			pieces.push(renderBody(data.file, data.line, data.endLine, data.source, ""));
			if (Array.isArray(data.supers)) {
				for (const s of data.supers) {
					pieces.push("");
					pieces.push(renderBody(s.file, s.line, s.endLine, s.source, accent(`${s.type}#${s.method}`)));
				}
			}
			return new Text(applyCollapse(pieces.join("\n"), expanded), 0, 0);
		},
	});

	// ---- java_type ----
	pi.registerTool({
		name: "java_type",
		label: "Java Type",
		promptSnippet: "Show a Java type's source or search for types",
		description: "If multiple types match shows the list of matches instead of the source",
		promptGuidelines: [
			"Use java_type instead of read to view a Java type by name. Faster than read and does not require knowing the file path",
			"Use java_type with wildcards to find matching types. Faster than bash find",
		],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			limit: Type.Optional(Type.Number({ description: "Max lines of source when exactly one type matches. Default 500" })),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_type") + accent(params.type) + extra("project", params.project, "limit", params.limit);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_type", params, signal);
			if (data._error) return result(data._error);
			if (!Array.isArray(data) || data.length === 0) return result("No matching types for: " + params.type);
			if (data.length === 1 && data[0].source != null) {
				const m = data[0];
				const path = relPath(m.file, ctx.cwd);
				const header = `${m.type}  ${path}:${m.line}-${m.endLine}`;
				const parts = [header, m.source];
				if (m.truncated) {
					const shown = m.endLine - m.line + 1;
					parts.push("");
					parts.push(`[Type is ${m.totalLines} lines, showing first ${shown}. Raise limit to see more.]`);
				}
				return result(parts.join("\n"), { single: m, cwd: ctx.cwd });
			}
			const text = groupByFile(data, ctx.cwd, (t) => {
				let s = t.line ? `${t.line}` : " ";
				s += `  ${t.type}`;
				return s;
			});
			return result(text, { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (r.details?.single) {
				const m = r.details.single;
				const cwd = r.details.cwd;
				const header = accent(m.type) + "  " + filePath(relPath(m.file, cwd)) + lineNumber(`:${m.line}-${m.endLine}`);
				const body = javaCode(stripIndent(m.source || ""));
				let out = header + "\n" + body;
				if (m.truncated) {
					const shown = m.endLine - m.line + 1;
					out += "\n" + yellow(`[Type is ${m.totalLines} lines, showing first ${shown}. Raise limit to see more.]`);
				}
				return new Text(applyCollapse(out, expanded), 0, 0);
			}
			if (!r.details?.data) return new Text(r.content[0]?.text || "No types found.", 0, 0);
			const { data, cwd } = r.details;
			const w = maxLineWidth(data);
			return new Text(applyCollapse(renderGrouped(data, cwd, (t) => (t.line ? paddedLine(t.line, w) + "  " : "") + accent(t.type)), expanded), 0, 0);
		},
	});

	// ---- java_organize_imports ----
	pi.registerTool({
		name: "java_organize_imports",
		label: "Java Organize Imports",
		promptSnippet: "Automatically add/remove Java imports",
		description: "",
		promptGuidelines: ["Use java_organize_imports instead of editing imports manually"],
		parameters: Type.Object({
			file: Type.Optional(Type.String({ description: "Path to the Java file" })),
			type: Type.Optional(Type.String({ description: "Type name or pattern with * and ? wildcards. Resolves to file, overriding file parameter" })),
			resolve: Type.Optional(Type.String({ description: "Explicit resolutions for ambiguous types eg: Array:com.badlogic.gdx.utils.Array,List:java.util.List" })),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_organize_imports") + accent(params.type || params.file);
			if (params.resolve) text += extra("resolve", params.resolve);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			if (!params.type && !params.file) throw new Error("Missing parameter: file or type");
			const path = require("node:path");
			const serverParams: any = { ...params };
			if (!params.type) serverParams.file = path.resolve(ctx.cwd, params.file);
			const data = await jdt("/java_organize_imports", serverParams, signal);
			if (data._error) return result(data._error + ": " + (params.type || params.file));
			if (data.organized) return result("Success");
			const lines = ["Ambiguous imports, call again with resolve parameter:"];
			for (const c of data.conflicts)
				lines.push(` ${c.type}: ${c.choices.join(", ")}`);
			return result(lines.join("\n"), { data, conflicts: data.conflicts });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Organizing..."), 0, 0);
			const text = r.content[0]?.text || "";
			if (text === "Success") return new Text(green("Success."), 0, 0);
			if (!r.details?.data) return new Text(text || "Not found.", 0, 0);
			if (!r.details?.data.conflicts) return new Text(text, 0, 0);
			const parts = [red("Ambiguous imports, call again with resolve parameter:")];
			for (const c of r.details.data.conflicts)
				parts.push(`${accent(c.type)}: ${c.choices.join(", ")}`);
			return new Text(applyCollapse(parts.join("\n"), expanded), 0, 0);
		},
	});

	// ---- java_errors ----
	pi.registerTool({
		name: "java_errors",
		label: "Java Errors",
		promptSnippet: "Report Java compilation errors and warnings",
		description: "Refreshes workspace and waits for build to complete",
		promptGuidelines: ["Verify projects compile after all editing is complete"],
		parameters: Type.Object({
			...optionalProject(),
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_errors") + extra("project", params.project, "limit", params.limit);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_errors", params, signal);
			if (data._error) return result(data._error);
			if (data.total === 0) return result("None");
			const text = groupByFile(data.errors, ctx.cwd, (e) => {
				let s = ` :${e.line}  ${e.severity}: ${e.message}`;
				if (e.context) s += `\n${e.context}`;
				return s;
			});
			const suffix = data.limited ? `\n\nShowing ${data.errors.length} of ${data.total}\nUse limit for more` : "";
			return result(text + suffix, { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Building..."), 0, 0);
			const text = r.content[0]?.text || "";
			if (text === "None") return new Text(green("No errors."), 0, 0);
			if (!r.details?.data) return new Text(text, 0, 0);
			const { data, cwd } = r.details;
			const grouped = renderGrouped(data.errors, cwd, (e) => {
				const severity = e.severity == "error" ? "Error" : "Warning";
				return lineNumber(e.line) + "  " + red(`${severity}: ${e.message}`) + (e.context ? `\n${stripIndent(e.context)}` : "")
			}, data.limited ? `Showing ${data.errors.length} of ${data.total}.` : undefined);
			return new Text(applyCollapse(grouped, expanded), 0, 0);
		},
	});

	// ---- java_references ----
	pi.registerTool({
		name: "java_references",
		label: "Java References",
		promptSnippet: "Show all references to a Java type, method, or field",
		description: "Shows enclosing method name for each reference. Can filter to specific paths and field reads/writes",
		promptGuidelines: ["Use java_references instead of bash grep to find usage of a Java type/field/method or field writes"],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			member: Type.Optional(Type.String({ description: "Method or field. Omit to find references to the type" })),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method eg: String,int" })),
			access: Type.Optional(StringEnum(["read", "write"] as const, { description: "Filter to field read or write accesses" })),
			file: Type.Optional(Type.String({ description: "Filter to file paths matching this substring" })),
			...optionalProject(),
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_references") + type(params)
				+ extra("project", params.project, "access", params.access, "limit", params.limit, "file", params.file);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_references", params, signal);
			if (data._error) return result(data._error + ": " + typePlain(params));
			if (data.total === 0) return result("No references for: " + typePlain(params), { data });
			const text = groupByFile(data.references, ctx.cwd, (r) => {
				let s = `${r.line}`;
				if (r.enclosingType) {
					const simple = r.enclosingType.split(".").pop();
					s += r.enclosingMethod ? `  ${simple}.${r.enclosingMethod}` : `  ${simple}`;
				}
				if (r.context) s += `  ${r.context}`;
				return s;
			});
			const suffix = data.limited ? `\n\nShowing ${data.references.length} of ${data.total}\nUse limit for more` : "";
			return result(text + suffix, { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "Type not found.", 0, 0);
			if (r.details.data.total === 0) return new Text(r.content[0]?.text || "No references found.", 0, 0);
			const { data, cwd } = r.details;
			const w = maxLineWidth(data.references);
			const grouped = renderGrouped(data.references, cwd, (r) => {
				let s = paddedLine(r.line, w);
				if (r.enclosingType) s += "  " + accent(r.enclosingType.split(".").pop() + (r.enclosingMethod ? "." + r.enclosingMethod : ""));
				if (r.context) s += "  " + r.context;
				return s;
			}, data.limited ? `Showing ${data.references.length} of ${data.total}.` : undefined);
			return new Text(applyCollapse(grouped, expanded), 0, 0);
		},
	});

	// ---- java_hierarchy ----
	pi.registerTool({
		name: "java_hierarchy",
		label: "Java Type Hierarchy",
		promptSnippet: "Show Java type hierarchy",
		description: "Shows subtypes/implementors, supertypes, or full hierarchy. Can filter to types that override a specific method",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			direction: Type.Optional(StringEnum(["sub", "super", "all"] as const, {
				description: "sub (default): subtypes/implementors, super: supertypes, all: full hierarchy",
			})),
			method: Type.Optional(Type.String({ description: "Filter to types that override this method" })),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method eg: String,int" })),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_hierarchy") + type(params);
			if (params.direction && params.direction !== "sub") text += extra("direction", params.direction);
			text += extra("project", params.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_hierarchy", params, signal);
			if (data._error) return result(data._error + ": " + typePlain(params));
			if (data.length === 0) return result("No types in hierarchy for: " + typePlain(params), { data });
			const lines = data.map((t: any) => {
				let s = t.type;
				if (t.file) s += "  " + relPath(t.file, ctx.cwd) + (t.line ? `:${t.line}` : "");
				return s;
			});
			return result(lines.join("\n"), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Working..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "Type not found.", 0, 0);
			if (r.details.data.length === 0) return new Text(r.content[0]?.text || "No types in hierarchy.", 0, 0);
			const { data, cwd } = r.details;
			const text = data.map((t: any) => {
				let s = accent(t.type);
				if (t.file) s += "  " + filePath(relPath(t.file, cwd)) + (t.line ? lineNumber(":" + t.line) : "");
				return s;
			}).join("\n");
			return new Text(applyCollapse(text, expanded), 0, 0);
		},
	});

	// ---- java_callers ----
	pi.registerTool({
		name: "java_callers",
		label: "Java Callers",
		promptSnippet: "Show all callers of a Java method",
		description: "Shows enclosing method name for each reference",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			method: Type.String({ description: "Method name" }),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method" })),
			...optionalProject(),
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_callers") + type(params) + extra("project", params.project, "limit", params.limit);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_callers", params, signal);
			if (data._error) return result(data._error + ": " + typePlain(params));
			if (data.total === 0) return result("No callers for: " + typePlain(params), { data });
			const text = groupByFile(data.callers, ctx.cwd, (r) => {
				let s = `${r.line}`;
				if (r.enclosingType) {
					const simple = r.enclosingType.split(".").pop();
					s += r.enclosingMethod ? `  ${simple}.${r.enclosingMethod}` : `  ${simple}`;
				}
				if (r.context) s += `  ${r.context}`;
				return s;
			});
			const suffix = data.limited ? `\n\nShowing ${data.callers.length} of ${data.total}\nUse limit for more` : "";
			return result(text + suffix, { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "Type not found.", 0, 0);
			if (r.details.data.total === 0) return new Text(r.content[0]?.text || "No callers found.", 0, 0);
			const { data, cwd } = r.details;
			const w = maxLineWidth(data.callers);
			const grouped = renderGrouped(data.callers, cwd, (r) => {
				let s = paddedLine(r.line, w);
				if (r.enclosingType) s += "  " + accent(r.enclosingType.split(".").pop() + (r.enclosingMethod ? "." + r.enclosingMethod : ""));
				if (r.context) s += "  " + r.context;
				return s;
			}, data.limited ? `Showing ${data.callers.length} of ${data.total}.` : undefined);
			return new Text(applyCollapse(grouped, expanded), 0, 0);
		},
	});

	// ---- java_classpath ----
	pi.registerTool({
		name: "java_classpath",
		label: "Java Classpath",
		promptSnippet: "Classpath for Java project and all dependencies to run main classes",
		description: "Use with bash java @file to run Java classes in a project",
		parameters: Type.Object({
			project: Type.String({ description: "Eclipse project name" }),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_classpath") + accent(params.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal) {
			const data = await jdt("/java_classpath", params, signal);
			if (data._error) return result(data._error + ": " + params.project);
			return result(data.file, { data });
		},
		renderResult(r, { isPartial }, theme) {
			if (!r.details?.data) return new Text(r.content[0]?.text || "Project not found.", 0, 0);
			return renderResult(r, isPartial, theme);
		},
	});
}

function result(text: string, details?: Record<string, any>) {
	return { content: [{ type: "text" as const, text }], details: details || {} };
}

function applyCollapse(text: string, expanded: boolean): string {
	if (expanded) return text;
	const lines = text.split("\n");
	if (lines.length <= 10) return text;
	const remaining = lines.length - 10;
	return lines.slice(0, 10).join("\n") + _theme.fg("muted", `\n... (${remaining} more lines,`) + " " + keyHint("app.tools.expand", "to expand") + ")";
}

function stripIndent(text: string): string {
	text = text.replace(/\r/g, "");
	const nl = text.indexOf("\n");
	if (nl < 0) return text;
	const rest = text.slice(nl + 1);
	const lines = rest.split("\n");
	let min = Infinity;
	for (const line of lines) {
		if (line.trim().length === 0) continue;
		let i = 0;
		while (i < line.length && (line[i] === " " || line[i] === "\t")) i++;
		if (i < min) min = i;
	}
	if (min === Infinity || min === 0) return text;
	const stripped = lines.map(line => line.length >= min ? line.slice(min) : line).join("\n");
	return text.slice(0, nl) + "\n" + stripped;
}

async function jdt(path: string, params: Record<string, any>, signal?: AbortSignal): Promise<any> {
	const url = new URL(path, BASE);
	for (const [k, v] of Object.entries(params))
		if (v != null && v !== "") url.searchParams.set(k, String(v));
	let response;
	try {
		response = await fetch(url.toString(), { signal });
	} catch (e: any) {
		throw new Error("Eclipse could not be reached on port: " + PORT);
	}
	if (!response.ok) {
		const body = await response.text();
		let message = body;
		try {
			const json = JSON.parse(body);
			if (json.error) message = json.error;
		} catch (ignored) {}
		if (response.status === 404) return { _error: message || "Not found" };
		throw new Error(message);
	}
	return response.json();
}

function relPath(absPath: string, cwd: string): string {
	if (absPath.toLowerCase().startsWith(cwd.toLowerCase())) {
		let rel = absPath.slice(cwd.length);
		if (rel[0] === "/" || rel[0] === "\\") rel = rel.slice(1);
		return rel;
	}
	return absPath;
}

function groupByFile(data: any[], cwd: string, formatMatch: (r: any) => string): string {
	const byFile = new Map<string, string[]>();
	for (const r of data) {
		const file = relPath(r.file, cwd);
		const key = r.project ? `${r.project}  ${file}` : file;
		if (!byFile.has(key)) byFile.set(key, []);
		byFile.get(key)!.push(formatMatch(r));
	}
	const parts: string[] = [];
	for (const [file, lines] of byFile) {
		parts.push(file);
		for (const line of lines) parts.push(line);
	}
	return parts.join("\n");
}

function renderGrouped(items: any[], cwd: string, formatItem: (r: any) => string, limitMsg?: string): string {
	const byFile = new Map<string, string[]>();
	const projects = new Map<string, string | undefined>();
	for (const r of items) {
		const file = relPath(r.file, cwd);
		if (!byFile.has(file)) { byFile.set(file, []); projects.set(file, r.project); }
		byFile.get(file)!.push(formatItem(r));
	}
	const parts: string[] = [];
	for (const [file, lines] of byFile) {
		const proj = projects.get(file);
		parts.push(proj ? accent(proj) + "  " + filePath(file) : filePath(file));
		for (const line of lines) parts.push(line);
	}
	if (limitMsg) parts.push(_theme.fg("dim", limitMsg));
	return parts.join("\n");
}

function maxLineWidth(items: any[]): number {
	let max = 0;
	for (const item of items)
		if (item.line) max = Math.max(max, String(item.line).length);
	return max;
}

function paddedLine(line: number | string, width: number): string {
	return lineNumber(String(line).padStart(width));
}

function renderResult(r: any, isPartial: boolean, theme: any,
	opts: { wait?: string; success?: string; empty?: string } = {}): Text { _theme = theme;
	if (isPartial) return new Text(yellow(opts.wait || "Working..."), 0, 0);
	const text = r.content[0]?.text || "";
	if (opts.success && text === opts.success) return new Text(green(text), 0, 0);
	if (opts.empty && text === opts.empty) return new Text(red(text), 0, 0);
	return new Text(text, 0, 0);
}

let _theme: any;

function white(text: string): string {
	return _theme.fg("toolTitle", _theme.bold(text));
}
function yellow(value: string): string {
	return _theme.fg("warning", value);
}
function green(text: string): string {
	return _theme.fg("success", _theme.bold(text));
}
function red(text: string): string {
	return _theme.fg("error", _theme.bold(text));
}
function accent(value: string): string {
	return _theme.fg("accent", value);
}

function tool(text: string): string {
	return white(text + " ");
}
function type(params: any): string {
	let text = accent(params.type);
	const member = params.member || params.method;
	if (member) {
		text += white("#") + member;
		if (params.paramTypes) text += javaCode("(" + params.paramTypes + ")");
	}
	return text;
}
function typePlain(params: any): string {
	let text = params.type;
	const member = params.member || params.method;
	if (member) {
		text += "#" + member;
		if (params.paramTypes) text += "(" + params.paramTypes + ")";
	}
	return text;
}
function extra(...args: Array<string | number | undefined | null>): string {
	if (args.length == 1) {
		const value = args[0];
		if (value === undefined || value === null || value === "") return "";
		return " " + yellow(value);
	}
	let text = "";
	for (let i = 0; i < args.length; i += 2) {
		const value = args[i + 1];
		if (value === undefined || value === null || value === "") continue;
		text += " " + yellow(args[i] + "=") + white(String(value));
	}
	return text;
}
function lineNumber(value: string): string {
	return yellow(value);
}
function filePath(value: string): string {
	return _theme.fg("success", value);
}
function javaCode(code: string): string {
	// highlightCode returns string[] (one entry per line). Join with \n so the result is a plain string
	// whether the input is a single expression (used in signatures) or a multi-line block (used after
	// stripIndent for method/type bodies). Strip CR first because cli-highlight normalizes CR to LF,
	// which would produce a trailing empty entry and Array.toString commas when concatenated.
	return highlightCode(code.replace(/\r/g, ""), "java").join("\n");
}
