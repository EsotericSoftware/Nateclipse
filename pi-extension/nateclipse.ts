import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import { StringEnum } from "@mariozechner/pi-ai";
import { Text } from "@mariozechner/pi-tui";

const PORT = 9001;
const BASE = `http://localhost:${PORT}`;

async function jdt(path: string, params: Record<string, any>, signal?: AbortSignal): Promise<any> {
	const url = new URL(path, BASE);
	for (const [k, v] of Object.entries(params)) {
		if (v != null && v !== "") url.searchParams.set(k, String(v));
	}
	const res = await fetch(url.toString(), { signal });
	if (!res.ok) {
		const text = await res.text();
		throw new Error(`Eclipse JDT (${res.status}): ${text}`);
	}
	return res.json();
}

function relPath(absPath: string, cwd: string): string {
	if (absPath.toLowerCase().startsWith(cwd.toLowerCase())) {
		let rel = absPath.slice(cwd.length);
		if (rel[0] === "/" || rel[0] === "\\") rel = rel.slice(1);
		return rel;
	}
	return absPath;
}

/** Group matches by file, path on its own line, matches indented beneath. */
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

export default function (pi: ExtensionAPI) {
	pi.registerTool({
		name: "jdt_errors",
		label: "JDT Errors",
		description: "Check Java compilation errors and warnings. Refreshes workspace and waits for build to complete.",
		promptSnippet: "Check Java compilation errors/warnings via Eclipse JDT",
		promptGuidelines: ["After editing Java files, use jdt_errors to verify the project compiles."],
		parameters: Type.Object({
			project: Type.Optional(Type.String({ description: "Eclipse project name. Omit for all projects." })),
		}),
		renderCall(args, theme) {
			let text = theme.fg("toolTitle", theme.bold("jdt_errors"));
			if (args.project) text += " " + theme.fg("accent", args.project);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/jdt_errors", params, signal);
			if (data.length === 0) return { content: [{ type: "text" as const, text: "No errors or warnings." }] };
			const text = groupByFile(data, ctx.cwd, (e) => `:${e.line}  ${e.severity}: ${e.message}`);
			return { content: [{ type: "text" as const, text }] };
		},
	});

	pi.registerTool({
		name: "jdt_references",
		label: "JDT References",
		description: "Find all references to a Java type, method, or field via Eclipse JDT. Shows enclosing method for each reference.",
		promptSnippet: "Find references to a Java type/method/field via Eclipse JDT",
		promptGuidelines: ["Prefer jdt_references over grep for finding usages of Java elements."],
		parameters: Type.Object({
			type: Type.String({ description: "Fully qualified type, e.g. com.foo.Bar" }),
			member: Type.Optional(Type.String({ description: "Method or field name. Omit to find references to the type itself." })),
			paramTypes: Type.Optional(Type.String({ description: "Comma-separated param types for overloaded methods, e.g. String,int" })),
			project: Type.Optional(Type.String({ description: "Eclipse project name." })),
		}),
		renderCall(args, theme) {
			let text = theme.fg("toolTitle", theme.bold("jdt_references "));
			text += theme.fg("accent", args.type);
			if (args.member) {
				text += theme.fg("dim", "#") + theme.fg("accent", args.member);
				if (args.paramTypes) text += theme.fg("dim", "(" + args.paramTypes + ")");
			}
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/jdt_references", params, signal);
			if (data.length === 0) return { content: [{ type: "text" as const, text: "No references found." }] };
			const text = groupByFile(data, ctx.cwd, (r) => {
				let s = `:${r.line}`;
				if (r.enclosingType && r.enclosingMethod) {
					const simple = r.enclosingType.split(".").pop();
					s += `  ${simple}.${r.enclosingMethod}`;
				}
				if (r.context) s += `  ${r.context}`;
				return s;
			});
			return { content: [{ type: "text" as const, text }] };
		},
	});

	pi.registerTool({
		name: "jdt_hierarchy",
		label: "JDT Type Hierarchy",
		description: "Show type hierarchy via Eclipse JDT. Returns subtypes/implementors, supertypes, or full hierarchy. Optionally filter to types that override a specific method.",
		promptSnippet: "Show Java type hierarchy (sub/super/all) via Eclipse JDT. Optional method override filter.",
		parameters: Type.Object({
			type: Type.String({ description: "Fully qualified type, e.g. com.foo.Bar" }),
			direction: Type.Optional(StringEnum(["sub", "super", "all"] as const, {
				description: "sub (default): subtypes/implementors. super: supertypes. all: full hierarchy.",
			})),
			method: Type.Optional(Type.String({ description: "Filter to types that override this method." })),
			paramTypes: Type.Optional(Type.String({ description: "Comma-separated param types for overloaded methods." })),
			project: Type.Optional(Type.String({ description: "Eclipse project name." })),
		}),
		renderCall(args, theme) {
			let text = theme.fg("toolTitle", theme.bold("jdt_hierarchy "));
			if (args.direction && args.direction !== "sub") text += theme.fg("dim", args.direction + " ");
			text += theme.fg("accent", args.type);
			if (args.method) {
				text += theme.fg("dim", "#") + theme.fg("accent", args.method);
				if (args.paramTypes) text += theme.fg("dim", "(" + args.paramTypes + ")");
			}
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/jdt_hierarchy", params, signal);
			if (data.length === 0) return { content: [{ type: "text" as const, text: "No types in hierarchy." }] };
			const lines = data.map((t: any) => {
				let s = t.type;
				if (t.file) s += `  ${relPath(t.file, ctx.cwd)}` + (t.line ? `:${t.line}` : "");
				return s;
			});
			return { content: [{ type: "text" as const, text: lines.join("\n") }] };
		},
	});

	pi.registerTool({
		name: "jdt_search_type",
		label: "JDT Search Type",
		description: "Search for Java types by name via Eclipse JDT. Supports wildcards: *Bar, B?r*, *Utils*.",
		promptSnippet: "Search Java types by name (wildcards) via Eclipse JDT",
		promptGuidelines: ["Prefer jdt_search_type over find/grep when locating Java types by name."],
		parameters: Type.Object({
			name: Type.String({ description: "Type name or pattern with * and ? wildcards." }),
			project: Type.Optional(Type.String({ description: "Eclipse project name." })),
		}),
		renderCall(args, theme) {
			let text = theme.fg("toolTitle", theme.bold("jdt_search_type "));
			text += theme.fg("accent", args.name);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/jdt_search_type", params, signal);
			if (data.length === 0) return { content: [{ type: "text" as const, text: "No types found." }] };
			const text = groupByFile(data, ctx.cwd, (t) => {
				let s = t.line ? `:${t.line}` : "";
				s += `  ${t.type}`;
				return s;
			});
			return { content: [{ type: "text" as const, text }] };
		},
	});

	pi.registerTool({
		name: "jdt_members",
		label: "JDT Members",
		description: "List fields and methods of a Java type via Eclipse JDT. Shows signatures, return types, and modifiers. Includes inherited members.",
		promptSnippet: "List fields and methods of a Java type via Eclipse JDT",
		parameters: Type.Object({
			type: Type.String({ description: "Fully qualified type, e.g. com.foo.Bar" }),
			project: Type.Optional(Type.String({ description: "Eclipse project name." })),
		}),
		renderCall(args, theme) {
			let text = theme.fg("toolTitle", theme.bold("jdt_members "));
			text += theme.fg("accent", args.type);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal) {
			const data = await jdt("/jdt_members", params, signal);
			if (!data.length) return { content: [{ type: "text" as const, text: "No members." }] };
			const parts: string[] = [];
			for (let i = 0; i < data.length; i++) {
				const entry = data[i];
				if (i > 0) {
					parts.push("");
					parts.push(`inherited from ${entry.type}:`);
				}
				if (entry.fields?.length) {
					parts.push("fields:");
					for (const f of entry.fields) {
						const flags = f.flags ? f.flags + " " : "";
						const line = f.line ? `  ${f.line}:` : "  ";
						parts.push(`${line} ${flags}${f.type} ${f.name}`);
					}
				}
				if (entry.methods?.length) {
					parts.push("methods:");
					for (const m of entry.methods) {
						const flags = m.flags ? m.flags + " " : "";
						const ret = m.returnType ? m.returnType + " " : "";
						const line = m.line ? `  ${m.line}:` : "  ";
						parts.push(`${line} ${flags}${ret}${m.name}(${m.parameters})`);
					}
				}
			}
			return { content: [{ type: "text" as const, text: parts.join("\n") }] };
		},
	});

	pi.registerTool({
		name: "jdt_organize_imports",
		label: "JDT Organize Imports",
		description: "Organize imports for a Java file. Adds missing imports, removes unused ones. Resolves ambiguous types using project priority rules. Call after finishing code edits.",
		promptSnippet: "Organize Java imports (add missing, remove unused) via Eclipse JDT",
		promptGuidelines: ["After editing Java files, use jdt_organize_imports to fix imports before checking compilation with jdt_errors."],
		parameters: Type.Object({
			file: Type.String({ description: "Path to the Java file." }),
			resolve: Type.Optional(Type.String({ description: "Explicit resolutions for ambiguous types, e.g. Array:com.badlogic.gdx.utils.Array,List:java.util.List" })),
		}),
		renderCall(args, theme) {
			let text = theme.fg("toolTitle", theme.bold("jdt_organize_imports "));
			text += theme.fg("accent", args.file);
			if (args.resolve) text += theme.fg("dim", " resolve: ") + theme.fg("accent", args.resolve);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const path = require("node:path");
			const absFile = path.resolve(ctx.cwd, params.file);
			const data = await jdt("/jdt_organize_imports", { ...params, file: absFile }, signal);
			if (data.organized) {
				return { content: [{ type: "text" as const, text: "Imports organized." }] };
			}
			const lines = ["Ambiguous imports (call again with resolve parameter):"];
			for (const c of data.conflicts) {
				lines.push(`  ${c.type}: ${c.choices.join(", ")}`);
			}
			return { content: [{ type: "text" as const, text: lines.join("\n") }] };
		},
	});
}
