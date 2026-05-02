// Grep tool for nicer output than bash grep, provides hints for recovery when
// there are no matches, and ignores `.git` and other folders.

import type { AgentToolResult, ExtensionAPI, Theme, ThemeColor } from "@mariozechner/pi-coding-agent";
import { getLanguageFromPath, highlightCode, keyHint } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import { Text } from "@mariozechner/pi-tui";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { statSync } from "node:fs";

const MAX_MATCHES = 100;
const MAX_LINE_LENGTH = 500;
const DEFAULT_EXCLUDE_DIRS = [".git", "node_modules", "bin", "target"];

export default async function (pi: ExtensionAPI) {
	//(await import("./util/debug")).default(pi);

	pi.registerTool({
		name: "grep",
		label: "grep",
		promptSnippet: "Search file contents with grep",
		description: "Runs grep. All grep flags supported. Default is BRE, unescaped | adds -E unless using -F/-P",
		promptGuidelines: ["Use the grep tool instead of bash grep"],
		parameters: Type.Object({
			pattern: Type.String({ description: "Grep pattern" }),
			path: Type.Optional(Type.String({ description: "Directory or file to search. Default: CWD" })),
			flags: Type.Optional(Type.String({ description: "Grep flags eg: --include=*.ts -i -A3 -B2 -C5 -E. Default: -n" })),
		}),
		renderCall(params, theme, context) {
			const s = style(theme);
			let text = s.tool("grep") + " " + s.yellow(params.pattern);
			if (params.path) text += " " + s.accent(relPath(params.path, context.cwd));
			text += " " + s.yellow(effectiveGrepFlags(params.pattern, splitFlags(params.flags)).join(" "));
			return new Text(text, 0, 0);
		},
		async execute(_id, params, signal, _onUpdate, ctx) {
			const searchPath = params.path || ".";
			const userFlags = effectiveGrepFlags(params.pattern, splitFlags(params.flags));

			// Detect dir vs file for output formatting + auto -r injection.
			let isDir = false;
			try {
				isDir = statSync(resolveAgainst(ctx.cwd, searchPath)).isDirectory();
			} catch {
				throw new Error(`Path not found: ${searchPath}`);
			}

			// Inject -r for directories unless already present.
			const hasRecursive = userFlags.some((f) => /^-[a-zA-Z]*[rR]$/.test(f) || f === "--recursive" || f === "--dereference-recursive");
			const hasIncludeOrExcludeDir = userFlags.some((f) => f.startsWith("--include=") || f.startsWith("--exclude-dir="));
			const autoFlags: string[] = ["-H"]; // uniform "file:line:content"
			if (isDir && !hasRecursive) autoFlags.push("-R"); // -R follows symlinks; GNU grep detects loops and warns.
			if (isDir && !hasIncludeOrExcludeDir) {
				for (const d of DEFAULT_EXCLUDE_DIRS) autoFlags.push(`--exclude-dir=${d}`);
			}

			const allFlags = [...autoFlags, ...userFlags];
			// Flags that change grep's output format so our "file:line:content" parser wouldn't apply.
			// -l/-L: file list. -c: file:count. When present we capture raw stdout instead.
			const rawMode = userFlags.some((f) =>
				(f.startsWith("-") && !f.startsWith("--") && /[lLc]/.test(f)) ||
				f === "--files-with-matches" || f === "--files-without-match" || f === "--count",
			);
			const { rows, rawLines, stderr, code, matchLimitReached, linesTruncated } = await runGrep(
				allFlags,
				params.pattern,
				searchPath,
				MAX_MATCHES,
				ctx.cwd,
				rawMode,
				signal,
			);

			const hasOutput = rawMode ? rawLines.length > 0 : rows.length > 0;
			if (!hasOutput) {
				const cmd = `grep ${allFlags.join(" ")} ${JSON.stringify(params.pattern)} ${searchPath}`.replace(/\s+/g, " ").trim();
				let msg = `No matches for: ${cmd}`;
				if (code === 2 && stderr) msg += `\n${stderr.trim().split("\n")[0].replace(/^grep:\s*/, "")}`;

				throw new Error(msg);
			}

			const notices = buildNotices(matchLimitReached, linesTruncated);
			const body = rawMode
				? formatRaw(plain, rawLines, ctx.cwd)
				: formatGrep(plain, rows, ctx.cwd);
			const plainText = body + (notices ? `\n[${notices}]` : "");
			return result(plainText, {
				rows,
				rawLines,
				rawMode,
				cwd: ctx.cwd,
				matchLimitReached,
				linesTruncated,
			});
		},
		renderResult(r, { isPartial, expanded }, theme) {
			const s = style(theme);
			if (isPartial) return new Text("\n" + s.yellow("Searching..."), 0, 0);
			const d = r.details;
			if (!d || (!d.rows?.length && !d.rawLines?.length)) {
				const first = r.content[0];
				const text = first?.type === "text" ? first.text : "No matches found.";
				return new Text("\n" + s.applyCollapse(text, expanded), 0, 0);
			}
			let body = d.rawMode
				? formatRaw(s, d.rawLines ?? [], d.cwd ?? "")
				: formatGrep(s, d.rows ?? [], d.cwd ?? "");
			const notices = buildNotices(d.matchLimitReached, d.linesTruncated);
			if (notices) body += "\n" + s.dim(`[${notices}]`);
			return new Text("\n" + s.applyCollapse(body, expanded), 0, 0);
		},
	});
}

// ---- grep runner ----

type Row = { file: string; line: number; content: string; isMatch: boolean };

async function runGrep(
	flags: string[],
	pattern: string,
	searchPath: string,
	limit: number,
	cwd: string,
	rawMode: boolean,
	signal: AbortSignal | undefined,
): Promise<{ rows: Row[]; rawLines: string[]; stderr: string; code: number; matchLimitReached: boolean; linesTruncated: boolean }> {
	return new Promise((resolve, reject) => {
		if (signal?.aborted) { reject(new Error("Operation aborted")); return; }

		const args = [...flags, pattern, searchPath];
		const child = spawn("grep", args, { cwd, stdio: ["ignore", "pipe", "pipe"] });
		const rl = createInterface({ input: child.stdout });

		const rows: Row[] = [];
		const rawLines: string[] = [];
		let stderr = "";
		let matchCount = 0;
		let matchLimitReached = false;
		let linesTruncated = false;
		let aborted = false;
		let killedByLimit = false;

		const onAbort = () => { aborted = true; if (!child.killed) child.kill(); };
		signal?.addEventListener("abort", onAbort, { once: true });

		child.stderr?.on("data", (c) => { stderr += c.toString(); });

		rl.on("line", (line) => {
			if (!line || line === "--") return;
			if (matchLimitReached) return; // drop any in-flight rows after we signalled kill
			if (rawMode) {
				const { text: truncated, wasTruncated } = truncateLine(line);
				if (wasTruncated) linesTruncated = true;
				rawLines.push(truncated);
				matchCount++;
				if (matchCount >= limit) {
					matchLimitReached = true;
					if (!child.killed) { killedByLimit = true; child.kill(); }
				}
				return;
			}
			// `(.+?)([-:])(\d+)\2(.*)` — second occurrence of same separator after line number.
			const m = line.match(/^(.+?)([-:])(\d+)\2(.*)$/);
			if (!m) return;
			const content = m[4];
			const { text: truncated, wasTruncated } = truncateLine(content);
			if (wasTruncated) linesTruncated = true;
			const isMatch = m[2] === ":";
			rows.push({ file: m[1], line: parseInt(m[3], 10), content: truncated, isMatch });
			if (isMatch) {
				matchCount++;
				if (matchCount >= limit) {
					matchLimitReached = true;
					if (!child.killed) { killedByLimit = true; child.kill(); }
				}
			}
		});

		child.on("error", (err) => {
			signal?.removeEventListener("abort", onAbort);
			reject(new Error(`Failed to run grep: ${err.message}`));
		});
		child.on("close", (code) => {
			signal?.removeEventListener("abort", onAbort);
			if (aborted) { reject(new Error("Operation aborted")); return; }
			// grep exit: 0 = match, 1 = no match, 2 = error.
			const exit = killedByLimit ? 0 : (code ?? 1);
			resolve({ rows, rawLines, stderr: stderr.replace(/\r/g, ""), code: exit, matchLimitReached, linesTruncated });
		});
	});
}

function splitFlags(flags: string | undefined): string[] {
	const parts = (flags || "-n").split(/\s+/).filter(Boolean);
	return parts.length ? parts : ["-n"];
}

function effectiveGrepFlags(pattern: string, flags: string[]): string[] {
	if (!hasBarePipe(pattern) || hasGrepRegexModeFlag(flags)) return flags;
	const out = [...flags];
	const endOfOptions = out.indexOf("--");
	if (endOfOptions >= 0) out.splice(endOfOptions, 0, "-E");
	else out.push("-E");
	return out;
}

function hasGrepRegexModeFlag(flags: string[]): boolean {
	return flags.some((f) =>
		(f.startsWith("-") && !f.startsWith("--") && /[EFP]/.test(f.slice(1))) ||
		f === "--extended-regexp" || f === "--fixed-strings" || f === "--perl-regexp"
	);
}

function hasBarePipe(pattern: string): boolean {
	let escaped = false;
	let inClass = false;
	for (const ch of pattern) {
		if (escaped) { escaped = false; continue; }
		if (ch === "\\") { escaped = true; continue; }
		if (ch === "[" && !inClass) { inClass = true; continue; }
		if (ch === "]" && inClass) { inClass = false; continue; }
		if (ch === "|" && !inClass) return true;
	}
	return false;
}

function buildNotices(matchLimitReached: boolean | undefined, linesTruncated: boolean | undefined): string {
	const parts: string[] = [];
	if (matchLimitReached) parts.push(`${MAX_MATCHES} match limit reached. Use -m N to cap per-file, refine pattern, or narrow path`);
	if (linesTruncated) parts.push(`Some lines truncated to ${MAX_LINE_LENGTH} chars`);
	return parts.join(". ");
}

function truncateLine(s: string): { text: string; wasTruncated: boolean } {
	const sanitized = s.replace(/\r/g, "");
	if (sanitized.length <= MAX_LINE_LENGTH) return { text: sanitized, wasTruncated: false };
	return { text: sanitized.slice(0, MAX_LINE_LENGTH) + "…", wasTruncated: true };
}

// ---- formatting ----

// Used for -l/-L (file lists) and -c (file:count). Each raw line may start with a file path; relativize it.
// For -c we reformat "file:count" as "<count>  <file>" (count right-aligned) to match the grep match format
// of "<line>  <content>".
function formatRaw(s: Style, lines: string[], cwd: string): string {
	// Parse once to learn max count width, then render.
	const parsed = lines.map((line) => {
		// Split on the LAST colon followed only by digits so Windows drive letters ("C:/...") are ignored.
		const m = line.match(/^(.*):(\d+)$/);
		return m ? { file: m[1], count: m[2] } : { file: line, count: undefined };
	});
	let w = 0;
	for (const p of parsed) if (p.count) w = Math.max(w, p.count.length);
	return parsed.map((p) => s.paddedLine(p.count, w) + s.filePath(relPath(p.file, cwd))).join("\n");
}

function formatGrep(s: Style, rows: Row[], cwd: string): string {
	const byFile = new Map<string, Row[]>();
	for (const r of rows) {
		if (!byFile.has(r.file)) byFile.set(r.file, []);
		byFile.get(r.file)!.push(r);
	}
	let w = 0;
	for (const r of rows) w = Math.max(w, String(r.line).length);
	const parts: string[] = [];
	for (const [file, fileRows] of byFile) {
		parts.push(s.filePath(relPath(file, cwd)));
		const lang = getLanguageFromPath(file);
		for (const r of fileRows) {
			const highlighted = lang ? s.code(r.content, lang) : r.content;
			const body = r.isMatch ? highlighted : s.dim(r.content);
			parts.push(s.paddedLine(r.line, w) + body);
		}
	}
	return parts.join("\n");
}

function resolveAgainst(cwd: string, p: string): string {
	if (/^(?:[a-zA-Z]:[\\/]|[\\/])/.test(p)) return p;
	return cwd.replace(/[\\/]+$/, "") + "/" + p;
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

function result<T>(text: string, details: T): AgentToolResult<T> {
	return { content: [{ type: "text", text }], details };
}

// ---- styling (vendored from nateclipse.ts, trimmed) ----

type Style = {
	yellow: (s: string) => string;
	accent: (s: string) => string;
	filePath: (s: string) => string;
	dim: (s: string) => string;
	tool: (s: string) => string;
	code: (s: string, lang: string) => string;
	paddedLine: (line: number | string | undefined, width: number) => string;
	extra: (...args: Array<string | number | undefined | null>) => string;
	applyCollapse: (text: string, expanded: boolean) => string;
};

function style(theme: Theme): Style {
	const fg = (k: ThemeColor, v: string) => theme.fg(k, v);
	const bold = (v: string) => theme.bold(v);
	const yellow = (v: string) => fg("warning", v);
	const white = (v: string) => fg("toolTitle", bold(v));
	return {
		yellow,
		accent: (v) => fg("accent", v),
		filePath: (v) => fg("success", v),
		dim: (v) => fg("dim", v),
		tool: white,
		code: (code, lang) => highlightCode(code.replace(/\r/g, ""), lang).join("\n"),
		paddedLine: (line, width) => (line ? yellow(String(line).padStart(width)) + "  " : ""),
		extra(...args) {
			if (args.length == 1) {
				const v = args[0];
				return v === undefined || v === null || v === "" ? "" : " " + yellow(String(v));
			}
			let text = "";
			for (let i = 0; i < args.length; i += 2) {
				const v = args[i + 1];
				if (v === undefined || v === null || v === "") continue;
				text += " " + yellow(args[i] + "=") + white(String(v));
			}
			return text;
		},
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
	yellow: id,
	accent: id,
	filePath: id,
	dim: id,
	tool: id,
	code: (code) => code.replace(/\r/g, ""),
	paddedLine: (line, width) => (line ? String(line).padStart(width) + "  " : ""),
	extra(...args) {
		if (args.length == 1) {
			const v = args[0];
			return v === undefined || v === null || v === "" ? "" : " " + String(v);
		}
		let text = "";
		for (let i = 0; i < args.length; i += 2) {
			const v = args[i + 1];
			if (v === undefined || v === null || v === "") continue;
			text += " " + args[i] + "=" + String(v);
		}
		return text;
	},
	applyCollapse: (text) => text,
};
