import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const KEEP_IMAGES = 5;
const PRUNED_TEXT = "Image pruned from context";

let enabled = true;

function isImageBlock(block: unknown): boolean {
	return !!block && typeof block === "object" && (block as any).type === "image";
}

function textBlock(): { type: "text"; text: string } {
	return { type: "text", text: PRUNED_TEXT };
}

function collectImageBlocks(messages: any[]): any[] {
	const images: any[] = [];
	for (const message of messages) {
		const content = message?.content;
		if (!Array.isArray(content)) continue;
		for (const block of content) {
			if (isImageBlock(block)) images.push(block);
		}
	}
	return images;
}

function pruneImages(messages: any[]): { messages: any[]; pruned: number; totalImages: number } {
	const images = collectImageBlocks(messages);
	const totalImages = images.length;
	const keep = new Set(images.slice(Math.max(0, images.length - KEEP_IMAGES)));
	let pruned = 0;

	for (const message of messages) {
		const content = message?.content;
		if (!Array.isArray(content)) continue;
		message.content = content.map((block: unknown) => {
			if (!isImageBlock(block) || keep.has(block)) return block;
			pruned++;
			return textBlock();
		});
	}

	return { messages, pruned, totalImages };
}

export default function (pi: ExtensionAPI) {
	pi.on("session_start", async (_event, ctx) => {
		if (!enabled) ctx.ui.setStatus("image-pruner", undefined);
	});

	pi.registerCommand("image-pruner", {
		description: "Enable/disable pruning old images from LLM context. Usage: /image-pruner [on|off|status]",
		handler: async (args, ctx) => {
			const arg = args.trim().toLowerCase();
			if (arg === "on" || arg === "enable" || arg === "enabled") {
				enabled = true;
				ctx.ui.notify(`Image pruner enabled; keeping last ${KEEP_IMAGES} images.`, "info");
				return;
			}
			if (arg === "off" || arg === "disable" || arg === "disabled") {
				enabled = false;
				ctx.ui.setStatus("image-pruner", undefined);
				ctx.ui.notify("Image pruner disabled.", "warning");
				return;
			}
			if (arg && arg !== "status") {
				ctx.ui.notify("Usage: /image-pruner [on|off|status]", "warning");
				return;
			}
			if (!enabled) ctx.ui.setStatus("image-pruner", undefined);
			ctx.ui.notify(`Image pruner is ${enabled ? "enabled" : "disabled"}; keeping last ${KEEP_IMAGES} images.`, "warning");
		},
	});

	pi.on("context", async (event, ctx) => {
		if (!enabled) return;
		const result = pruneImages(event.messages as any[]);
		ctx.ui.setStatus(
			"image-pruner",
			result.pruned > 0 ? ctx.ui.theme.fg("dim", `pruned ${result.pruned} images`) : undefined,
			//result.pruned > 0 ? ctx.ui.theme.fg("dim", `pruned ${result.pruned}/${result.totalImages} images`) : undefined,
		);
		return { messages: result.messages };
	});
}
