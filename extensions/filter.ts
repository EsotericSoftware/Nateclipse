import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { matchesKey, type Component, type TUI } from "@earendil-works/pi-tui";

const PROBE_WIDGET_KEY = "auto-turn-filter-probe";

type AnyComponent = Component & Record<string, any>;
type ContainerLike = AnyComponent & { children: AnyComponent[] };

type PatchedContainer = ContainerLike & {
	__piFilterOriginalRender?: (width: number) => string[];
};

class EmptyComponent implements Component {
	render(): string[] {
		return [];
	}
}

class HeaderComponent implements Component {
	constructor(
		private readonly toolCallCount: number,
		private readonly theme: any,
	) {}

	render(): string[] {
		const label =
			this.toolCallCount > 0
				? `${this.toolCallCount} tool call${this.toolCallCount === 1 ? "" : "s"}`
				: "thoughts";
		const text = `▸ ${label}`;
		return ["", this.theme?.fg ? this.theme.fg("muted", text) : text];
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
	return text.replace(/\x1b\[[0-9;]*m/g, "");
}

function isErrorTextBoundary(component: AnyComponent): boolean {
	if (component?.constructor?.name !== "Text") return false;
	const text = typeof component.text === "string" ? stripAnsi(component.text).trim() : "";
	return /^(Error|Warning):\s/.test(text);
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
		return new Ctor(
			{
				...message,
				content,
				stopReason: kind === "thinking" || kind === "toolCall" ? undefined : message.stopReason,
				errorMessage: kind === "thinking" || kind === "toolCall" ? undefined : message.errorMessage,
			},
			original.hideThinkingBlock ?? false,
			original.markdownTheme,
			original.hiddenThinkingLabel,
		);
	} catch {
		return undefined;
	}
}

function splitAssistant(component: AnyComponent): AnyComponent[] {
	const message = assistantMessage(component);
	if (!message) return [component];

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

function renderFolded(children: AnyComponent[], width: number, theme: any): string[] {
	const normalized = children.flatMap(splitAssistant);
	const output: AnyComponent[] = [];
	let pending: AnyComponent[] = [];
	let neutralAfterPending: AnyComponent[] = [];

	function flushFold() {
		if (pending.length === 0) return;
		output.push(new HeaderComponent(pending.filter(isToolExecution).length, theme) as AnyComponent);
		pending = [];
	}

	function flushRaw() {
		if (pending.length === 0 && neutralAfterPending.length === 0) return;
		output.push(...pending, ...neutralAfterPending);
		pending = [];
		neutralAfterPending = [];
	}

	for (const child of normalized) {
		if (isFoldable(child)) {
			pending.push(...neutralAfterPending, child);
			neutralAfterPending = [];
			continue;
		}

		if (isUserBoundary(child) || isAssistantBoundary(child)) {
			flushFold();
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
	flushRaw();

	return renderComponents(output, width);
}

function setExpandedDeep(component: AnyComponent, expanded: boolean): void {
	if (typeof component?.setExpanded === "function") component.setExpanded(expanded);
	if (Array.isArray(component?.children)) {
		for (const child of component.children) setExpandedDeep(child, expanded);
	}
}

export default function (pi: ExtensionAPI) {
	let tui: (TUI & { children?: AnyComponent[]; requestRender?: () => void }) | undefined;
	let theme: any;
	let foldingEnabled = true;
	let runtimeActive = false;

	function requestRender() {
		tui?.requestRender?.();
	}

	function patchContainer(container: AnyComponent | undefined) {
		if (!container || !Array.isArray(container.children)) return;
		const target = container as PatchedContainer;

		// /reload keeps the same TUI/container instances. If a previous runtime
		// already patched this container, reuse its saved original render and
		// replace the stale wrapper with this runtime's wrapper.
		const original = target.__piFilterOriginalRender ?? target.render.bind(target);
		target.__piFilterOriginalRender = original;
		target.render = (width: number) => {
			if (!runtimeActive || !foldingEnabled || !isChatLike(target)) {
				return original(width);
			}
			return renderFolded(target.children, width, theme);
		};
	}

	function patchTopLevelContainers() {
		for (const child of tui?.children ?? []) patchContainer(child);
	}

	function applyToolExpansion(expanded: boolean) {
		for (const top of tui?.children ?? []) {
			if (Array.isArray(top.children) && isChatLike(top as ContainerLike)) {
				for (const child of top.children) setExpandedDeep(child, expanded);
			}
		}
	}

	pi.on("session_start", async (_event, ctx) => {
		if (ctx.mode !== "tui") return;

		runtimeActive = true;
		foldingEnabled = true;

		ctx.ui.setWidget(PROBE_WIDGET_KEY, (capturedTui, capturedTheme) => {
			tui = capturedTui as typeof tui;
			theme = capturedTheme;
			patchTopLevelContainers();
			return new EmptyComponent();
		});
		ctx.ui.setWidget(PROBE_WIDGET_KEY, undefined);

		// session_start happens before restored messages on cold start. The chat
		// container is already present, but render after a few ticks so the first
		// restored transcript draw uses the folded renderer.
		for (const delay of [0, 25, 100, 250, 500, 1000]) {
			setTimeout(() => {
				if (!runtimeActive) return;
				patchTopLevelContainers();
				requestRender();
			}, delay);
		}

		const unsubscribe = ctx.ui.onTerminalInput((data) => {
			if (matchesKey(data, "ctrl+f")) {
				foldingEnabled = !foldingEnabled;
				if (!foldingEnabled) applyToolExpansion(ctx.ui.getToolsExpanded());
				ctx.ui.notify(`Turn folding ${foldingEnabled ? "enabled" : "disabled"}`, "info");
				requestRender();
				return { consume: true };
			}
			if (matchesKey(data, "ctrl+o")) {
				setTimeout(() => applyToolExpansion(ctx.ui.getToolsExpanded()), 0);
			} else if (matchesKey(data, "ctrl+t")) {
				setTimeout(requestRender, 0);
			}
			return undefined;
		});

		pi.on("message_end", async (event, eventCtx) => {
			if (!runtimeActive || eventCtx.mode !== "tui") return;
			const message = (event as any).message;
			if (message?.role !== "assistant") return;
			if (!message.content?.some((c: any) => c?.type === "text" && String(c.text ?? "").trim())) return;
			patchTopLevelContainers();
			requestRender();
		});

		pi.on("agent_end", async (_event, eventCtx) => {
			if (!runtimeActive || eventCtx.mode !== "tui") return;
			patchTopLevelContainers();
			requestRender();
		});

		pi.on("session_shutdown", async () => {
			runtimeActive = false;
			unsubscribe();
		});
	});
}
