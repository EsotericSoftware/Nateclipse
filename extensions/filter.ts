import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { isKeyRelease, isKeyRepeat, matchesKey, type Component, type TUI } from "@earendil-works/pi-tui";

const PROBE_WIDGET_KEY = "auto-turn-filter-probe";
const RUNTIME_KEY = "__piAutoTurnFilterRuntime";
const ANSI_SGR_RE = /\x1b\[[0-9;]*m/g;
const ERROR_OR_WARNING_RE = /^(Error|Warning):\s/;

// fold: tool runs between prose collapse, live work shows raw.
// fold all: live work collapses too.
type FilterMode = "fold" | "fold all" | "off";
const FILTER_MODES: readonly FilterMode[] = ["fold", "fold all", "off"];

type AnyComponent = Component & Record<string, any>;
type ContainerLike = AnyComponent & { children: AnyComponent[] };

type PatchedContainer = ContainerLike & {
	__piFilterOriginalRender?: (width: number) => string[];
};

type PieceCacheEntry = {
	message: any;
	signature: string;
	hideThinkingBlock: boolean;
	markdownTheme: any;
	hiddenThinkingLabel: string;
	outputPad: number;
	markdownTransformers: any;
	isStreaming: boolean;
	pieces: AnyComponent[];
};

// Memoized splitAssistant pieces per source component. Reusing clone instances
// across frames keeps their internal Markdown/Text line caches warm, which is
// the expensive part. Streaming rows change signature every frame and rebuild.
const pieceCache = new WeakMap<AnyComponent, PieceCacheEntry>();

class EmptyComponent implements Component {
	render(): string[] {
		return [];
	}
}

class HeaderComponent implements Component {
	constructor(
		private readonly toolCallCount: number,
		private readonly failedCount: number,
		private readonly toolNames: Map<string, number>,
		private readonly theme: any,
	) {}

	render(): string[] {
		const label =
			this.toolCallCount > 0
				? `${this.toolCallCount} tool call${this.toolCallCount === 1 ? "" : "s"}`
				: "thoughts";
		const fg = (color: string, text: string) => (this.theme?.fg ? this.theme.fg(color, text) : text);
		const parts = [...this.toolNames]
			.sort(([a], [b]) => (a === "edit" ? -1 : b === "edit" ? 1 : a.localeCompare(b)))
			.map(([name, count]) => (name === "edit" ? fg("warning", `${name} ${count}x`) : fg("muted", name)));
		const names = parts.length > 0 ? fg("muted", ": ") + parts.join(fg("muted", ", ")) : "";
		const failed = this.failedCount > 0 ? fg("muted", " · ") + fg("error", `${this.failedCount} failed`) : "";
		return ["", fg("muted", `▸ ${label}`) + names + failed];
	}
}

function assistantMessage(component: AnyComponent): any | undefined {
	const message = component?.lastMessage;
	return message?.role === "assistant" ? message : undefined;
}

function assistantHasText(component: AnyComponent): boolean {
	const message = assistantMessage(component);
	return !!message?.content?.some((c: any) => c?.type === "text" && String(c.text ?? "").trim());
}

function assistantHasThinking(component: AnyComponent): boolean {
	const message = assistantMessage(component);
	return !!message?.content?.some((c: any) => c?.type === "thinking" && String(c.thinking ?? "").trim());
}

function assistantHasToolCall(component: AnyComponent): boolean {
	const message = assistantMessage(component);
	return !!message?.content?.some((c: any) => c?.type === "toolCall");
}

function assistantHasVisibleStatus(component: AnyComponent): boolean {
	const message = assistantMessage(component);
	if (!message) return false;
	if (message.stopReason === "length") return true;
	return (message.stopReason === "error" || message.stopReason === "aborted") && !assistantHasToolCall(component);
}

function stripAnsi(text: string): string {
	return text.replace(ANSI_SGR_RE, "");
}

function isErrorTextBoundary(component: AnyComponent): boolean {
	if (component?.constructor?.name !== "Text") return false;
	const text = typeof component.text === "string" ? stripAnsi(component.text).trim() : "";
	return ERROR_OR_WARNING_RE.test(text);
}

function isAssistantBoundary(component: AnyComponent): boolean {
	return assistantHasText(component) || assistantHasVisibleStatus(component) || isErrorTextBoundary(component);
}

function isUserBoundary(component: AnyComponent): boolean {
	const name = component?.constructor?.name;
	return name === "UserMessageComponent" || name === "SkillInvocationMessageComponent";
}

function isToolExecution(component: AnyComponent): boolean {
	return component?.constructor?.name === "ToolExecutionComponent";
}

function isFoldable(component: AnyComponent): boolean {
	// Tool-call-only assistant components render as empty rows in restored transcripts,
	// but they occur between visible tool rows and should not break the fold.
	return isToolExecution(component) || assistantHasThinking(component) || (assistantHasToolCall(component) && !assistantHasText(component));
}

function isChatLike(container: ContainerLike): boolean {
	return container.children.some(
		(child) => isUserBoundary(child) || isAssistantBoundary(child) || isToolExecution(child) || assistantHasThinking(child),
	);
}

function cloneAssistantPiece(
	original: AnyComponent,
	content: any[],
	kind: "thinking" | "text" | "status" | "toolCall",
): AnyComponent | undefined {
	const message = assistantMessage(original);
	if (!message) return undefined;

	try {
		const Ctor = original.constructor as new (...args: any[]) => AnyComponent;
		const piece = new Ctor(
			undefined,
			original.hideThinkingBlock ?? false,
			original.markdownTheme,
			original.hiddenThinkingLabel,
			original.outputPad ?? 1,
			original.markdownTransformers ?? [],
		);
		// Constructor can't take the streaming flag; markdown transformers see it via updateContent.
		piece.updateContent(
			{
				...message,
				content,
				// Only the status piece renders error/abort/truncation lines, so they are not duplicated under prose.
				stopReason: kind === "status" ? message.stopReason : undefined,
				errorMessage: kind === "status" ? message.errorMessage : undefined,
			},
			original.isStreaming ?? false,
		);
		return piece;
	} catch {
		return undefined;
	}
}

function messageSignature(message: any): string {
	const parts: string[] = [String(message.stopReason ?? ""), String(message.errorMessage ?? "")];
	for (const content of message.content ?? []) {
		if (content?.type === "thinking") parts.push(`k${String(content.thinking ?? "").length}`);
		else if (content?.type === "text") parts.push(`t${String(content.text ?? "").length}`);
		else if (content?.type === "toolCall") parts.push(`c${String(content.id ?? "")}`);
		else parts.push(String(content?.type ?? "u"));
	}
	return parts.join("|");
}

function splitAssistant(component: AnyComponent): AnyComponent[] {
	const message = assistantMessage(component);
	if (!message) return [component];

	const signature = messageSignature(message);
	const cached = pieceCache.get(component);
	if (
		cached &&
		cached.message === message &&
		cached.signature === signature &&
		cached.hideThinkingBlock === (component.hideThinkingBlock ?? false) &&
		cached.markdownTheme === component.markdownTheme &&
		cached.hiddenThinkingLabel === component.hiddenThinkingLabel &&
		cached.outputPad === (component.outputPad ?? 1) &&
		cached.markdownTransformers === component.markdownTransformers &&
		cached.isStreaming === (component.isStreaming ?? false)
	) {
		return cached.pieces;
	}

	const pieces = buildAssistantPieces(component, message);
	pieceCache.set(component, {
		message,
		signature,
		hideThinkingBlock: component.hideThinkingBlock ?? false,
		markdownTheme: component.markdownTheme,
		hiddenThinkingLabel: component.hiddenThinkingLabel,
		outputPad: component.outputPad ?? 1,
		markdownTransformers: component.markdownTransformers,
		isStreaming: component.isStreaming ?? false,
		pieces,
	});
	return pieces;
}

function buildAssistantPieces(component: AnyComponent, message: any): AnyComponent[] {
	const pieces: AnyComponent[] = [];
	for (const content of message.content ?? []) {
		if (content?.type === "thinking" && String(content.thinking ?? "").trim()) {
			const piece = cloneAssistantPiece(component, [content], "thinking");
			if (piece) pieces.push(piece);
		} else if (content?.type === "text" && String(content.text ?? "").trim()) {
			const piece = cloneAssistantPiece(component, [content], "text");
			if (piece) pieces.push(piece);
		} else if (content?.type === "toolCall") {
			// Invisible marker preserving turn ordering for grouping without re-rendering assistant prose.
			const piece = cloneAssistantPiece(component, [content], "toolCall");
			if (piece) pieces.push(piece);
		}
	}

	if (assistantHasVisibleStatus(component)) {
		const piece = cloneAssistantPiece(component, [], "status");
		if (piece) pieces.push(piece);
	}

	return pieces.length > 0 ? pieces : [component];
}

function renderComponents(components: AnyComponent[], width: number): string[] {
	const lines: string[] = [];
	for (const component of components) {
		lines.push(...component.render(width));
	}
	return lines;
}

function foldSummary(items: AnyComponent[], theme: any): AnyComponent | undefined {
	let toolCount = 0;
	let failedCount = 0;
	let hasThinking = false;
	const toolNames = new Map<string, number>();
	for (const item of items) {
		if (isToolExecution(item)) {
			toolCount++;
			if (item.result?.isError) failedCount++;
			if (typeof item.toolName === "string") toolNames.set(item.toolName, (toolNames.get(item.toolName) ?? 0) + 1);
		} else if (assistantHasThinking(item)) {
			hasThinking = true;
		}
	}
	// Tool-call markers alone have nothing to summarize.
	if (toolCount === 0 && !hasThinking) return undefined;
	return new HeaderComponent(toolCount, failedCount, toolNames, theme) as AnyComponent;
}

function renderFolded(children: AnyComponent[], width: number, theme: any, foldTail: boolean): string[] {
	const normalized = children.flatMap(splitAssistant);
	const output: AnyComponent[] = [];
	let pending: AnyComponent[] = [];
	let neutralAfterPending: AnyComponent[] = [];

	function pushFold(items: AnyComponent[]) {
		const header = foldSummary(items, theme);
		if (header) output.push(header);
	}

	for (const child of normalized) {
		if (isFoldable(child)) {
			pending.push(...neutralAfterPending, child);
			neutralAfterPending = [];
			continue;
		}

		if (isUserBoundary(child) || isAssistantBoundary(child)) {
			pushFold(pending);
			pending = [];
			output.push(...neutralAfterPending, child);
			neutralAfterPending = [];
			continue;
		}

		if (pending.length > 0) {
			neutralAfterPending.push(child);
		} else {
			output.push(child);
		}
	}

	// No following user/assistant prose yet: this is live/current intermediate work.
	if (foldTail) {
		pushFold(pending);
		output.push(...neutralAfterPending);
	} else {
		output.push(...pending, ...neutralAfterPending);
	}

	return renderComponents(output, width);
}

// Pi nests the transcript: tui → documentContainer → chatContainer. Walk plain
// Containers only; message components subclass Container but never host the transcript.
function collectContainers(roots: AnyComponent[], out: ContainerLike[] = []): ContainerLike[] {
	for (const root of roots) {
		if (root?.constructor?.name !== "Container" || !Array.isArray(root.children)) continue;
		out.push(root as ContainerLike);
		collectContainers(root.children, out);
	}
	return out;
}

function setExpandedDeep(component: AnyComponent, expanded: boolean): void {
	if (typeof component?.setExpanded === "function") component.setExpanded(expanded);
	if (Array.isArray(component?.children)) {
		for (const child of component.children) setExpandedDeep(child, expanded);
	}
}

export default function (pi: ExtensionAPI) {
	const runtimeId = `${Date.now()}:${Math.random()}`;
	(globalThis as any)[RUNTIME_KEY] = runtimeId;

	let tui: (TUI & { children?: AnyComponent[]; requestRender?: () => void }) | undefined;
	let theme: any;
	let mode: FilterMode = "fold";
	let runtimeActive = false;
	let renderQueued = false;

	function isCurrentRuntime(): boolean {
		return runtimeActive && (globalThis as any)[RUNTIME_KEY] === runtimeId;
	}

	function requestRender() {
		if (!isCurrentRuntime() || renderQueued) return;
		renderQueued = true;
		queueMicrotask(() => {
			renderQueued = false;
			if (!isCurrentRuntime()) return;
			tui?.requestRender?.();
		});
	}

	function patchContainer(container: ContainerLike) {
		const target = container as PatchedContainer;

		// /reload keeps the same TUI/container instances. If a previous runtime
		// already patched this container, reuse its saved original render and
		// replace the stale wrapper with this runtime's wrapper.
		const original = target.__piFilterOriginalRender ?? target.render.bind(target);
		target.__piFilterOriginalRender = original;
		target.render = (width: number) => {
			if (!isCurrentRuntime() || mode === "off" || !isChatLike(target)) {
				return original(width);
			}
			return renderFolded(target.children, width, theme, mode === "fold all");
		};
	}

	function patchTopLevelContainers() {
		for (const container of collectContainers(tui?.children ?? [])) patchContainer(container);
	}

	function applyToolExpansion(expanded: boolean) {
		for (const container of collectContainers(tui?.children ?? [])) {
			if (isChatLike(container)) {
				for (const child of container.children) setExpandedDeep(child, expanded);
			}
		}
	}

	// Registered once per extension load so repeated session_starts
	// don't stack duplicate handlers.
	let unsubscribeInput: (() => void) | undefined;

	pi.on("message_end", async (event, eventCtx) => {
		if (!isCurrentRuntime() || eventCtx.mode !== "tui") return;
		const message = (event as any).message;
		if (message?.role !== "assistant") return;
		if (!message.content?.some((c: any) => c?.type === "text" && String(c.text ?? "").trim())) return;
		patchTopLevelContainers();
		requestRender();
	});

	pi.on("agent_end", async (_event, eventCtx) => {
		if (!isCurrentRuntime() || eventCtx.mode !== "tui") return;
		patchTopLevelContainers();
		requestRender();
	});

	pi.on("session_shutdown", async () => {
		if ((globalThis as any)[RUNTIME_KEY] === runtimeId) {
			(globalThis as any)[RUNTIME_KEY] = undefined;
		}
		runtimeActive = false;
		unsubscribeInput?.();
		unsubscribeInput = undefined;
	});

	pi.on("session_start", async (_event, ctx) => {
		if (ctx.mode !== "tui") return;

		runtimeActive = true;
		mode = "fold";

		ctx.ui.setWidget(PROBE_WIDGET_KEY, (capturedTui, capturedTheme) => {
			tui = capturedTui as typeof tui;
			theme = capturedTheme;
			patchTopLevelContainers();
			return new EmptyComponent();
		});
		ctx.ui.setWidget(PROBE_WIDGET_KEY, undefined);

		// session_start happens before restored messages on cold start. The chat
		// container is already present, but render after a few ticks so the first
		// restored transcript draw uses the folded renderer. Keep this sparse so it
		// does not fight typing during startup.
		for (const delay of [0, 75, 250, 750]) {
			setTimeout(() => {
				if (!isCurrentRuntime()) return;
				patchTopLevelContainers();
				requestRender();
			}, delay);
		}

		// A second session_start replaces the listener instead of stacking a duplicate.
		unsubscribeInput?.();
		unsubscribeInput = ctx.ui.onTerminalInput((data) => {
			if (!isCurrentRuntime()) return undefined;

			if (matchesKey(data, "ctrl+f")) {
				// The kitty keyboard protocol reports repeat/release as separate events
				// and input listeners see them before the TUI's release filter. Acting on
				// both press and release toggled twice per keystroke (a net no-op).
				// Swallow non-press events for the key we own, but only toggle on press.
				if (isKeyRelease(data) || isKeyRepeat(data)) return { consume: true };
				mode = FILTER_MODES[(FILTER_MODES.indexOf(mode) + 1) % FILTER_MODES.length]!;
				if (mode === "off") applyToolExpansion(ctx.ui.getToolsExpanded());
				ctx.ui.notify(`Filter: ${mode}`, "info");
				requestRender();
				return { consume: true };
			}
			if (matchesKey(data, "ctrl+o")) {
				setTimeout(() => {
					if (!isCurrentRuntime()) return;
					applyToolExpansion(ctx.ui.getToolsExpanded());
					requestRender();
				}, 0);
			} else if (matchesKey(data, "ctrl+t")) {
				setTimeout(requestRender, 0);
			}
			return undefined;
		});
	});
}
