import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { createReadTool, highlightCode } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import { StringEnum } from "@mariozechner/pi-ai";
import { Text } from "@mariozechner/pi-tui";

const PORT = 9001;
const BASE = `http://localhost:${PORT}`;

export default function (pi: ExtensionAPI) {
	const cwd = process.cwd();

	// ---- Override read to add type parameter ----
	const originalRead = createReadTool(cwd);
	pi.registerTool({
		name: "read",
		label: "Read",
		description: originalRead.description,
		parameters: Type.Object({
			path: Type.String({ description: "Path to the file to read, relative or absolute" }),
			type: Type.Optional(Type.String({ description: "Java type name or pattern with * and ? wildcards. Resolves to file path overriding path parameter" })),
			offset: Type.Optional(Type.Number({ description: "Line number to start reading from, 1-indexed" })),
			limit: Type.Optional(Type.Number({ description: "Maximum lines to read" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("read") + accent(args.type || args.path) + extra("offset", args.offset, "limit", args.limit) + "\n";
			return new Text(text, 0, 0);
		},
		async execute(toolCallId, params, signal, onUpdate, ctx) {
			let resolvedPath: string | null = null;
			if (params.type) {
				resolvedPath = await resolveTypeToFile(params.type, undefined, signal);
				params = { ...params, path: resolvedPath };
			}
			const result = await originalRead.execute(toolCallId, params, signal, onUpdate);
			if (resolvedPath && result.content[0]?.type === "text")
				result.content[0].text = resolvedPath + "\n" + result.content[0].text;
			return result;
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			return new Text(r.content[0]?.text, 0, 0);
		},
	});

	// ---- java_grep ----
	pi.registerTool({
		name: "java_grep",
		label: "Java Grep",
		promptSnippet: "Grep source files of Java types matched by name or pattern",
		promptGuidelines: ["Prefer java_grep over bash grep for finding text in Java source"],
		description: "Resolves type to file, then runs grep. All grep flags supported",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards to grep multiple files" }),
			pattern: Type.String({ description: "Grep pattern" }),
			flags: Type.Optional(Type.String({ description: "Grep flags eg: -i -n -A3 -B2 -C5. Default: -n" })),
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_grep") + accent(args.type) + extra(args.pattern) + " " + (args.flags || "-n");
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const typeData = await jdt("/java_find_type", { name: params.type, project: params.project }, signal);
			const files = [...new Set(typeData.filter((t: any) => t.file).map((t: any) => t.file as string))];
			if (files.length === 0) throw new Error(`Type not found: ${params.type}`);

			const flagStr = params.flags || "-n";
			const args = flagStr.split(/\s+/).filter(Boolean);
			args.push(params.pattern, ...files);
			const result = await pi.exec("grep", args, { signal });
			const output = (result.stdout || "").trim();
			if (!output) return { content: [{ type: "text" as const, text: "No matches" }], details: {} };
			const cleaned = output.split("\n").map((l: string) => l.replace(/^(\d+):/, "$1  ")).join("\n");
			return { content: [{ type: "text" as const, text: cleaned }], details: { lines: output.split("\n") } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.lines) return new Text(r.content[0]?.text || "No matches", 0, 0);
			const lines = r.details.lines as string[];
			let w = 0;
			for (const l of lines) { const m = l.match(/^(\d+):/); if (m) w = Math.max(w, m[1].length); }
			return new Text(lines.map((l: string) =>
				l.replace(/^(\d+):/, (_: string, n: string) => paddedLine(n, w) + "  ")
			).join("\n"), 0, 0);
		},
	});

	// ---- java_members ----
	pi.registerTool({
		name: "java_members",
		label: "Java Members",
		promptSnippet: "Show fields and methods of a Java type",
		description: "Shows signatures, return types, and modifiers. Includes inherited members",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_members") + accent(args.type) + extra("project", args.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_members", params, signal);
			if (!data.length) return { content: [{ type: "text" as const, text: "No members" }], details: {} };
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
			return { content: [{ type: "text" as const, text: parts.join("\n") }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Loading..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "No members", 0, 0);
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
			return new Text(parts.join("\n"), 0, 0);
		},
	});

	// ---- java_method ----
	pi.registerTool({
		name: "java_method",
		label: "Java Method",
		promptSnippet: "Show the source code of a Java method",
		description: "Returns exact method body without over/under reading",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			method: Type.String({ description: "Method name" }),
			paramTypes: Type.Optional(Type.String({ description: "Comma-separated param types for overloaded methods" })),
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
		}),
		renderCall(args, theme) { _theme = theme;
			return new Text(tool("java_method") + type(args.type, args.method, args.paramTypes) + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_method", params, signal);
			const parts: string[] = [];
			if (data.file) parts.push(`${relPath(data.file, ctx.cwd)}` + (data.line ? `:${data.line}` : "") + (data.endLine ? `-${data.endLine}` : ""));
			parts.push(data.source);
			return { content: [{ type: "text" as const, text: parts.join("\n") }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Loading..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "", 0, 0);
			const { data, cwd } = r.details;
			const header = data.file ? filePath(relPath(data.file, cwd)) + (data.line ? lineNumber(":" + data.line + (data.endLine ? "-" + data.endLine : "")) : "") : "";
			const source = (data.source || "").split("\n").map((l: string) => javaCode(l)).join("\n");
			return new Text(header + "\n" + source, 0, 0);
		},
	});

	// ---- java_find_type ----
	pi.registerTool({
		name: "java_find_type",
		label: "Java Find Type",
		promptSnippet: "Search Java types by name or wildcard pattern",
		promptGuidelines: ["Prefer java_find_type over bash find for finding Java files containing a type"],
		description: "Returns file paths and line numbers",
		parameters: Type.Object({
			name: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_find_type") + accent(args.name);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_find_type", params, signal);
			if (data.length === 0) return { content: [{ type: "text" as const, text: "No types found" }], details: {} };
			const text = groupByFile(data, ctx.cwd, (t) => {
				let s = t.line ? `${t.line}` : " ";
				s += `  ${t.type}`;
				return s;
			});
			return { content: [{ type: "text" as const, text }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "No types found", 0, 0);
			const { data, cwd } = r.details;
			const w = maxLineWidth(data);
			return renderGrouped(data, cwd, (t) => (t.line ? paddedLine(t.line, w) + "  " : "") + accent(t.type));
		},
	});

	// ---- java_organize_imports ----
	pi.registerTool({
		name: "java_organize_imports",
		label: "Java Organize Imports",
		promptSnippet: "Automatically add/remove Java imports",
		description: "",
		parameters: Type.Object({
			file: Type.Optional(Type.String({ description: "Path to the Java file" })),
			type: Type.Optional(Type.String({ description: "Type name or pattern with * and ? wildcards. Resolves to file, overriding file parameter" })),
			resolve: Type.Optional(Type.String({ description: "Explicit resolutions for ambiguous types eg: Array:com.badlogic.gdx.utils.Array,List:java.util.List" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_organize_imports") + accent(args.type || args.file);
			if (args.resolve) text += extra("resolve", args.resolve);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			if (!params.type && !params.file) throw new Error("Missing parameter: file or type");
			const path = require("node:path");
			const serverParams: any = { ...params };
			if (!params.type && params.file) serverParams.file = path.resolve(ctx.cwd, params.file);
			const data = await jdt("/java_organize_imports", serverParams, signal);
			if (data.organized) return { content: [{ type: "text" as const, text: "Success" }], details: {} };
			const lines = ["Ambiguous imports, call again with resolve parameter:"];
			for (const c of data.conflicts)
				lines.push(` ${c.type}: ${c.choices.join(", ")}`);
			return { content: [{ type: "text" as const, text: lines.join("\n") }], details: { conflicts: data.conflicts } };
		},
		renderResult(r, { isPartial }, theme) {
			_theme = theme;
			if (isPartial) return new Text(yellow("Organizing..."), 0, 0);
			const text = r.content[0]?.text || "";
			if (text === "Success") return new Text(green(text), 0, 0);
			if (!r.details?.conflicts) return new Text(text, 0, 0);
			const parts = [red("Ambiguous imports, call again with resolve parameter:")];
			for (const c of r.details.conflicts)
				parts.push(`${accent(c.type)}: ${c.choices.join(", ")}`);
			return new Text(parts.join("\n"), 0, 0);
		},
	});

	// ---- java_errors ----
	pi.registerTool({
		name: "java_errors",
		label: "Java Errors",
		promptSnippet: "Report Java compilation errors and warnings",
		description: "Refreshes workspace and waits for build to complete",
		promptGuidelines: ["After editing Java files use java_errors to verify the project compiles"],
		parameters: Type.Object({
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(args, theme) { _theme = theme;
			return new Text(tool("java_errors") + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_errors", params, signal);
			if (data.total === 0) return { content: [{ type: "text" as const, text: "None" }], details: {} };
			const text = groupByFile(data.errors, ctx.cwd, (e) => {
				let s = ` :${e.line}  ${e.severity}: ${e.message}`;
				if (e.context) s += `\n${e.context}`;
				return s;
			});
			const suffix = data.limited ? `\n\nShowing ${data.errors.length} of ${data.total}\nUse limit for more` : "";
			return { content: [{ type: "text" as const, text: text + suffix }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Building..."), 0, 0);
			const text = r.content[0]?.text || "";
			if (text === "None") return new Text(green("None"), 0, 0);
			if (!r.details?.data) return new Text(text, 0, 0);
			const { data, cwd } = r.details;
			return renderGrouped(data.errors, cwd, (e) => {
				const severity = e.severity == "error" ? "Error" : "Warning";
				return lineNumber(e.line) + "  " + red(`${severity}: ${e.message}`) + (e.context ? `\n${e.context}` : "")
			}, data.limited ? `Showing ${data.errors.length} of ${data.total}.` : undefined);
		},
	});

	// ---- java_references ----
	pi.registerTool({
		name: "java_references",
		label: "Java References",
		promptSnippet: "Show all references to a Java type, method, or field",
		promptGuidelines: ["Prefer java_references over bash grep for finding usage of Java elements"],
		description: "Shows enclosing method name for each reference",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			member: Type.Optional(Type.String({ description: "Method or field. Omit to find references to the type" })),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method eg: String,int" })),
			file: Type.Optional(Type.String({ description: "Filter results to file paths matching this substring" })),
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_references") + type(args.type, args.member, args.paramTypes) + extra("file", args.file, "project", args.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_references", params, signal);
			if (data.total === 0) return { content: [{ type: "text" as const, text: "No references found" }], details: {} };
			const text = groupByFile(data.references, ctx.cwd, (r) => {
				let s = `${r.line}`;
				if (r.enclosingType && r.enclosingMethod) {
					const simple = r.enclosingType.split(".").pop();
					s += `  ${simple}.${r.enclosingMethod}`;
				}
				if (r.context) s += `  ${r.context}`;
				return s;
			});
			const suffix = data.limited ? `\n\nShowing ${data.references.length} of ${data.total}\nUse limit for more` : "";
			return { content: [{ type: "text" as const, text: text + suffix }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "No references found", 0, 0);
			const { data, cwd } = r.details;
			const w = maxLineWidth(data.references);
			return renderGrouped(data.references, cwd, (r) => {
				let s = paddedLine(r.line, w);
				if (r.enclosingType && r.enclosingMethod) s += "  " + accent(r.enclosingType.split(".").pop() + "." + r.enclosingMethod);
				if (r.context) s += "  " + r.context;
				return s;
			}, data.limited ? `Showing ${data.references.length} of ${data.total}.` : undefined);
		},
	});

	// ---- java_hierarchy ----
	pi.registerTool({
		name: "java_hierarchy",
		label: "Java Type Hierarchy",
		promptSnippet: "Show Java type hierarchy",
		description: "Shows subtypes/implementors, supertypes, or full hierarchy. Optional filter for types that override a specific method",
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			direction: Type.Optional(StringEnum(["sub", "super", "all"] as const, {
				description: "sub (default): subtypes/implementors, super: supertypes, all: full hierarchy",
			})),
			method: Type.Optional(Type.String({ description: "Filter to types that override this method" })),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method eg: String,int" })),
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_hierarchy") + type(args.type, args.method, args.paramTypes);
			if (args.direction && args.direction !== "sub") text += extra("direction", args.direction);
			text += extra("project", args.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_hierarchy", params, signal);
			if (data.length === 0) return { content: [{ type: "text" as const, text: "No types in hierarchy" }], details: {} };
			const lines = data.map((t: any) => {
				let s = t.type;
				if (t.file) s += "  " + relPath(t.file, ctx.cwd) + (t.line ? `:${t.line}` : "");
				return s;
			});
			return { content: [{ type: "text" as const, text: lines.join("\n") }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Working..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "No types in hierarchy", 0, 0);
			const { data, cwd } = r.details;
			return new Text(data.map((t: any) => {
				let s = accent(t.type);
				if (t.file) s += "  " + filePath(relPath(t.file, cwd)) + (t.line ? lineNumber(":" + t.line) : "");
				return s;
			}).join("\n"), 0, 0);
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
			project: Type.Optional(Type.String({ description: "Eclipse project name" })),
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_callers") + type(args.type, args.method, args.paramTypes) + extra("project", args.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_callers", params, signal);
			if (data.total === 0) return { content: [{ type: "text" as const, text: "No callers found" }], details: {} };
			const text = groupByFile(data.callers, ctx.cwd, (r) => {
				let s = `${r.line}`;
				if (r.enclosingType && r.enclosingMethod) {
					const simple = r.enclosingType.split(".").pop();
					s += `  ${simple}.${r.enclosingMethod}`;
				}
				if (r.context) s += `  ${r.context}`;
				return s;
			});
			const suffix = data.limited ? `\n\nShowing ${data.callers.length} of ${data.total}\nUse limit for more` : "";
			return { content: [{ type: "text" as const, text: text + suffix }], details: { data, cwd: ctx.cwd } };
		},
		renderResult(r, { isPartial }, theme) { _theme = theme;
			if (isPartial) return new Text(yellow("Searching..."), 0, 0);
			if (!r.details?.data) return new Text(r.content[0]?.text || "No callers found", 0, 0);
			const { data, cwd } = r.details;
			const w = maxLineWidth(data.callers);
			return renderGrouped(data.callers, cwd, (r) => {
				let s = paddedLine(r.line, w);
				if (r.enclosingType && r.enclosingMethod) s += "  " + accent(r.enclosingType.split(".").pop() + "." + r.enclosingMethod);
				if (r.context) s += "  " + r.context;
				return s;
			}, data.limited ? `Showing ${data.callers.length} of ${data.total}.` : undefined);
		},
	});

	// ---- java_classpath ----
	pi.registerTool({
		name: "java_classpath",
		label: "Java Classpath",
		promptSnippet: "Provides the classpath for a Java project and all dependencies",
		description: "Use with bash java @file to run Java classes in the project",
		parameters: Type.Object({
			project: Type.String({ description: "Eclipse project name" }),
		}),
		renderCall(args, theme) { _theme = theme;
			let text = tool("java_classpath") + accent(args.project);
			return new Text(text + "\n", 0, 0);
		},
		async execute(_id, params, signal) {
			const data = await jdt("/java_classpath", params, signal);
			return { content: [{ type: "text" as const, text: data.file }] };
		},
		renderResult(r, { isPartial }, theme) { return renderResult(r, isPartial, theme); },
	});
}

async function jdt(path: string, params: Record<string, any>, signal?: AbortSignal): Promise<any> {
	const url = new URL(path, BASE);
	for (const [k, v] of Object.entries(params))
		if (v != null && v !== "") url.searchParams.set(k, String(v));
	let result;
	try {
		result = await fetch(url.toString(), { signal });
	} catch (e: any) {
		throw new Error("Eclipse could not be reached on port: " + PORT);
	}
	if (!result.ok) {
		const body = await result.text();
		try {
			const json = JSON.parse(body);
			if (json.error) throw new Error(json.error);
		} catch (e) {
			if (e instanceof Error && !e.message.startsWith("{")) throw e;
		}
		throw new Error(body);
	}
	return result.json();
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
		if (!byFile.has(file)) byFile.set(file, []);
		byFile.get(file)!.push(formatMatch(r));
	}
	const parts: string[] = [];
	for (const [file, lines] of byFile) {
		parts.push(file);
		for (const line of lines) parts.push(line);
	}
	return parts.join("\n");
}

async function resolveTypeToFile(typeName: string, project: string | undefined, signal?: AbortSignal): Promise<string> {
	const data = await jdt("/java_resolve_type", { type: typeName, project }, signal);
	if (!data.file) throw new Error(`No source file found for type: ${typeName}`);
	return data.file;
}

function renderGrouped(items: any[], cwd: string, formatItem: (r: any) => string, limitMsg?: string): Text {
	const byFile = new Map<string, string[]>();
	for (const r of items) {
		const file = relPath(r.file, cwd);
		if (!byFile.has(file)) byFile.set(file, []);
		byFile.get(file)!.push(formatItem(r));
	}
	const parts: string[] = [];
	for (const [file, lines] of byFile) {
		parts.push(filePath(file));
		for (const line of lines) parts.push(line);
	}
	if (limitMsg) parts.push(_theme.fg("dim", limitMsg));
	return new Text(parts.join("\n"), 0, 0);
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
function type(type: string, member?: string, paramTypes?: string): string {
	let text = accent(type);
	if (member) {
		text += white("#") + member;
		if (paramTypes) text += javaCode(member + "(" + paramTypes + ")");
	}
	return text;
}
function extra(...args: Array<string | number | undefined | null>): string {
	if (args.length == 1) return " " + yellow(args[0]);
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
	return highlightCode(code, "java", _theme);
}
