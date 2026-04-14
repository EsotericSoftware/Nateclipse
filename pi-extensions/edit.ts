// Improves the edit tool by providing context when edits fail.
// - When there is no match, returns a fuzzy match for context to save a turn.
// - When edits fail due to multiple occurrences, returns minimal unique context to save a turn.
// - Prefixes `No edits made.` when edits fail to make it clear.

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { createEditTool } from "@mariozechner/pi-coding-agent";

import { readFile } from "fs/promises";
import { resolve } from "path";

interface EditOp { oldText?: string; newText?: string; }
interface EditParams { path?: string; edits?: EditOp[]; }

const MAX_CONTEXT_LINES = 20;
const PREVIEW_LEN = 40;

export default function (pi: ExtensionAPI) {
	const cwd = process.cwd();
	const originalEdit = createEditTool(cwd);

	pi.registerTool({
		name: "edit",
		label: "edit",
		promptSnippet: "Make precise file edits with exact text replacement, including multiple disjoint edits in one call",
		description: "Edit a single file using exact text replacement. Do not emit overlapping or nested edits, merge nearby changes into one edit. Do not include large unchanged regions just to connect distant changes",
		promptGuidelines: [
			"Use edit for precise changes, edits[].oldText must match exactly",
			"Use one edit call with multiple edits[] instead of multiple edit calls",
			"Do not emit overlapping or nested edits, merge nearby changes into one edit",
			"edits[].oldText matches against the original file, not after earlier edits[] are applied",
			"Keep edits[].oldText as small as possible while still being unique in the file, do not pad with large unchanged text",
		],
		parameters: originalEdit.parameters,
		async execute(toolCallId, params, signal, onUpdate) {
			try {
				return await originalEdit.execute(toolCallId, params, signal, onUpdate);
			} catch (err: any) {
				const msg = err?.message ?? String(err);
				// Underlying tool throws on ambiguous matches; enrich with locations.
				if (msg.includes("occurrence"))
					throw new Error("No edits made. " + await enrichDuplicateError(msg, params, cwd));
				if (msg.includes("Could not find"))
					throw new Error("No edits made. " + await enrichNotFoundError(msg, params, cwd));
				throw new Error("No edits made. " + msg);
			}
		},
	});
}

/** Find every occurrence of `needle` in `haystack`, returning 1-based line numbers. */
function findLineNumbers(haystack: string, needle: string): number[] {
	const lines: number[] = [];
	let from = 0;
	while (true) {
		const idx = haystack.indexOf(needle, from);
		if (idx === -1) break;
		// Count newlines up to idx. Could precompute, but this is fine for typical files.
		let line = 1;
		for (let i = 0; i < idx; i++) if (haystack[i] === "\n") line++;
		lines.push(line);
		from = idx + 1;
	}
	return lines;
}

/** Smallest (before, after) window such that all snippets around `startLines` are distinct. */
function findDistinguishingWindow(lines: string[], startLines: number[]): { before: number; after: number } {
	let before = 1;
	let after = 0;
	while (before + after < MAX_CONTEXT_LINES) {
		const snippets = startLines.map(ln => {
			const s = Math.max(0, ln - 1 - before);
			const e = Math.min(lines.length, ln - 1 + after + 1);
			return lines.slice(s, e).join("\n");
		});
		if (new Set(snippets).size === snippets.length) break;
		if (after > before) before++;
		else after++;
	}
	return { before, after };
}

function renderLocation(lines: string[], startLine: number, before: number, after: number, index: number): string {
	const s = Math.max(0, startLine - 1 - before);
	const e = Math.min(lines.length, startLine - 1 + after + 1);
	const snippet = lines.slice(s, e).map((l, j) => `  ${s + j + 1}: ${l}`).join("\n");
	return `#${index + 1} at line ${startLine}:\n${snippet}`;
}

async function enrichDuplicateError(text: string, params: EditParams, cwd: string): Promise<string> {
	try {
		// pi-coding-agent uses a leading "@" to mark file refs; strip it for fs access.
		let filePath = params.path ?? "";
		if (filePath.startsWith("@")) filePath = filePath.slice(1);

		const content = await readFile(resolve(cwd, filePath), "utf-8");
		const lines = content.split("\n");

		// Find all edits whose oldText appears more than once, keeping the match positions.
		const dupes: { oldText: string; startLines: number[] }[] = [];
		for (const edit of params.edits ?? []) {
			if (!edit?.oldText) continue;
			const startLines = findLineNumbers(content, edit.oldText);
			if (startLines.length > 1) dupes.push({ oldText: edit.oldText, startLines });
		}
		if (!dupes.length) return text;

		const sections = dupes.map(({ oldText, startLines }) => {
			const { before, after } = findDistinguishingWindow(lines, startLines);
			const locations = startLines.map((ln, i) => renderLocation(lines, ln, before, after, i));
			if (dupes.length > 1) {
				const preview = oldText.slice(0, PREVIEW_LEN).replace(/\n/g, "\\n");
				return `"${preview}..."\n${locations.join("\n\n")}`;
			}
			return locations.join("\n\n");
		});

		return text + "\n\n" + sections.join("\n\n");
	} catch (err) {
		console.debug("enrichDuplicateError failed:", err);
		return text;
	}
}

function levenshtein(a: string, b: string): number {
	const m = a.length, n = b.length;
	let prev = Array.from({length: n + 1}, (_, i) => i);
	for (let i = 1; i <= m; i++) {
		const curr = [i];
		for (let j = 1; j <= n; j++)
			curr[j] = a[i - 1] === b[j - 1] ? prev[j - 1] : 1 + Math.min(prev[j - 1], prev[j], curr[j - 1]);
		prev = curr;
	}
	return prev[n];
}

/** Strip whitespace, returning stripped string and mapping from stripped index to original index. */
function stripWhitespace(text: string): { stripped: string; mapping: number[] } {
	const mapping: number[] = [];
	let stripped = "";
	for (let i = 0; i < text.length; i++) {
		const ch = text[i];
		if (ch !== " " && ch !== "\t" && ch !== "\n" && ch !== "\r") {
			stripped += ch;
			mapping.push(i);
		}
	}
	return { stripped, mapping };
}

/** Find the position in fileContent that best matches oldText using Levenshtein on whitespace-stripped content. */
function fuzzyFind(fileContent: string, oldText: string): { origStart: number; origEnd: number; score: number } | null {
	const file = stripWhitespace(fileContent);
	const needle = stripWhitespace(oldText);
	if (needle.stripped.length === 0 || file.stripped.length === 0) return null;

	const windowLen = needle.stripped.length;
	if (file.stripped.length < windowLen) return null;

	// Slide window, find minimum Levenshtein distance.
	let bestDist = Infinity;
	let bestPos = 0;

	// For very long files, sample every Nth position first to find rough location, then refine.
	const totalPositions = file.stripped.length - windowLen + 1;
	const step = totalPositions > 5000 ? Math.max(1, Math.floor(totalPositions / 1000)) : 1;

	// Coarse pass.
	for (let i = 0; i < totalPositions; i += step) {
		const window = file.stripped.substring(i, i + windowLen);
		const d = levenshtein(needle.stripped, window);
		if (d < bestDist) { bestDist = d; bestPos = i; }
		if (d === 0) break;
	}

	// Fine pass around the best coarse position.
	if (step > 1) {
		const from = Math.max(0, bestPos - step);
		const to = Math.min(totalPositions, bestPos + step);
		for (let i = from; i < to; i++) {
			const window = file.stripped.substring(i, i + windowLen);
			const d = levenshtein(needle.stripped, window);
			if (d < bestDist) { bestDist = d; bestPos = i; }
		}
	}

	// Map back to original file positions.
	const origStart = file.mapping[bestPos];
	const origEnd = file.mapping[Math.min(bestPos + windowLen - 1, file.mapping.length - 1)] + 1;
	const similarity = 1 - bestDist / windowLen;

	return { origStart, origEnd, score: similarity };
}

async function enrichNotFoundError(text: string, params: EditParams, cwd: string): Promise<string> {
	try {
		let filePath = params.path ?? "";
		if (filePath.startsWith("@")) filePath = filePath.slice(1);
		const content = await readFile(resolve(cwd, filePath), "utf-8");

		const sections: string[] = [];
		for (const edit of params.edits ?? []) {
			if (!edit?.oldText) continue;
			// Skip edits that exist exactly (only enriching the ones that failed).
			if (content.includes(edit.oldText)) continue;

			const match = fuzzyFind(content, edit.oldText);
			if (!match || match.score < 0.3) continue;

			// Extract the matched region with line numbers.
			let lineNum = 1;
			for (let i = 0; i < match.origStart; i++) if (content[i] === "\n") lineNum++;

			// Show 2 lines before + matched region + 1 line after.
			const allLines = content.split("\n");
			const matchedLineCount = content.substring(match.origStart, match.origEnd).split("\n").length;
			const ctxStart = Math.max(0, lineNum - 1 - 2);
			const ctxEnd = Math.min(allLines.length, lineNum - 1 + matchedLineCount + 1);
			const numbered = allLines.slice(ctxStart, ctxEnd).map((l, i) => `${ctxStart + i + 1} ${l}`).join("\n");

			sections.push(`Closest match at line ${lineNum}:\n${numbered}`);
		}

		if (!sections.length) return text;
		return text + "\n\n" + sections.join("\n\n");
	} catch (err) {
		console.debug("enrichNotFoundError failed:", err);
		return text;
	}
}
