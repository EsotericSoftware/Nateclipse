// Wraps pi's built-in read tool so its styling matches other Nateclipse tools.

import type { ExtensionAPI, ReadToolDetails, Theme, ThemeColor } from "@earendil-works/pi-coding-agent";
import { createReadToolDefinition, getLanguageFromPath, highlightCode, keyHint } from "@earendil-works/pi-coding-agent";
import { Text } from "@earendil-works/pi-tui";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { access, readdir, stat, unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const URL_CACHE_PREFIX = "pi-read-url-";
const URL_CACHE_MAX_AGE_MS = 48 * 60 * 60 * 1000; // prune cached downloads older than 48h

const COLLAPSED_LINES = 0;
const SHOW_COLLAPSED_MESSAGE = false;

export default async function (pi: ExtensionAPI) {
	//(await import("./util/debug")).default(pi);

	const cwd = process.cwd();
	const originals = new Map<string, ReturnType<typeof createReadToolDefinition>>();
	const getOriginal = (toolCwd: string) => {
		const key = toolCwd || cwd;
		let original = originals.get(key);
		if (!original) {
			original = createReadToolDefinition(key);
			originals.set(key, original);
		}
		return original;
	};
	const original = getOriginal(cwd);

	pi.registerTool<typeof original.parameters, ReadToolDetails | undefined>({
		...original,
		name: "read",
		label: "read",
		promptSnippet: "Read file or URL contents",
		description: "Read the contents of a text or image file or URL. Use offset/limit for large files",
		promptGuidelines: ["Use the read tool instead of bash cat or sed"],
		async execute(toolCallId, params, signal, onUpdate, ctx) {
			const original = getOriginal(ctx?.cwd ?? cwd);
			const argPath = readPathArg(params);
			if (argPath && /^https?:\/\//i.test(argPath.trim())) {
				const url = argPath.trim();
				// Cache the download per URL so offset-based continuation reads reuse the
				// same snapshot instead of re-fetching (avoiding wasted bandwidth and
				// line numbers shifting if the URL's content changes between pages).
				// A fresh read (no offset, or offset<=1) always re-downloads.
				const tmp = join(tmpdir(), URL_CACHE_PREFIX + createHash("sha1").update(url).digest("hex").slice(0, 16));
				const isContinuation = (params?.offset ?? 1) > 1;
				if (!isContinuation || !(await fileExists(tmp))) {
					void pruneUrlCache();
					await curlDownload(url, tmp, signal);
				}
				return original.execute(toolCallId, { ...params, path: tmp }, signal, onUpdate, ctx);
			}
			return original.execute(toolCallId, params, signal, onUpdate, ctx);
		},
		renderCall(params, theme, context) {
			const s = style(theme);
			const argPath = readPathArg(params);
			const path = argPath ? relPath(argPath, context.cwd) : "";
			let text = s.tool("read") + " " + (path ? s.accent(path) : s.dim("..."));
			// Mirror offset/limit as ":start-end" like the built-in renderer.
			if (params?.offset !== undefined || params?.limit !== undefined) {
				const start = params.offset ?? 1;
				const end = params.limit !== undefined ? start + params.limit - 1 : "";
				text += s.lineNumber(`:${start}${end ? `-${end}` : ""}`);
			}
			return new Text(text, 0, 0);
		},
		renderResult(r, options, theme, context) {
			// Images: let the built-in renderer handle attachments/fallbacks.
			const hasImage = r?.content?.some((c) => c.type === "image");
			const originalRenderResult = getOriginal(context.cwd).renderResult;
			if (hasImage && originalRenderResult) return originalRenderResult(r, options, theme, context);

			const s = style(theme);
			const textContent = r?.content?.find((c) => c.type === "text");
			const text = textContent?.type === "text" ? textContent.text : "";
			if (!text) return new Text("", 0, 0);

			// execute() appends a "\n\n[...]" notice at the end when truncation/continuation applies,
			// or the text is a lone "[Line X is ... limit ...]" error (firstLineExceedsLimit case).
			const { body, notice } = splitTrailingNotice(text);

			const offset = (context.args?.offset as number | undefined) ?? 1;
			const path = readPathArg(context.args);
			const lang = path ? getLanguageFromPath(path) : undefined;

			let rendered: string;
			if (!body) {
				rendered = "";
			} else {
				const rawLines = body.replace(/\r/g, "").split("\n");
				const displayLines = lang ? highlightCode(rawLines.join("\n"), lang) : rawLines;
				const width = String(offset + rawLines.length - 1).length;
				rendered = displayLines
					.map((line, i) => s.paddedLine(offset + i, width) + (lang ? line : s.dim(line)))
					.join("\n");
			}

			const collapsed = isCollapsed(rendered, options.expanded);
			let out = s.applyCollapse(rendered, options.expanded);
			if (notice && !collapsed) out += (out ? "\n" : "") + s.dim(notice);
			return new Text("\n" + out, 0, 0);
		},
	});
}

// ---- URL reading (the built-in read tool only handles local files) ----

async function fileExists(p: string): Promise<boolean> {
	try {
		await access(p);
		return true;
	} catch {
		return false;
	}
}

// Best-effort removal of stale cached URL downloads so they don't accumulate.
async function pruneUrlCache(): Promise<void> {
	try {
		const dir = tmpdir();
		const now = Date.now();
		for (const name of await readdir(dir)) {
			if (!name.startsWith(URL_CACHE_PREFIX)) continue;
			const p = join(dir, name);
			try {
				const s = await stat(p);
				if (now - s.mtimeMs > URL_CACHE_MAX_AGE_MS) await unlink(p);
			} catch {
				// ignore files that vanish or can't be stat'd
			}
		}
	} catch {
		// ignore: cache pruning is best-effort
	}
}

// Download a URL to a local file via curl, reusing its redirect/compression/TLS
// handling. The downloaded file is then read by the built-in read tool, which
// owns image detection/resize and text truncation/offset behavior.
function curlDownload(url: string, destPath: string, signal: AbortSignal | undefined): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(new Error("Operation aborted"));
			return;
		}
		const child = spawn("curl", ["-sS", "-f", "-L", "--max-time", "120", "-o", destPath, "--", url], {
			signal,
		});
		const err: Buffer[] = [];
		child.stderr.on("data", (d) => err.push(d));
		child.on("error", (e: any) => {
			if (signal?.aborted || e?.name === "AbortError") reject(new Error("Operation aborted"));
			else reject(new Error(`Failed to run curl for ${url}: ${e?.message ?? e}`));
		});
		child.on("close", (code) => {
			if (signal?.aborted) {
				reject(new Error("Operation aborted"));
				return;
			}
			if (code === 0) {
				resolve();
			} else {
				const msg = Buffer.concat(err).toString().trim() || `curl exited with code ${code}`;
				reject(new Error(`Failed to fetch ${url}: ${msg}`));
			}
		});
	});
}

// Split "<body>\n\n[notice]" at the end, or a lone "[notice]" with no body.
function splitTrailingNotice(text: string): { body: string; notice: string | null } {
	const m = text.match(/\n\n(\[[^\n]+\])\s*$/);
	if (m) return { body: text.slice(0, m.index), notice: m[1] };
	if (/^\[[^\n]+\]\s*$/.test(text)) return { body: "", notice: text.trim() };
	return { body: text, notice: null };
}

function readPathArg(args: any): string | undefined {
	return typeof args?.path === "string" ? args.path : typeof args?.file_path === "string" ? args.file_path : undefined;
}

function isCollapsed(text: string, expanded: boolean): boolean {
	if (expanded || !text) return false;
	const maxLines = Math.max(0, Math.floor(COLLAPSED_LINES));
	return text.split("\n").length > maxLines;
}

function relPath(absPath: string, cwd: string): string {
	const a = absPath.replace(/\\/g, "/");
	const c = cwd.replace(/\\/g, "/");
	if (a.toLowerCase().startsWith(c.toLowerCase())) {
		let rel = a.slice(c.length);
		if (rel[0] === "/") rel = rel.slice(1);
		return rel || a;
	}
	return a;
}

// ---- styling (matches pi-extensions/grep.ts) ----

type Style = {
	yellow: (s: string) => string;
	accent: (s: string) => string;
	dim: (s: string) => string;
	tool: (s: string) => string;
	lineNumber: (s: string) => string;
	paddedLine: (line: number | string | undefined, width: number) => string;
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
		dim: (v) => fg("dim", v),
		tool: white,
		lineNumber: (v) => v.startsWith(":") ? white(":") + yellow(v.slice(1)) : yellow(v),
		paddedLine: (line, width) => (line ? yellow(String(line).padStart(width)) + "  " : ""),
		applyCollapse(text, expanded) {
			if (expanded || !text) return text;
			const maxLines = Math.max(0, Math.floor(COLLAPSED_LINES));
			const lines = text.split("\n");
			if (lines.length <= maxLines) return text;
			const hidden = lines.length - maxLines;
			if (maxLines === 0 && !SHOW_COLLAPSED_MESSAGE) return "";
			const shown = lines.slice(0, maxLines).join("\n");
			const label = maxLines === 0
				? `${hidden} line${hidden === 1 ? "" : "s"}`
				: `${hidden} more line${hidden === 1 ? "" : "s"}`;
			const suffix = fg("muted", `... (${label},`) + " " + keyHint("app.tools.expand", "to expand") + ")";
			return shown ? shown + "\n" + suffix : suffix;
		},
	};
}
