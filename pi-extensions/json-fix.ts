// Lets the agent recover when an LLM sends literal tabs etc in JSON SSE data.
// manuranga https://github.com/badlogic/pi-mono/issues/2681#issuecomment-4228545404

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import type { AssistantMessage } from "@mariozechner/pi-ai";

export default function (pi: ExtensionAPI) {
	let failedMessageTimestamp: number | undefined;

	pi.on("agent_end", (event) => {
		const last = event.messages[event.messages.length - 1];
		if (!last || last.role !== "assistant") return;

		const msg = last as AssistantMessage;
		if (msg.stopReason !== "error" || !msg.errorMessage) return;
		if (!/Bad control character/i.test(msg.errorMessage)) return;

		failedMessageTimestamp = msg.timestamp;

		setTimeout(() => pi.sendUserMessage("edit tool failed, escape tab as \\t"), 100);
	});

	pi.on("context", (event) => {
		if (failedMessageTimestamp === undefined) return;
		const timestamp = failedMessageTimestamp;
		failedMessageTimestamp = undefined;
		return {
			messages: event.messages.filter((m) => {
				if (m.role !== "assistant") return true;
				const msg = m as AssistantMessage;
				return msg.timestamp !== timestamp;
			}),
		};
	});
}
