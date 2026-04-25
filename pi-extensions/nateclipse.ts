// Provides tools to access Eclipse JDT through the Nateclipse plugin.

import type { AgentToolResult, ExtensionAPI, Theme, ThemeColor, ToolRenderResultOptions } from "@mariozechner/pi-coding-agent";
import { highlightCode, keyHint } from "@mariozechner/pi-coding-agent";
import { Type, type TOptional, type TString } from "@sinclair/typebox";
import { StringEnum } from "@mariozechner/pi-ai";
import { Text } from "@mariozechner/pi-tui";
import os from "node:os";

const PORT = 9001;
const BASE = `http://localhost:${PORT}`;
const PROJECT_PARAMS = false; // Clanker narrows tool usage too often
const GREP_MAX_FILES = 5000;
const GREP_MAX_MATCHES = 500;
const GREP_MAX_LINE_LENGTH = 500;
const GREP_CHUNK = 100;
const TYPE_MAX = 200;
const ORGANIZE_IMPORTS_MAX = 100;

export default async function (pi: ExtensionAPI) {
	//(await import("./util/debug")).default(pi);

	const cwd = process.cwd();

	// ---- java_grep ----
	pi.registerTool({
		name: "java_grep",
		label: "Java Grep",
		promptSnippet: "Grep source files of Java types matched by name or pattern",
		description: "Resolves type to file, then runs grep. All grep flags supported. Specify -E if using |",
		promptGuidelines: [
			"The java_* tools are aware of types, references, hierarchies. Use them over bash/grep/find for Java source",
			"Use java_grep instead of grep to find text in Java source",
		],
		parameters: Type.Object({
			pattern: Type.String({ description: "Grep pattern" }),
			type: Type.String({ description: "Type name or pattern with * and ? wildcards to grep multiple files" }),
			flags: Type.Optional(Type.String({ description: "Grep flags eg: -i -n -A3 -B2 -C5. Default: -n" })),
			...optionalProject,
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_grep") + " " + s.yellow(params.pattern) + " " + s.accent(params.type) + s.extra("project", params.project) + " " + s.yellow(params.flags || "-n"), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			// Multi-match /java_type responses are grouped as { file, types: [...] }. Skip lines=true so server avoids buffer loads.
			const typeData = await jdt("/java_type", { type: params.type, project: params.project }, signal);
			if (typeData._error) throw new Error(`Type not found: ${params.type}`);
			const matches = typeData.matches || [];
			const files = matches.filter((m: any) => m.file).map((m: any) => m.file as string);
			if (files.length === 0) throw new Error(`Type not found: ${params.type}`);
			if (files.length > GREP_MAX_FILES) {
				throw tooManyError(params.type, files.length, GREP_MAX_FILES, "files",
					matches.flatMap((m: any) => m.types || (m.type ? [m.type] : [])),
					"Narrow the pattern or for Java symbols use: java_references, java_callers, java_hierarchy");
			}

			const flagStr = params.flags || "-n";
			const flagArgs = flagStr.split(/\s+/).filter(Boolean);
			// Flags that change grep's output format so our "file:line:content" parser wouldn't apply.
			// -l/-L: file list. -c: file:count. When present we capture raw stdout instead.
			const rawMode = flagArgs.some((f) =>
				(f.startsWith("-") && !f.startsWith("--") && /[lLc]/.test(f)) ||
				f === "--files-with-matches" || f === "--files-without-match" || f === "--count",
			);
			const grepResult = await runGrepChunked(flagArgs, params.pattern, files, signal);
			const output = grepResult.stdout.replace(/\r/g, "").trim();
			if (!output) {
				const cmd = `grep ${flagArgs.join(" ")} ${JSON.stringify(params.pattern)}`.replace(/\s+/g, " ").trim();
				let msg = `No matches for: ${cmd}`;
				const MAX_FILES_SHOWN = 3;
				for (const f of files.slice(0, MAX_FILES_SHOWN)) msg += `\n${relPath(f, ctx.cwd)}`;
				if (files.length > MAX_FILES_SHOWN) msg += `\n...+${files.length - MAX_FILES_SHOWN} more`;
				// BRE footgun: `|` without -E/-P is a literal pipe. `\|` is GNU BRE alternation so don't warn if escaped.
				const hasExtended = flagArgs.some((f) =>
					(f.startsWith("-") && !f.startsWith("--") && /[EP]/.test(f)) ||
					f === "--extended-regexp" || f === "--perl-regexp"
				);
				const hasUnescapedPipe = /(^|[^\\])\|/.test(params.pattern);
				if (!hasExtended && hasUnescapedPipe) {
					// Probe whether -E would have helped, so the model knows which way to go next.
					const retry = await runGrepChunked(["-E", ...flagArgs], params.pattern, files, signal);
					const retryOut = retry.stdout.replace(/\r/g, "").trim();
					if (retryOut) {
						if (rawMode) {
							const retryLines = retryOut.split("\n").filter((l) => l.length > 0);
							const n = retryLines.length;
							if (n > 0 && n <= 10) {
								msg += `\nWith -E:\n${formatRawGrep(plain, retryLines, ctx.cwd)}`;
							} else {
								msg += `\nWith -E: ${n} match${n === 1 ? "" : "es"}`;
							}
						} else {
							const retryRows: Array<{ file: string; line: number; content: string; isMatch: boolean }> = [];
							for (const l of retryOut.split("\n")) {
								if (!l || l === "--") continue;
								const m = l.match(/^(.+?)([-:])(\d+)\2(.*)$/);
								if (!m) continue;
								retryRows.push({ file: m[1], line: parseInt(m[3]), content: m[4], isMatch: m[2] === ":" });
							}
							const n = retryRows.filter((r) => r.isMatch).length;
							if (n > 0 && n <= 10) {
								msg += `\nWith -E:\n${formatGrep(plain, retryRows, ctx.cwd)}`;
							} else {
								msg += `\nWith -E: ${n} match${n === 1 ? "" : "es"}`;
							}
						}
					} else {
						const err = (retry.stderr || "").replace(/\r/g, "").trim().split("\n")[0];
						// grep exit 2 = error, 1 = no match, 0 = match.
						if (retry.code === 2 && err) msg += `\nWith -E: ${err.replace(/^grep:\s*/, "")}`;
						else msg += `\nWith -E: 0 matches`;
					}
				}
				throw new Error(msg);
			}
			let linesTruncated = false;
			if (rawMode) {
				// -l/-L emit bare file paths; -c emits "file:count". Skip the structured parser.
				const rawLines: string[] = [];
				for (const l of output.split("\n")) {
					if (!l) continue;
					const { text: truncated, wasTruncated } = truncateLine(l);
					if (wasTruncated) linesTruncated = true;
					rawLines.push(truncated);
				}
				if (rawLines.length > GREP_MAX_MATCHES) {
					throw new Error(`Too many matches for "${params.pattern}": ${rawLines.length} lines, max ${GREP_MAX_MATCHES}. Narrow the pattern, or restrict the file set with a more specific type.`);
				}
				const notices = linesTruncated ? `\n[Some lines truncated to ${GREP_MAX_LINE_LENGTH} chars]` : "";
				return result(formatRawGrep(plain, rawLines, ctx.cwd) + notices, { rawLines, rawMode: true, linesTruncated, cwd: ctx.cwd });
			}
			// grep output: match lines are "file:line:content", context lines (-A/-B/-C) are "file-line-content", "--" separates groups.
			const rows: Array<{ file: string; line: number; content: string; isMatch: boolean; enclosingType?: string; enclosingMethod?: string }> = [];
			let matchCount = 0;
			const perFile = new Map<string, number>();
			for (const l of output.split("\n")) {
				if (!l || l === "--") continue;
				const m = l.match(/^(.+?)([-:])(\d+)\2(.*)$/);
				if (!m) continue;
				const isMatch = m[2] === ":";
				const { text: truncated, wasTruncated } = truncateLine(m[4]);
				if (wasTruncated) linesTruncated = true;
				rows.push({ file: m[1], line: parseInt(m[3]), content: truncated, isMatch });
				if (isMatch) {
					matchCount++;
					perFile.set(m[1], (perFile.get(m[1]) || 0) + 1);
				}
			}
			if (matchCount > GREP_MAX_MATCHES) {
				const top = [...perFile.entries()].sort((a, b) => b[1] - a[1]);
				const TOP_N = 5;
				let msg = `Too many matches for "${params.pattern}": ${matchCount} matches, max ${GREP_MAX_MATCHES}`;
				for (const [f, n] of top.slice(0, TOP_N)) msg += `\n  ${relPath(f, ctx.cwd)}  ${n}`;
				if (top.length > TOP_N) msg += `\n  ...+${top.length - TOP_N} more files`;
				msg += `\nNarrow the pattern, or restrict the file set with a more specific type.`;
				throw new Error(msg);
			}
			await enrichEnclosing(rows, signal);
			const notices = linesTruncated ? `\n[Some lines truncated to ${GREP_MAX_LINE_LENGTH} chars]` : "";
			return result(formatGrep(plain, rows, ctx.cwd) + notices, { rows, linesTruncated, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) {
			const s = style(theme);
			if (isPartial) return new Text("\n" + s.yellow("Searching..."), 0, 0);
			const d = r.details;
			if (!d || (!d.rows?.length && !d.rawLines?.length)) {
				return new Text("\n" + s.applyCollapse(firstText(r) || "No matches found.", expanded), 0, 0);
			}
			let body = d.rawMode
				? formatRawGrep(s, d.rawLines ?? [], d.cwd ?? "")
				: formatGrep(s, d.rows ?? [], d.cwd ?? "");
			if (d.linesTruncated) body += "\n" + s.dim(`[Some lines truncated to ${GREP_MAX_LINE_LENGTH} chars]`);
			return new Text("\n" + s.applyCollapse(body, expanded), 0, 0);
		},
	});

	// Chunked grep to stay under Windows cmdline limits (~32KB). Bounded worker pool, sticky exit code: error > match > no-match.
	async function runGrepChunked(flagArgs: string[], pattern: string, files: string[], signal?: AbortSignal): Promise<{ stdout: string; stderr: string; code: number }> {
		const chunks: string[][] = [];
		for (let i = 0; i < files.length; i += GREP_CHUNK) chunks.push(files.slice(i, i + GREP_CHUNK));
		const results: Array<{ stdout: string; stderr: string; code: number }> = new Array(chunks.length);
		let nextIdx = 0;
		const worker = async () => {
			while (true) {
				const idx = nextIdx++;
				if (idx >= chunks.length) return;
				// -H forces filename prefix so output is uniformly "file:line:content".
				results[idx] = await pi.exec("grep", [...flagArgs, "-H", pattern, ...chunks[idx]], { signal });
			}
		};
		const maxThreads = Math.max(2, os.cpus().length - 1);
		const n = Math.min(maxThreads, chunks.length);
		await Promise.all(Array.from({ length: n }, worker));
		let stdout = "";
		let stderr = "";
		let code = 1;
		for (const r of results) {
			if (!r) continue;
			stdout += r.stdout || "";
			stderr += r.stderr || "";
			if (r.code === 2) code = 2;
			else if (r.code === 0 && code !== 2) code = 0;
		}
		return { stdout, stderr, code };
	}

	// ---- java_members ----
	pi.registerTool({
		name: "java_members",
		label: "Java Members",
		promptSnippet: "Show fields and methods of a Java type",
		description: "Shows signatures, return types, and modifiers. Includes inherited members",
		promptGuidelines: ["Use java_members to explore all fields/methods on a class"],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			...optionalProject,
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_members") + " " + s.accent(params.type) + s.extra("project", params.project), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_members", params, signal);
			if (data._error) throw new Error(data._error + ": " + params.type);
			const entries = data.entries || [];
			if (!entries.length) throw new Error(data.warning || "Type has no members: " + params.type);
			return result(plain.withWarning(renderMembers(plain, entries, ctx.cwd), data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, ctx, theme) {
			return jdtResult(r, ctx, theme, { loading: "Loading...", notFound: "Type not found." }, (data, cwd, s) => {
				const entries = data.entries || [];
				if (!entries.length) {
					const msg = data.warning ? s.red(data.warning) : (firstText(r) || "Type has no members.");
					return new Text("\n" + msg, 0, 0);
				}
				return renderMembers(s, entries, cwd);
			});
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
			...optionalProject,
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_type") + " " + s.accent(params.type) + s.extra("project", params.project, "limit", params.limit), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			rejectBareWildcard("java_type", params.type, "Narrow the pattern");
			// lines=true asks server for per-type line numbers in multi-match responses. Only set by this tool.
			const data = await jdt("/java_type", { ...params, lines: true }, signal);
			if (data._error) throw new Error(data._error);
			const rawMatches = data.matches || [];
			if (rawMatches.length === 0) throw new Error("No matching types for: " + params.type);
			// Single-match carries `source`; leave as-is. Multi-match is grouped { file, types: [], lines: [] } -- flatten to per-type rows.
			if (rawMatches.length === 1 && rawMatches[0].source != null) {
				const m = rawMatches[0];
				const header = `${m.type}  ${relPath(m.file, ctx.cwd)}:${m.line}-${m.endLine}`;
				const parts = [header, m.source];
				if (m.truncated) {
					const shown = m.endLine - m.line + 1;
					parts.push("");
					parts.push(`[Type is ${m.totalLines} lines, showing first ${shown}. Raise limit to see more.]`);
				}
				return result(plain.withWarning(parts.join("\n"), data.warning), { single: m, warning: data.warning, cwd: ctx.cwd });
			}
			const matches = rawMatches.flatMap((g: any) =>
				(g.types || []).map((t: string, i: number) => ({ type: t, file: g.file, line: g.lines?.[i] }))
			);
			if (matches.length > TYPE_MAX) {
				throw tooManyError(params.type, matches.length, TYPE_MAX, "types",
					matches.map((m: any) => m.type), "Narrow the pattern");
			}
			const text = groupByFile(plain, matches, ctx.cwd, (t) => (t.line ? `${t.line}` : " ") + `  ${t.type}`);
			return result(text, { matches, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) {
			const s = style(theme);
			if (isPartial) return new Text("\n" + s.yellow("Searching..."), 0, 0);
			if (r.details?.single) {
				const m = r.details.single;
				const cwd = r.details.cwd ?? "";
				const header = s.accent(m.type) + "  " + formatLocation(s, m.file, m.line, m.endLine, cwd);
				const rendered = s.javaCode(stripIndent(m.source || ""));
				const body = m.line
					? (() => {
						const lines = rendered.split("\n");
						const w = String(m.line + lines.length - 1).length;
						return lines.map((ln, i) => s.paddedLine(m.line + i, w) + ln).join("\n");
					})()
					: rendered;
				let out = header + "\n" + body;
				if (m.truncated) {
					const shown = m.endLine - m.line + 1;
					out += "\n" + s.dim(`[Type is ${m.totalLines} lines, showing first ${shown}. Raise limit to see more.]`);
				}
				return new Text("\n" + s.withWarning(s.applyCollapse(out, expanded), r.details.warning), 0, 0);
			}
			if (!r.details?.matches) return new Text("\n" + (firstText(r) || "No types found."), 0, 0);
			const { matches, cwd = "" } = r.details;
			const w = maxLineWidth(matches);
			const body = groupByFile(s, matches, cwd, (t) => s.paddedLine(t.line, w) + s.accent(t.type));
			return new Text("\n" + s.applyCollapse(body, expanded), 0, 0);
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
			...optionalProject,
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_method") + typeRef(s, params) + s.extra("project", params.project), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_method", params, signal);
			if (data._error) throw new Error(data._error);
			return result(plain.withWarning(renderMethod(plain, data, ctx.cwd), data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, ctx, theme) {
			return jdtResult(r, ctx, theme, { loading: "Loading...", notFound: "Method not found." },
				(data, cwd, s) => renderMethod(s, data, cwd, true));
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
		renderCall(params, theme, context) {
			const s = style(theme);
			const scope = params.type || (params.file ? relPath(params.file, context.cwd) : "");
			let text = s.tool("java_organize_imports") + " " + s.accent(scope);
			if (params.resolve) text += s.extra("resolve", params.resolve);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			if (!params.type && !params.file) throw new Error("Missing parameter: file or type");
			if (params.type) {
				rejectBareWildcard("java_organize_imports", params.type, "Narrow the pattern or target specific files, this tool writes to every match");
				// Pre-resolve to enforce the write-scope cap before the server mutates anything.
				const typeData = await jdt("/java_type", { type: params.type }, signal);
				if (typeData._error) throw new Error(`Type not found: ${params.type}`);
				const typeMatches = typeData.matches || [];
				const typeFiles = typeMatches.filter((m: any) => m.file).map((m: any) => m.file as string);
				if (typeFiles.length === 0) throw new Error(`Type not found: ${params.type}`);
				if (typeFiles.length > ORGANIZE_IMPORTS_MAX) {
					throw tooManyError(params.type, typeFiles.length, ORGANIZE_IMPORTS_MAX, "files",
						typeMatches.flatMap((m: any) => m.types || (m.type ? [m.type] : [])),
						"Narrow the pattern or target specific files, this tool writes to every match");
				}
			}
			const path = require("node:path");
			const serverParams: Record<string, any> = { ...params };
			if (!params.type) serverParams.file = path.resolve(ctx.cwd, params.file);
			const data = await jdt("/java_organize_imports", serverParams, signal);
			if (data._error) throw new Error(data._error + ": " + (params.type || params.file));
			if (data.organized) return result("Success", {});
			const lines = ["Ambiguous imports, call again with resolve parameter:"];
			for (const c of data.conflicts)
				lines.push(` ${c.type}: ${c.choices.join(", ")}`);
			return result(lines.join("\n"), { data, conflicts: data.conflicts });
		},
		renderResult(r, { isPartial, expanded }, theme) {
			const s = style(theme);
			if (isPartial) return new Text("\n" + s.yellow("Organizing..."), 0, 0);
			const text = firstText(r);
			if (text === "Success") return new Text("\n" + s.green("Success."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (text || "Not found."), 0, 0);
			if (!r.details.data.conflicts) return new Text("\n" + text, 0, 0);
			const parts = [s.red("Ambiguous imports, call again with resolve parameter:")];
			for (const c of r.details.data.conflicts)
				parts.push(`${s.accent(c.type)}: ${c.choices.join(", ")}`);
			return new Text("\n" + s.applyCollapse(parts.join("\n"), expanded), 0, 0);
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
			...optionalProject,
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_errors") + s.extra("project", params.project, "limit", params.limit), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_errors", params, signal);
			if (data._error) throw new Error(data._error);
			if (data.total === 0) return result("None", {});
			await enrichEnclosing(data.errors, signal);
			const text = groupByFile(plain, data.errors, ctx.cwd, (e) => {
				let out = `${e.line}`;
				const encl = enclosingLabel(e.enclosingType, e.enclosingMethod);
				if (encl) out += `  ${encl}`;
				out += `  ${e.severity}: ${e.message}`;
				if (e.context) out += `\n${e.context}`;
				return out;
			});
			const suffix = data.limited ? `\n\nShowing ${data.errors.length} of ${data.total}\nUse limit for more` : "";
			return result(text + suffix, { data, cwd: ctx.cwd });
		},
		renderResult(r, { isPartial, expanded }, theme) {
			const s = style(theme);
			if (isPartial) return new Text("\n" + s.yellow("Building..."), 0, 0);
			const text = firstText(r);
			if (text === "None") return new Text("\n" + s.green("No errors."), 0, 0);
			if (!r.details?.data) return new Text("\n" + text, 0, 0);
			const { data, cwd = "" } = r.details;
			const w = maxLineWidth(data.errors);
			const body = groupByFile(s, data.errors, cwd, (e) => {
				const severity = e.severity == "error" ? "Error" : "Warning";
				const encl = enclosingLabel(e.enclosingType, e.enclosingMethod);
				const enclPart = encl ? s.accent(encl) + "  " : "";
				return s.paddedLine(e.line, w) + enclPart + s.red(`${severity}: ${e.message}`) + (e.context ? `\n${s.javaCode(stripIndent(e.context))}` : "");
			}, data.limited ? `Showing ${data.errors.length} of ${data.total}.` : undefined);
			return new Text("\n" + s.applyCollapse(body, expanded), 0, 0);
		},
	});

	// ---- java_references ----
	pi.registerTool({
		name: "java_references",
		label: "Java References",
		promptSnippet: "Show usage of a Java field (read/write), method, or type",
		description: "Shows enclosing method name for each reference. Can filter to specific paths",
		promptGuidelines: ["Use java_references instead of grep to find references field reads/writes"],
		parameters: Type.Object({
			type: Type.String({ description: "Type name or pattern with * and ? wildcards" }),
			member: Type.Optional(Type.String({ description: "Method or field. Omit to find references to the type" })),
			paramTypes: Type.Optional(Type.String({ description: "Parameter types for overloaded method eg: String,int" })),
			access: Type.Optional(StringEnum(["read", "write"] as const, { description: "Filter to field read or write accesses" })),
			file: Type.Optional(Type.String({ description: "Filter to file paths matching this substring" })),
			...optionalProject,
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(params, theme, context) {
			const s = style(theme);
			const file = params.file ? relPath(params.file, context.cwd) : undefined;
			return new Text(s.tool("java_references") + typeRef(s, params)
				+ s.extra("project", params.project, "access", params.access, "limit", params.limit, "file", file), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_references", params, signal);
			if (data._error) throw new Error(data._error);
			if (data.total === 0) throw new Error(data.warning || "No references for: " + typePlain(params));
			const text = groupByFile(plain, data.references, ctx.cwd, (r) => formatRefRowPlain(r));
			const suffix = data.limited ? `\n\nShowing ${data.references.length} of ${data.total}\nUse limit for more` : "";
			return result(plain.withWarning(text + suffix, data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, ctx, theme) {
			return jdtResult(r, ctx, theme, { loading: "Searching...", notFound: "Type not found." }, (data, cwd, s) => {
				if (data.total === 0) {
					const msg = data.warning ? s.red(data.warning) : (firstText(r) || "No references found.");
					return new Text("\n" + msg, 0, 0);
				}
				const w = maxLineWidth(data.references);
				return groupByFile(s, data.references, cwd, (row) => formatRefRowStyled(s, row, w),
					data.limited ? `Showing ${data.references.length} of ${data.total}.` : undefined);
			});
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
			...optionalProject,
		}),
		renderCall(params, theme) {
			const s = style(theme);
			let text = s.tool("java_hierarchy") + typeRef(s, params);
			if (params.direction && params.direction !== "all") text += s.extra("direction", params.direction);
			text += s.extra("project", params.project);
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_hierarchy", params, signal);
			if (data._error) throw new Error(data._error);
			const types = data.types || [];
			if (types.length === 0) throw new Error(data.warning || "No types in hierarchy for: " + typePlain(params));
			const lines = types.map((t: any) => t.type + (t.file ? "  " + relPath(t.file, ctx.cwd) + (t.line ? `:${t.line}` : "") : ""));
			return result(plain.withWarning(lines.join("\n"), data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, ctx, theme) {
			return jdtResult(r, ctx, theme, { loading: "Working...", notFound: "Type not found." }, (data, cwd, s) => {
				const types = data.types || [];
				if (types.length === 0) {
					const msg = data.warning ? s.red(data.warning) : (firstText(r) || "No types in hierarchy.");
					return new Text("\n" + msg, 0, 0);
				}
				return types.map((t: any) => s.accent(t.type) + (t.file ? "  " + formatLocation(s, t.file, t.line, undefined, cwd) : "")).join("\n");
			});
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
			...optionalProject,
			limit: Type.Optional(Type.Number({ description: "Maximum results. Default 50" })),
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_callers") + typeRef(s, params) + s.extra("project", params.project, "limit", params.limit), 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const data = await jdt("/java_callers", params, signal);
			if (data._error) throw new Error(data._error);
			if (data.total === 0) throw new Error(data.warning || "No callers for: " + typePlain(params));
			const text = data.overloads && data.overloads.length > 1
				? renderCallersByOverload(plain, data.callers, data.overloads, ctx.cwd, formatRefRowPlain)
				: groupByFile(plain, data.callers, ctx.cwd, formatRefRowPlain);
			const suffix = data.limited ? `\n\nShowing ${data.callers.length} of ${data.total}\nUse limit for more` : "";
			return result(plain.withWarning(text + suffix, data.warning), { data, cwd: ctx.cwd });
		},
		renderResult(r, ctx, theme) {
			return jdtResult(r, ctx, theme, { loading: "Searching...", notFound: "Type not found." }, (data, cwd, s) => {
				if (data.total === 0) {
					const msg = data.warning ? s.red(data.warning) : (firstText(r) || "No callers found.");
					return new Text("\n" + msg, 0, 0);
				}
				const w = maxLineWidth(data.callers);
				const format = (row: any) => formatRefRowStyled(s, row, w);
				const limitMsg = data.limited ? `Showing ${data.callers.length} of ${data.total}.` : undefined;
				return data.overloads && data.overloads.length > 1
					? renderCallersByOverload(s, data.callers, data.overloads, cwd, format, limitMsg)
					: groupByFile(s, data.callers, cwd, format, limitMsg);
			});
		},
	});

	// ---- java_classpath ----
	pi.registerTool({
		name: "java_classpath",
		label: "Java Classpath",
		description: "Get classpath for one or more Java projects and all dependencies. Use with bash java @file to run a Java main class.",
		parameters: Type.Object({
			projects: Type.String({ description: "Comma-separated list of Eclipse project names to combine in order" }),
		}),
		renderCall(params, theme) {
			const s = style(theme);
			return new Text(s.tool("java_classpath") + " " + s.accent(params.projects), 0, 0);
		},
		async execute(_id, params, signal) {
			const data = await jdt("/java_classpath", params, signal);
			if (data._error) throw new Error(data._error + " (" + params.projects + ")");
			return result(data.file, { data });
		},
		renderResult(r, { isPartial }, theme) {
			const s = style(theme);
			if (isPartial) return new Text("\n" + s.yellow("Working..."), 0, 0);
			if (!r.details?.data) return new Text("\n" + (firstText(r) || "Project not found."), 0, 0);
			return new Text("\n" + s.filePath(r.details.data.file), 0, 0);
		},
	});
}

// JDT server responses are loosely shaped; we access fields ad hoc.
type JdtData = Record<string, any>;

// Shared details shape for tools that pass through the raw JDT response.
interface JdtDetails { data?: JdtData; cwd?: string; }

// PROJECT_PARAMS is a compile-time toggle. When disabled, the cast keeps the
// `project` key present in the Static<> shape (as `string | undefined`) so
// tools can reference `params.project` without per-tool conditionals.
const optionalProject: { project: TOptional<TString> } = PROJECT_PARAMS
	? { project: Type.Optional(Type.String({ description: "Eclipse project name" })) }
	: ({} as { project: TOptional<TString> });

// ---- HTTP ----

async function jdt(path: string, params: Record<string, any>, signal?: AbortSignal, body?: string): Promise<JdtData> {
	const url = new URL(path, BASE);
	for (const [k, v] of Object.entries(params))
		if (v != null && v !== "") url.searchParams.set(k, String(v));
	const init: RequestInit = { signal };
	if (body !== undefined) {
		init.method = "POST";
		init.headers = { "Content-Type": "text/plain" };
		init.body = body;
	}
	let response;
	try {
		response = await fetch(url.toString(), init);
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

// One batched POST instead of one request per file. Body is line-oriented: "<filepath>\t<csv-lines>".
async function enrichEnclosing(rows: Array<{ file: string; line: number; enclosingType?: string; enclosingMethod?: string }>, signal?: AbortSignal): Promise<void> {
	const byFile = new Map<string, Set<number>>();
	for (const r of rows) {
		if (!byFile.has(r.file)) byFile.set(r.file, new Set());
		byFile.get(r.file)!.add(r.line);
	}
	const lookup = new Map<string, { type?: string; method?: string }>();
	const bodyLines: string[] = [];
	for (const [file, lines] of byFile) bodyLines.push(`${file}\t${[...lines].join(",")}`);
	try {
		const data = await jdt("/java_enclosing", {}, signal, bodyLines.join("\n"));
		if (!data?._error && Array.isArray(data?.enclosures)) {
			for (const e of data.enclosures) lookup.set(`${e.file}:${e.line}`, { type: e.type, method: e.method });
		}
	} catch { /* best-effort */ }
	for (const r of rows) {
		const info = lookup.get(`${r.file}:${r.line}`);
		if (info?.type) r.enclosingType = info.type;
		if (info?.method) r.enclosingMethod = info.method;
	}
}

// ---- Formatters ----

function truncateLine(s: string): { text: string; wasTruncated: boolean } {
	const sanitized = s.replace(/\r/g, "");
	if (sanitized.length <= GREP_MAX_LINE_LENGTH) return { text: sanitized, wasTruncated: false };
	return { text: sanitized.slice(0, GREP_MAX_LINE_LENGTH) + "\u2026", wasTruncated: true };
}

// Used for -l/-L (file lists) and -c (file:count). Each raw line may start with a file path; relativize it.
function formatRawGrep(s: Style, lines: string[], cwd: string): string {
	return lines.map((line) => {
		// -c format is "file:count"; split on the LAST trailing ":<digits>" so Windows drive letters ("C:/...") aren't mistaken for the separator.
		const m = line.match(/^(.*):(\d+)$/);
		if (m) return s.filePath(relPath(m[1], cwd)) + s.dim(":") + " " + s.yellow(m[2]);
		return s.filePath(relPath(line, cwd));
	}).join("\n");
}

function formatLocation(s: Style, file: string | undefined, line: number | undefined, endLine: number | undefined, cwd: string): string {
	if (!file) return "";
	const rel = s.filePath(relPath(file, cwd));
	if (!line) return rel;
	return rel + s.lineNumber(":" + line + (endLine ? "-" + endLine : ""));
}

function enclosingLabel(enclosingType: string | undefined, enclosingMethod: string | undefined): string {
	if (!enclosingType) return "";
	const simple = enclosingType.split(".").pop();
	return enclosingMethod ? `${simple}.${enclosingMethod}` : simple!;
}

function formatGrep(s: Style, rows: Array<{ file: string; line: number; content: string; isMatch?: boolean; enclosingType?: string; enclosingMethod?: string }>, cwd: string): string {
	type Row = { line: number; content: string; isMatch?: boolean; enclosingType?: string; enclosingMethod?: string };
	const byFile = new Map<string, Row[]>();
	for (const r of rows) {
		if (!byFile.has(r.file)) byFile.set(r.file, []);
		byFile.get(r.file)!.push({ line: r.line, content: r.content, isMatch: r.isMatch, enclosingType: r.enclosingType, enclosingMethod: r.enclosingMethod });
	}
	let w = 0;
	for (const r of rows) w = Math.max(w, String(r.line).length);
	const parts: string[] = [];
	for (const [file, fileRows] of byFile) {
		parts.push(s.filePath(relPath(file, cwd)));
		for (const r of fileRows) {
			// Context lines (-A/-B/-C) render dim with no enclosing label; the match line above already shows it.
			const isContext = r.isMatch === false;
			if (isContext) {
				parts.push(s.paddedLine(r.line, w) + s.dim(r.content));
			} else {
				const encl = enclosingLabel(r.enclosingType, r.enclosingMethod);
				const enclPart = encl ? s.accent(encl) + "  " : "";
				parts.push(s.paddedLine(r.line, w) + enclPart + s.javaCode(r.content));
			}
		}
	}
	return parts.join("\n");
}

function groupByFile(s: Style, items: any[], cwd: string, formatItem: (r: any) => string, limitMsg?: string): string {
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
		parts.push(proj ? s.accent(proj) + "  " + s.filePath(file) : s.filePath(file));
		for (const line of lines) parts.push(line);
	}
	if (limitMsg) parts.push(s.dim(limitMsg));
	return parts.join("\n");
}

// Groups callers by overload signature, then by file within each overload. `overloads` lists ALL with full counts (may exceed shown).
function renderCallersByOverload(
	s: Style,
	callers: any[],
	overloads: Array<{ signature: string; count: number }>,
	cwd: string,
	formatItem: (r: any) => string,
	limitMsg?: string,
): string {
	const byOverload = new Map<string, any[]>();
	for (const o of overloads) byOverload.set(o.signature, []);
	for (const c of callers) {
		const key = c.overload;
		if (!byOverload.has(key)) byOverload.set(key, []);
		byOverload.get(key)!.push(c);
	}
	const sections: string[] = [];
	for (const o of overloads) {
		const rows = byOverload.get(o.signature) || [];
		const countLabel = `${o.count} caller${o.count === 1 ? "" : "s"}`;
		const header = s.accent(o.signature) + "  " + s.dim(countLabel);
		if (rows.length === 0) {
			sections.push(header + (o.count > 0 ? "  " + s.dim("(not shown)") : ""));
			continue;
		}
		sections.push(header + "\n" + groupByFile(s, rows, cwd, formatItem));
	}
	if (limitMsg) sections.push(s.dim(limitMsg));
	return sections.join("\n\n");
}

function renderMembers(s: Style, entries: any[], cwd: string): string {
	const allMembers = entries.flatMap((e: any) => [...(e.fields || []), ...(e.methods || [])]);
	const w = maxLineWidth(allMembers);
	const parts: string[] = [];
	for (let i = 0; i < entries.length; i++) {
		const e = entries[i];
		if (i > 0) parts.push("");
		const label = i === 0 ? "" : (e.isInterface ? "Implements " : "Extends ");
		let h = s.accent(`${label}${e.type}`);
		if (e.file) h += "  " + s.filePath(relPath(e.file, cwd));
		parts.push(h);
		if (e.fields?.length) {
			parts.push(s.accent("Fields"));
			for (const f of e.fields)
				parts.push(s.paddedLine(f.line, w) + s.javaCode(`${f.flags ? f.flags + " " : ""}${f.type} ${f.name}`));
		}
		if (e.methods?.length) {
			parts.push(s.accent("Methods"));
			for (const m of e.methods)
				parts.push(s.paddedLine(m.line, w) + s.javaCode(`${m.flags ? m.flags + " " : ""}${m.returnType ? m.returnType + " " : ""}${m.name}(${m.parameters})`));
		}
	}
	return parts.join("\n");
}

function renderMethod(s: Style, data: any, cwd: string, showLineNumbers: boolean = false): string {
	// Supers from JARs (JDK, libraries) dump huge javadoc we almost never need. Strip the leading /** ... */
	// and shift the start line so line numbers stay accurate. Primary method is always shown verbatim -
	// the user explicitly asked for it.
	type Section = { file: any; line: any; endLine: any; source: string; header: string };
	const sections: Section[] = [];
	let primaryHeader = s.accent(data.type) + s.white("#") + s.member(data.method);
	if (data.inheritedBy) primaryHeader += "  " + s.dim(`(inherited by ${data.inheritedBy})`);
	sections.push({ file: data.file, line: data.line, endLine: data.endLine, source: data.source || "", header: primaryHeader });
	if (Array.isArray(data.supers)) {
		for (const sup of data.supers) {
			const label = sup.kind === "super" ? "Super" : "Overrides";
			let src = sup.source || "";
			let line = sup.line;
			if (isJdkType(sup.type)) {
				const stripped = stripLeadingJavadoc(src);
				if (stripped.skipped > 0) {
					src = stripped.source;
					if (typeof line === "number") line += stripped.skipped;
				}
			}
			sections.push({ file: sup.file, line, endLine: sup.endLine, source: src, header: s.accent(`${label}: ${sup.type}`) + s.white("#") + s.member(sup.method) });
		}
	}
	// Width shared across all sections so line numbers align.
	let w = 0;
	if (showLineNumbers) for (const sec of sections) if (sec.line) w = Math.max(w, String(sec.endLine || sec.line).length);
	const body = ({ file, line, endLine, source, header }: Section) => {
		const loc = formatLocation(s, file, line, endLine, cwd);
		const full = header ? (loc ? header + "  " + loc : header) : loc;
		const rendered = s.javaCode(stripIndent(source));
		const numbered = showLineNumbers && line
			? rendered.split("\n").map((ln, i) => s.paddedLine(line + i, w) + ln).join("\n")
			: rendered;
		return (full ? full + "\n" : "") + numbered;
	};
	const pieces = sections.map((sec, i) => (i === 0 ? "" : "\n") + body(sec));
	return pieces.join("\n");
}

function formatRefRowPlain(r: any): string {
	let out = `${r.line}`;
	const encl = enclosingLabel(r.enclosingType, r.enclosingMethod);
	if (encl) out += `  ${encl}`;
	if (r.context) out += `  ${r.context}`;
	return out;
}

function formatRefRowStyled(s: Style, r: any, width: number): string {
	const encl = enclosingLabel(r.enclosingType, r.enclosingMethod);
	const tail: string[] = [];
	if (encl) tail.push(s.accent(encl));
	if (r.context) tail.push(s.javaCode(r.context));
	return s.paddedLine(r.line, width) + tail.join("  ");
}

function typeRef(s: Style, params: any): string {
	let text = " " + s.accent(params.type);
	const member = params.member || params.method;
	if (member) {
		text += s.white("#") + s.member(member);
		if (params.paramTypes) text += s.javaCode("(" + params.paramTypes + ")");
	}
	return text;
}

function typePlain(params: { type: string; member?: string; method?: string; paramTypes?: string }): string {
	let text = params.type;
	const member = params.member || params.method;
	if (member) {
		text += "#" + member;
		if (params.paramTypes) text += "(" + params.paramTypes + ")";
	}
	return text;
}

// ---- Errors ----

function rejectBareWildcard(toolName: string, pattern: string | undefined, advice: string): void {
	const n = (pattern || "").trim();
	if (n !== "*" && n !== "**") return;
	throw new Error(`Pattern "${pattern}" is not allowed for ${toolName}\n${advice}`);
}

function tooManyError(pattern: string, count: number, max: number, unit: string, samples: string[], advice: string): Error {
	const shown = samples.slice(0, 3).map((s) => `${s}`).join("\n");
	const more = count > 3 ? `\n...+${count - 3} more` : "";
	return new Error(`Too many matches for "${pattern}": ${count} ${unit}, max ${max}\n${shown}${more}\n${advice}`);
}

// ---- Misc ----

/** Wraps the common renderResult pattern: partial -> loading; no data -> notFound; else render & wrap with warning+collapse.
 *  The render callback may return a Text to bypass wrapping (for custom empty/error displays). */
function jdtResult(
	r: AgentToolResult<JdtDetails | undefined>,
	{ isPartial, expanded }: ToolRenderResultOptions,
	theme: Theme,
	opts: { loading: string; notFound?: string },
	render: (data: JdtData, cwd: string, s: Style) => string | Text,
): Text {
	const s = style(theme);
	if (isPartial) return new Text("\n" + s.yellow(opts.loading), 0, 0);
	const data = r.details?.data;
	if (!data) return new Text("\n" + (firstText(r) || opts.notFound || "Not found."), 0, 0);
	const body = render(data, r.details?.cwd ?? "", s);
	if (body instanceof Text) return body;
	return new Text("\n" + s.withWarning(s.applyCollapse(body, expanded), data.warning), 0, 0);
}

/** Extract the first text content from a tool result, handling the TextContent | ImageContent union. */
function firstText(r: AgentToolResult<unknown> | undefined): string {
	const first = r?.content?.[0];
	return first?.type === "text" ? first.text : "";
}

function maxLineWidth(items: any[]): number {
	let max = 0;
	for (const item of items)
		if (item.line) max = Math.max(max, String(item.line).length);
	return max;
}

function result<T>(text: string, details: T): AgentToolResult<T> {
	return { content: [{ type: "text", text }], details };
}

// JDK stdlib types whose Javadoc is universally known and just wastes tokens when shown as an incidental
// super/override. Library Javadoc (Guava, libGDX, etc.) is often the only contract documentation, so we
// keep it. Package-based check is more reliable than file paths, which vary by JDK install.
function isJdkType(type: string | undefined): boolean {
	return !!type && /^(java|javax|jdk|sun|com\.sun|org\.w3c|org\.xml|org\.ietf)\./.test(type);
}

// Strips a single leading /** ... */ javadoc block and any following blank/whitespace-only lines.
// Returns the remaining source and how many source lines were removed, so callers can adjust line numbers.
function stripLeadingJavadoc(src: string): { source: string; skipped: number } {
	const m = src.match(/^[ \t]*\/\*\*[\s\S]*?\*\/[ \t]*\r?\n(?:[ \t]*\r?\n)*/);
	if (!m) return { source: src, skipped: 0 };
	const skipped = (m[0].match(/\n/g) || []).length;
	return { source: src.slice(m[0].length), skipped };
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
	// Normalize separators for comparison; Windows Node gives backslashes in cwd, callers often pass forward slashes.
	const a = absPath.replace(/\\/g, "/");
	const c = cwd.replace(/\\/g, "/");
	if (a.toLowerCase().startsWith(c.toLowerCase())) {
		let rel = a.slice(c.length);
		if (rel[0] === "/") rel = rel.slice(1);
		return rel || a;
	}
	return a;
}

// ---- Styling ----

type Style = {
	white: (s: string) => string;
	yellow: (s: string) => string;
	member: (s: string) => string;
	green: (s: string) => string;
	red: (s: string) => string;
	accent: (s: string) => string;
	lineNumber: (s: string) => string;
	filePath: (s: string) => string;
	dim: (s: string) => string;
	tool: (s: string) => string;
	javaCode: (s: string) => string;
	paddedLine: (line: number | string | undefined, width: number) => string;
	extra: (...args: Array<string | number | undefined | null>) => string;
	withWarning: (text: string, warning?: string) => string;
	applyCollapse: (text: string, expanded: boolean) => string;
};

function style(theme: Theme): Style {
	const fg = (k: ThemeColor, v: string) => theme.fg(k, v);
	const bold = (v: string) => theme.bold(v);
	const yellow = (v: string) => fg("warning", v);
	const white = (v: string) => fg("toolTitle", bold(v));
	return {
		white,
		yellow,
		member: yellow,
		green: (v) => fg("success", bold(v)),
		red: (v) => fg("error", bold(v)),
		accent: (v) => fg("accent", v),
		lineNumber: (v) => v.startsWith(":") ? white(":") + yellow(v.slice(1)) : yellow(v),
		filePath: (v) => fg("success", v),
		dim: (v) => fg("dim", v),
		tool: white,
		javaCode: (code) => highlightCode(code.replace(/\r/g, ""), "java").join("\n"),
		paddedLine: (line, width) => (line ? yellow(String(line).padStart(width)) + "  " : ""),
		extra(...args) {
			if (args.length == 1) {
				const v = args[0];
				return (v === undefined || v === null || v === "") ? "" : " " + yellow(String(v));
			}
			let text = "";
			for (let i = 0; i < args.length; i += 2) {
				const v = args[i + 1];
				if (v === undefined || v === null || v === "") continue;
				text += " " + yellow(args[i] + "=") + white(String(v));
			}
			return text;
		},
		withWarning: (text, warning) => warning ? fg("error", bold(warning)) + "\n" + text : text,
		applyCollapse(text, expanded) {
			if (expanded) return text;
			const lines = text.split("\n");
			if (lines.length <= 10) return text;
			const remaining = lines.length - 10;
			return lines.slice(0, 10).join("\n") + fg("muted", `\n... (${remaining} more lines,`) + " " + keyHint("app.tools.expand", "to expand") + ")";
		},
	};
}

const id = (s: string) => s;
const plain: Style = {
	white: id, yellow: id, member: id, green: id, red: id, accent: id,
	lineNumber: id, filePath: id, dim: id, tool: id,
	javaCode: (code) => code.replace(/\r/g, ""),
	paddedLine: (line, width) => (line ? String(line).padStart(width) + "  " : ""),
	extra(...args) {
		if (args.length == 1) {
			const v = args[0];
			return (v === undefined || v === null || v === "") ? "" : " " + String(v);
		}
		let text = "";
		for (let i = 0; i < args.length; i += 2) {
			const v = args[i + 1];
			if (v === undefined || v === null || v === "") continue;
			text += " " + args[i] + "=" + String(v);
		}
		return text;
	},
	withWarning: (text, warning) => warning ? warning + "\n" + text : text,
	applyCollapse: (text) => text,
};
