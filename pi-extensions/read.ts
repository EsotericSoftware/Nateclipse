// Wraps pi's built-in read tool so its renderCall/renderResult match the other Nateclipse tools:
// - Tool name and path styled like grep / java_*.
// - Each output line prefixed with a right-padded 1-based line number ("  42  text"), offset-aware.
// Behavior (execute) is the default, only rendering is overridden.

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { createReadToolDefinition, getLanguageFromPath, highlightCode, keyHint } from "@mariozechner/pi-coding-agent";
import { Text } from "@mariozechner/pi-tui";

export default function (pi: ExtensionAPI) {
	const cwd = process.cwd();
	const original = createReadToolDefinition(cwd);
	const originalRenderResult = original.renderResult;

	pi.registerTool({
		...original,
		name: "read",
		label: "read",
		promptSnippet: "Read file contents",
		description: "Read the contents of a text or image file. Use offset/limit for large files",
		promptGuidelines: ["Use the read tool instead of bash cat or sed"],
		renderCall(params, theme, context) {
			const s = style(theme);
			const path = params?.path ? relPath(params.path, context.cwd) : "";
			let text = s.tool("read") + " " + (path ? s.accent(path) : s.dim("..."));
			// Mirror offset/limit as ":start-end" like the built-in renderer.
			if (params?.offset !== undefined || params?.limit !== undefined) {
				const start = params.offset ?? 1;
				const end = params.limit !== undefined ? start + params.limit - 1 : "";
				text += s.yellow(`:${start}${end ? `-${end}` : ""}`);
			}
			return new Text(text, 0, 0);
		},
		renderResult(r, options, theme, context) {
			// Images: let the built-in renderer handle attachments/fallbacks.
			const hasImage = r?.content?.some((c: any) => c.type === "image");
			if (hasImage && originalRenderResult) return originalRenderResult(r, options, theme, context);

			const s = style(theme);
			const text = (r?.content?.find((c: any) => c.type === "text") as any)?.text ?? "";
			if (!text) return new Text("", 0, 0);

			// execute() appends a "\n\n[...]" notice at the end when truncation/continuation applies,
			// or the text is a lone "[Line X is ... limit ...]" error (firstLineExceedsLimit case).
			const { body, notice } = splitTrailingNotice(text);

			const offset = (context.args?.offset as number | undefined) ?? 1;
			const path = context.args?.path as string | undefined;
			const lang = path ? getLanguageFromPath(path) : undefined;

			let rendered: string;
			if (!body) {
				rendered = "";
			} else {
				const rawLines = body.replace(/\r/g, "").split("\n");
				const displayLines = lang ? highlightCode(rawLines.join("\n"), lang) : rawLines;
				const width = String(offset + rawLines.length - 1).length;
				rendered = displayLines
					.map((line, i) => s.paddedLine(offset + i, width) + "  " + (lang ? line : s.dim(line)))
					.join("\n");
			}

			let out = rendered;
			if (notice) out += (out ? "\n" : "") + s.dim(notice);
			return new Text("\n" + s.applyCollapse(out, options.expanded), 0, 0);
		},
	});
}

// Split "<body>\n\n[notice]" at the end, or a lone "[notice]" with no body.
function splitTrailingNotice(text: string): { body: string; notice: string | null } {
	const m = text.match(/\n\n(\[[^\n]+\])\s*$/);
	if (m) return { body: text.slice(0, m.index), notice: m[1] };
	if (/^\[[^\n]+\]\s*$/.test(text)) return { body: "", notice: text.trim() };
	return { body: text, notice: null };
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
	paddedLine: (line: number | string, width: number) => string;
	applyCollapse: (text: string, expanded: boolean) => string;
};

function style(theme: any): Style {
	const fg = (k: string, v: string) => theme.fg(k, v);
	const bold = (v: string) => theme.bold(v);
	const yellow = (v: string) => fg("warning", v);
	return {
		yellow,
		accent: (v) => fg("accent", v),
		dim: (v) => fg("dim", v),
		tool: (v) => fg("toolTitle", bold(v)),
		paddedLine: (line, width) => yellow(String(line).padStart(width)),
		applyCollapse(text, expanded) {
			if (expanded) return text;
			const lines = text.split("\n");
			if (lines.length <= 10) return text;
			const remaining = lines.length - 10;
			return lines.slice(0, 10).join("\n") + fg("muted", `\n... (${remaining} more lines,`) + " " + keyHint("app.tools.expand", "to expand") + ")";
		},
	};
}
