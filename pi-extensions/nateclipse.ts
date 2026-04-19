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
		description: "Resolves type to file, then runs grep. All grep flags supported. Specify -E if using |",
		promptGuidelines: [
			"The java_* tools are aware of types, references, hierarchies. Use them over bash/grep/find for Java source",
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const typeData = await jdt("/java_type", { type: params.type, project: params.project }, signal);
			if (typeData._error) throw new Error(`Type not found: ${params.type}`);
			const matches = typeData.matches || [];
			const files = [...new Set(matches.filter((t: any) => t.file).map((t: any) => t.file as string))];
			if (files.length === 0) throw new Error(`Type not found: ${params.type}`);

			const flagStr = params.flags || "-n";
			const flagArgs = flagStr.split(/\s+/).filter(Boolean);
			const args = [...flagArgs, params.pattern, ...files];
			const grepResult = await pi.exec("grep", args, { signal });
			const output = (grepResult.stdout || "").replace(/\r/g, "").trim(); // Strip CR (Windows grep may emit \r\n).
			if (!output) {
				// Echo the command so the model sees exactly what ran.
				const cmd = `grep ${flagArgs.join(" ")} ${JSON.stringify(params.pattern)}`.replace(/\s+/g, " ").trim();
				let msg = `No matches for: ${cmd}`;
				// List up to 3 searched files; truncate the rest with a count.
				const MAX_FILES_SHOWN = 3;
				for (const f of files.slice(0, MAX_FILES_SHOWN)) msg += `\n${f}`;
				if (files.length > MAX_FILES_SHOWN) msg += `\n...+${files.length - MAX_FILES_SHOWN} more`;
				// BRE footgun: `|` without -E/-P is a literal pipe, almost never intentional.
				// `\|` is GNU BRE alternation, so don't warn when it's escaped.
				// Short-option cluster like -E, -P, -nE, -iEn all contain E/P.
				const hasExtended = flagArgs.some((f) =>
					(f.startsWith("-") && !f.startsWith("--") && /[EP]/.test(f)) ||
					f === "--extended-regexp" || f === "--perl-regexp"
				);
				const hasUnescapedPipe = /(^|[^\\])\|/.test(params.pattern);
				if (!hasExtended && hasUnescapedPipe) {
					// Probe whether -E would have helped, so the model knows which way to go next.
					const retry = await pi.exec("grep", ["-E", ...flagArgs, params.pattern, ...files], { signal });
					const retryOut = (retry.stdout || "").replace(/\r/g, "").trim();
					if (retryOut) {
						const n = retryOut.split("\n").filter((l) => l.length > 0).length;
						msg += `\nWith -E: ${n} match${n === 1 ? "" : "es"}`;
					} else {
						const err = (retry.stderr || "").replace(/\r/g, "").trim().split("\n")[0];
						// grep exit 2 = error (bad regex), 1 = no match, 0 = match.
						if (retry.code === 2 && err) msg += `\nWith -E: ${err.replace(/^grep:\s*/, "")}`;
						else msg += `\nWith -E: 0 matches`;
					}
				}
				throw new Error(msg);
			}
			const rawLines = output.split("\n").filter((l: string) => l.length > 0);
			const cleaned = rawLines.map((l: string) => l.replace(/^(\d+):/, "$1  ")).join("\n");
			return result(cleaned, { lines: rawLines, files });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Searching..."), 0, 0);
			if (!r.details?.lines) {
				const text = r.content[0]?.text || "No matches found.";
				return new Text("\n" + applyCollapse(text, expanded), 0, 0);
			}
			const lines = r.details.lines as string[];
			let w = 0;
			for (const l of lines) { const m = l.match(/^(\d+):/); if (m) w = Math.max(w, m[1].length); }
			const text = lines.map((l: string) =>
				l.replace(/^(\d+):/, (_: string, n: string) => paddedLine(n, w) + "  ")
			).join("\n");
			return new Text("\n" + applyCollapse(text, expanded), 0, 0);
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_members", params, signal);
			if (data._error) throw new Error(data._error + ": " + params.type);
			const entries = data.entries || [];
			if (!entries.length) {
				const msg = data.warning || "Type has no members: " + params.type;
				throw new Error(msg);
			}
			const parts: string[] = [];
			for (let i = 0; i < entries.length; i++) {
				const entry = entries[i];
				if (i === 0 && entry.file) {
					parts.push(relPath(entry.file, ctx.cwd));
				} else if (i > 0) {
					parts.push("");
					const label = entry.isInterface ? "Implements" : "Extends";
					let header = `${label} ${entry.type}`;
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
			return result(withWarning(parts.join("\n"), data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Loading..."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (r.content[0]?.text || "Type not found."), 0, 0);
			const entries = r.details.data.entries || [];
			if (!entries.length) {
				const msg = r.details.data.warning ? red(r.details.data.warning) : (r.content[0]?.text || "Type has no members.");
				return new Text("\n" + msg, 0, 0);
			}
			const cwd = r.details.cwd;
			const allMembers = entries.flatMap((e: any) => [...(e.fields || []), ...(e.methods || [])]);
			const w = maxLineWidth(allMembers);
			const parts: string[] = [];
			for (let i = 0; i < entries.length; i++) {
				const e = entries[i];
				if (i === 0 && e.file) parts.push(filePath(relPath(e.file, cwd)));
				else if (i > 0) {
					parts.push("");
					const label = e.isInterface ? "Implements" : "Extends";
					let h = accent(`${label} ${e.type}`);
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
			return new Text("\n" + withWarningStyled(applyCollapse(parts.join("\n"), expanded), r.details.data.warning), 0, 0);
		},
	});

	// ---- java_type ----
	pi.registerTool({
		name: "java_type",
		label: "Java Type",
		promptSnippet: "Show a Java type's source or search for types",
		description: "If multiple types match shows the list of matches instead of the source",
		promptGuidelines: [
			"Use java_type instead of read to view a Java type by name, without needing the file path",
			"Use java_type with wildcards instead of bash find to search for matching Java types",
		],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			limit: Type.Optional(Type.Number({ description: "Max lines of source when exactly one type matches. Default 500" })),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_type") + accent(params.type) + extra("project", params.project, "limit", params.limit);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_type", params, signal);
			if (data._error) throw new Error(data._error);
			const matches = data.matches || [];
			if (matches.length === 0) throw new Error("No matching types for: " + params.type);
			if (matches.length === 1 && matches[0].source != null) {
				const m = matches[0];
				const path = relPath(m.file, ctx.cwd);
				const header = `${m.type}  ${path}:${m.line}-${m.endLine}`;
				const parts = [header, m.source];
				if (m.truncated) {
					const shown = m.endLine - m.line + 1;
					parts.push("");
					parts.push(`[Type is ${m.totalLines} lines, showing first ${shown}. Raise limit to see more.]`);
				}
				return result(withWarning(parts.join("\n"), data.warning), { single: m, warning: data.warning, cwd: ctx.cwd });
			}
			const text = groupByFile(matches, ctx.cwd, (t) => {
				let s = t.line ? `${t.line}` : " ";
				s += `  ${t.type}`;
				return s;
			});
			return result(text, { matches, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Searching..."), 0, 0);
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
				return new Text("\n" + withWarningStyled(applyCollapse(out, expanded), r.details.warning), 0, 0);
			}
			if (!r.details?.matches) return new Text("\n" + (r.content[0]?.text || "No types found."), 0, 0);
			const { matches, cwd } = r.details;
			const w = maxLineWidth(matches);
			return new Text("\n" + applyCollapse(renderGrouped(matches, cwd, (t) => (t.line ? paddedLine(t.line, w) + "  " : "") + accent(t.type)), expanded), 0, 0);
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_method", params, signal);
			if (data._error) throw new Error(data._error);
			const parts: string[] = [];
			if (data.file) parts.push(`${relPath(data.file, ctx.cwd)}` + (data.line ? `:${data.line}` : "") + (data.endLine ? `-${data.endLine}` : ""));
			parts.push(data.source);
			if (Array.isArray(data.supers)) {
				for (const s of data.supers) {
					parts.push("");
					const label = s.kind === "super" ? "Super" : "Overrides";
					parts.push(`${label}: ${s.type}`);
					if (s.file) parts.push(`${relPath(s.file, ctx.cwd)}` + (s.line ? `:${s.line}` : "") + (s.endLine ? `-${s.endLine}` : ""));
					parts.push(s.source);
				}
			}
			return result(withWarning(parts.join("\n"), data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Loading..."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (r.content[0]?.text || "Method not found."), 0, 0);
			const { data, cwd } = r.details;
			const renderBody = (file: string | undefined, line: number | undefined, endLine: number | undefined, src: string, prefix: string) => {
				const loc = file ? filePath(relPath(file, cwd)) + (line ? lineNumber(":" + line + (endLine ? "-" + endLine : "")) : "") : "";
				const header = prefix ? (loc ? prefix + "\n" + loc : prefix) : loc;
				return (header ? header + "\n" : "") + javaCode(stripIndent(src || ""));
			};
			const pieces: string[] = [];
			pieces.push(renderBody(data.file, data.line, data.endLine, data.source, ""));
			if (Array.isArray(data.supers)) {
				for (const s of data.supers) {
					pieces.push("");
					const label = s.kind === "super" ? "Super" : "Overrides";
					pieces.push(renderBody(s.file, s.line, s.endLine, s.source, accent(`${label}: ${s.type}`)));
				}
			}
			return new Text("\n" + withWarningStyled(applyCollapse(pieces.join("\n"), expanded), data.warning), 0, 0);
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			if (!params.type && !params.file) throw new Error("Missing parameter: file or type");
			const path = require("node:path");
			const serverParams: any = { ...params };
			if (!params.type) serverParams.file = path.resolve(ctx.cwd, params.file);
			const data = await jdt("/java_organize_imports", serverParams, signal);
			if (data._error) throw new Error(data._error + ": " + (params.type || params.file));
			if (data.organized) return result("Success");
			const lines = ["Ambiguous imports, call again with resolve parameter:"];
			for (const c of data.conflicts)
				lines.push(` ${c.type}: ${c.choices.join(", ")}`);
			return result(lines.join("\n"), { data, conflicts: data.conflicts });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Organizing..."), 0, 0);
			const text = r.content[0]?.text || "";
			if (text === "Success") return new Text("\n" + green("Success."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (text || "Not found."), 0, 0);
			if (!r.details?.data.conflicts) return new Text("\n" + text, 0, 0);
			const parts = [red("Ambiguous imports, call again with resolve parameter:")];
			for (const c of r.details.data.conflicts)
				parts.push(`${accent(c.type)}: ${c.choices.join(", ")}`);
			return new Text("\n" + applyCollapse(parts.join("\n"), expanded), 0, 0);
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_errors", params, signal);
			if (data._error) throw new Error(data._error);
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
			if (isPartial) return new Text("\n" + yellow("Building..."), 0, 0);
			const text = r.content[0]?.text || "";
			if (text === "None") return new Text("\n" + green("No errors."), 0, 0);
			if (!r.details?.data) return new Text("\n" + text, 0, 0);
			const { data, cwd } = r.details;
			const grouped = renderGrouped(data.errors, cwd, (e) => {
				const severity = e.severity == "error" ? "Error" : "Warning";
				return lineNumber(e.line) + "  " + red(`${severity}: ${e.message}`) + (e.context ? `\n${stripIndent(e.context)}` : "")
			}, data.limited ? `Showing ${data.errors.length} of ${data.total}.` : undefined);
			return new Text("\n" + applyCollapse(grouped, expanded), 0, 0);
		},
	});

	// ---- java_references ----
	pi.registerTool({
		name: "java_references",
		label: "Java References",
		promptSnippet: "Show usage of a Java field (read/write), method, or type",
		description: "Shows enclosing method name for each reference. Can filter to specific paths",
		promptGuidelines: ["Use java_references instead of bash grep to find references field reads/writes"],
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_references", params, signal);
			if (data._error) throw new Error(data._error);
			if (data.total === 0) {
				const msg = data.warning || "No references for: " + typePlain(params);
				throw new Error(msg);
			}
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
			return result(withWarning(text + suffix, data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (r.content[0]?.text || "Type not found."), 0, 0);
			if (r.details.data.total === 0) {
				const msg = r.details.data.warning ? red(r.details.data.warning) : (r.content[0]?.text || "No references found.");
				return new Text("\n" + msg, 0, 0);
			}
			const { data, cwd } = r.details;
			const w = maxLineWidth(data.references);
			const grouped = renderGrouped(data.references, cwd, (r) => {
				let s = paddedLine(r.line, w);
				if (r.enclosingType) s += "  " + accent(r.enclosingType.split(".").pop() + (r.enclosingMethod ? "." + r.enclosingMethod : ""));
				if (r.context) s += "  " + r.context;
				return s;
			}, data.limited ? `Showing ${data.references.length} of ${data.total}.` : undefined);
			return new Text("\n" + withWarningStyled(applyCollapse(grouped, expanded), data.warning), 0, 0);
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
				description: "all (default): full hierarchy, sub: subtypes/implementors, super: supertypes",
			})),
			method: Type.Optional(Type.String({ description: "Filter to types that override this method" })),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method eg: String,int" })),
			...optionalProject(),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_hierarchy") + type(params);
			if (params.direction && params.direction !== "all") text += extra("direction", params.direction);
			text += extra("project", params.project);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_hierarchy", params, signal);
			if (data._error) throw new Error(data._error);
			const types = data.types || [];
			if (types.length === 0) {
				const msg = data.warning || "No types in hierarchy for: " + typePlain(params);
				throw new Error(msg);
			}
			const lines = types.map((t: any) => {
				let s = t.type;
				if (t.file) s += "  " + relPath(t.file, ctx.cwd) + (t.line ? `:${t.line}` : "");
				return s;
			});
			return result(withWarning(lines.join("\n"), data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Working..."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (r.content[0]?.text || "Type not found."), 0, 0);
			const types = r.details.data.types || [];
			if (types.length === 0) {
				const msg = r.details.data.warning ? red(r.details.data.warning) : (r.content[0]?.text || "No types in hierarchy.");
				return new Text("\n" + msg, 0, 0);
			}
			const cwd = r.details.cwd;
			const text = types.map((t: any) => {
				let s = accent(t.type);
				if (t.file) s += "  " + filePath(relPath(t.file, cwd)) + (t.line ? lineNumber(":" + t.line) : "");
				return s;
			}).join("\n");
			return new Text("\n" + withWarningStyled(applyCollapse(text, expanded), r.details.data.warning), 0, 0);
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
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_callers", params, signal);
			if (data._error) throw new Error(data._error);
			if (data.total === 0) {
				const msg = data.warning || "No callers for: " + typePlain(params);
				throw new Error(msg);
			}
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
			return result(withWarning(text + suffix, data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) { _theme = theme;
			if (isPartial) return new Text("\n" + yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (r.content[0]?.text || "Type not found."), 0, 0);
			if (r.details.data.total === 0) {
				const msg = r.details.data.warning ? red(r.details.data.warning) : (r.content[0]?.text || "No callers found.");
				return new Text("\n" + msg, 0, 0);
			}
			const { data, cwd } = r.details;
			const w = maxLineWidth(data.callers);
			const grouped = renderGrouped(data.callers, cwd, (r) => {
				let s = paddedLine(r.line, w);
				if (r.enclosingType) s += "  " + accent(r.enclosingType.split(".").pop() + (r.enclosingMethod ? "." + r.enclosingMethod : ""));
				if (r.context) s += "  " + r.context;
				return s;
			}, data.limited ? `Showing ${data.callers.length} of ${data.total}.` : undefined);
			return new Text("\n" + withWarningStyled(applyCollapse(grouped, expanded), data.warning), 0, 0);
		},
	});

	// ---- java_classpath ----
	pi.registerTool({
		name: "java_classpath",
		label: "Java Classpath",
		description: "Get classpath for a Java project and all dependencies. Use with bash java @file to run a Java main class in a project",
		parameters: Type.Object({
			project: Type.String({ description: "Eclipse project name" }),
		}),
		renderCall(params, theme) { _theme = theme;
			let text = tool("java_classpath") + accent(params.project);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal) {
			const data = await jdt("/java_classpath", params, signal);
			if (data._error) throw new Error(data._error + ": " + params.project);
			return result(data.file, { data });
		},
		renderResult(r, { isPartial }, theme) {
			if (!r.details?.data) return new Text("\n" + (r.content[0]?.text || "Project not found."), 0, 0);
			return renderResult(r, isPartial, theme);
		},
	});
}

function applyCollapse(text: string, expanded: boolean): string { // Matches read tool behavior.
	if (expanded) return text;
	const lines = text.split("\n");
	if (lines.length <= 10) return text;
	const remaining = lines.length - 10;
	return lines.slice(0, 10).join("\n") + _theme.fg("muted", `\n... (${remaining} more lines,`) + " " + keyHint("app.tools.expand", "to expand") + ")";
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

function renderResult(r: any, isPartial: boolean, theme: any,
	opts: { wait?: string; success?: string; empty?: string } = {}): Text { _theme = theme;
	if (isPartial) return new Text("\n" + yellow(opts.wait || "Working..."), 0, 0);
	const text = r.content[0]?.text || "";
	if (opts.success && text === opts.success) return new Text("\n" + green(text), 0, 0);
	if (opts.empty && text === opts.empty) return new Text("\n" + red(text), 0, 0);
	return new Text("\n" + text, 0, 0);
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
function lineNumber(value: string): string {
	return yellow(value);
}
function filePath(value: string): string {
	return _theme.fg("success", value);
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
		return " " + yellow(String(value));
	}
	let text = "";
	for (let i = 0; i < args.length; i += 2) {
		const value = args[i + 1];
		if (value === undefined || value === null || value === "") continue;
		text += " " + yellow(args[i] + "=") + white(String(value));
	}
	return text;
}
function withWarning(text: string, warning: string | undefined): string {
	return warning ? warning + "\n" + text : text;
}
function withWarningStyled(text: string, warning: string | undefined): string {
	return warning ? red(warning) + "\n" + text : text;
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
function result(text: string, details?: Record<string, any>) {
	return { content: [{ type: "text" as const, text }], details: details || {} };
}
function javaCode(code: string): string {
	return highlightCode(code.replace(/\r/g, ""), "java").join("\n");
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
function relPath(absPath: string, cwd: string): string {
	if (absPath.toLowerCase().startsWith(cwd.toLowerCase())) {
		let rel = absPath.slice(cwd.length);
		if (rel[0] === "/" || rel[0] === "\\") rel = rel.slice(1);
		return rel;
	}
	return absPath;
}
