// Debug helper for previewing the exact text a tool sends back to the model.
//
// When installed, every subsequent pi.registerTool() call has its renderResult
// wrapped so the displayed output is just `firstText(result.content)` -- i.e.
// the model-facing text -- instead of the styled/collapsed rendering. Partial
// (streaming) frames keep their original renderer so the spinner still works,
// and image-bearing results (e.g. read on an image) fall through unchanged so
// attachments still display.
//
// Not auto-discovered: this lives at ~/.pi/agent/extensions/util/debug.ts and
// pi only auto-loads `*.ts` at the extensions root or `*/index.ts` in subdirs,
// so a sibling file in `util/` is just an importable module.
//
// Usage: from each extension factory you want to debug, add ONE line at the
// top of the factory body and make the factory async:
//
//     (await import("./util/debug")).default(pi);
//
// Relative resolution works even though Nateclipse extensions are symlinked
// in: pi's jiti preserves symlinks for module resolution, so `./util/debug`
// from a symlinked `nateclipse.ts` resolves under ~/.pi/agent/extensions/.

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { Text } from "@mariozechner/pi-tui";

export default function installRawResultDebug(pi: ExtensionAPI): void {
	const origRegister = pi.registerTool.bind(pi);
	pi.registerTool = ((def: any) => {
		const prev = def.renderResult;
		def.renderResult = (r: any, opts: any, theme: any, ctx: any) => {
			// Streaming partial frames: keep the tool's own loading indicator.
			if (opts?.isPartial && prev) return prev(r, opts, theme, ctx);
			// Image-bearing results (read tool on images): preserve attachment rendering.
			if (Array.isArray(r?.content) && r.content.some((c: any) => c?.type === "image") && prev)
				return prev(r, opts, theme, ctx);
			const first = r?.content?.find?.((c: any) => c?.type === "text");
			const text = first?.type === "text" ? first.text : "";
			return new Text("\n" + text, 0, 0);
		};
		return origRegister(def);
	}) as typeof pi.registerTool;
}
