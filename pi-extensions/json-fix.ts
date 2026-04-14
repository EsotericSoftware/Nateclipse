// Sanitize control characters in SSE streams that break JSON.parse.
// Some LLM APIs send literal tabs etc in JSON SSE data.

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";

const originalFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
	const response = await originalFetch(input, init);
	const contentType = response.headers.get("content-type") || "";
	if (!contentType.includes("text/event-stream") || !response.body) return response;

	const reader = response.body.getReader();
	const decoder = new TextDecoder();
	const encoder = new TextEncoder();
	const transformed = new ReadableStream<Uint8Array>({
		async pull(controller) {
			const { done, value } = await reader.read();
			if (done) { controller.close(); return; }
			let text = decoder.decode(value, { stream: true });
			// eslint-disable-next-line no-control-regex
			text = text.replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, (ch) => {
				return `\\u${ch.charCodeAt(0).toString(16).padStart(4, "0")}`;
			});
			controller.enqueue(encoder.encode(text));
		},
		cancel() { reader.cancel(); },
	});
	return new Response(transformed, {
		status: response.status, statusText: response.statusText, headers: response.headers,
	});
};

export default function (pi: ExtensionAPI) {}
