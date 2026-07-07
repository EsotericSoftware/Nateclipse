import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";

type UsageWindowSnapshot = {
	leftPercent: number | null;
	resetInSeconds: number | null;
};

type UsageSnapshot = {
	fiveHour: UsageWindowSnapshot;
	weekly: UsageWindowSnapshot;
};

type AuthEntry = {
	type?: string;
	access?: string | null;
	accountId?: string | null;
	account_id?: string | null;
};

const EXTENSION_ID = "usage";

const agentDirFromEnv = process.env.PI_CODING_AGENT_DIR?.trim();
const AGENT_DIR = agentDirFromEnv ? agentDirFromEnv : path.join(os.homedir(), ".pi", "agent");
const AUTH_FILE = path.join(AGENT_DIR, "auth.json");

const REFRESH_INTERVAL_MS = 60_000;

function clampPercent(value: number): number {
	return Math.min(100, Math.max(0, value));
}

// Both Codex (`used_percent`) and Claude (`utilization`) report a 0-100 amount
// already consumed; we display the amount left.
function usedToLeftPercent(value: number | null | undefined): number | null {
	if (typeof value !== "number" || Number.isNaN(value)) return null;
	return clampPercent(100 - value);
}

function formatPercentValue(valueLeft: number | null): string {
	if (typeof valueLeft !== "number" || Number.isNaN(valueLeft)) {
		return "--";
	}

	return `${Math.round(clampPercent(valueLeft))}%`;
}

function formatResetDuration(seconds: number | null): string {
	if (typeof seconds !== "number" || Number.isNaN(seconds)) return "--";

	const roundedSeconds = Math.max(0, Math.ceil(seconds));
	if (roundedSeconds === 0) return "0m";
	if (roundedSeconds >= 86_400) return `${Math.ceil(roundedSeconds / 86_400)}d`;
	if (roundedSeconds >= 3_600) return `${Math.ceil(roundedSeconds / 3_600)}h`;
	return `${Math.ceil(roundedSeconds / 60)}m`;
}

function formatUsageWindow(window: UsageWindowSnapshot): string {
	return `${formatPercentValue(window.leftPercent)} ${formatResetDuration(window.resetInSeconds)}`;
}

function formatUsagePair(usage: UsageSnapshot): string {
	// Weekly first, then 5-hour, matching the reset durations users care about most.
	return `${formatUsageWindow(usage.weekly)} ${formatUsageWindow(usage.fiveHour)}`;
}

function formatStatus(ctx: ExtensionContext, label: string, usage: UsageSnapshot): string {
	return ctx.ui.theme.fg("dim", `${label} ${formatUsagePair(usage)}`);
}

async function readAuthFile(): Promise<Record<string, AuthEntry | undefined>> {
	const authRaw = await fs.readFile(AUTH_FILE, "utf8");
	return JSON.parse(authRaw) as Record<string, AuthEntry | undefined>;
}

function isMissingAuthError(error: unknown, prefix: string): boolean {
	if (!(error instanceof Error)) return false;
	if (error.message.includes(prefix)) return true;

	const errorWithCode = error as Error & { code?: string };
	return errorWithCode.code === "ENOENT" && error.message.includes(AUTH_FILE);
}

// === Shared usage cache (cross-session) =================================

// Snapshots are cached on disk so concurrent pi sessions share one fetch
// budget instead of each polling the provider (Claude's usage endpoint is
// rate limited to roughly one request per ~5 minutes per account).
const CACHE_FILE = path.join(AGENT_DIR, "usage-cache.json");
const PENDING_FETCH_TIMEOUT_MS = 30_000;
const RATE_LIMIT_COOLDOWN_MS = 5 * 60_000;
const FAILURE_COOLDOWN_MS = 60_000;

type UsageCacheEntry = {
	data?: unknown;
	fetchedAt?: number;
	cooldownUntil?: number;
	pendingAt?: number;
};

type UsageCache = Record<string, UsageCacheEntry | undefined>;

async function readUsageCache(): Promise<UsageCache> {
	try {
		const parsed = JSON.parse(await fs.readFile(CACHE_FILE, "utf8")) as unknown;
		if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as UsageCache;
	} catch {
		// Missing or corrupt cache is treated as empty.
	}
	return {};
}

async function updateUsageCacheEntry(key: string, update: UsageCacheEntry): Promise<UsageCacheEntry> {
	const cache = await readUsageCache();
	const entry = { ...cache[key], ...update };
	cache[key] = entry;
	try {
		await fs.writeFile(CACHE_FILE, JSON.stringify(cache));
	} catch {
		// Cache persistence is best-effort; other sessions just refetch.
	}
	return entry;
}

class UsageRequestError extends Error {
	constructor(
		message: string,
		readonly status: number,
		readonly retryAfterSeconds: number | null,
	) {
		super(message);
	}
}

function throwUsageRequestError(label: string, response: Response, url: string): never {
	const retryAfter = Number.parseInt(response.headers.get("retry-after") ?? "", 10);
	throw new UsageRequestError(
		`${label} usage request failed (${response.status}) for ${url}`,
		response.status,
		Number.isFinite(retryAfter) ? retryAfter : null,
	);
}

function getFailureCooldownMs(error: unknown): number {
	if (error instanceof UsageRequestError && error.status === 429) {
		return error.retryAfterSeconds !== null ? error.retryAfterSeconds * 1000 : RATE_LIMIT_COOLDOWN_MS;
	}
	return FAILURE_COOLDOWN_MS;
}

// === Codex (ChatGPT / OpenAI) ===========================================

type UsageWindow = {
	used_percent?: number | null;
	reset_after_seconds?: number | null;
	reset_at?: number | null;
};

type RateLimitBucket = {
	allowed?: boolean;
	limit_reached?: boolean;
	primary_window?: UsageWindow | null;
	secondary_window?: UsageWindow | null;
};

type CodexUsageResponse = {
	rate_limit?: RateLimitBucket | null;
	additional_rate_limits?: Record<string, unknown> | unknown[] | null;
};

const CODEX_PROVIDER = "openai-codex";
const CODEX_API = "openai-codex-responses";
const CODEX_LABEL = "codex";
const CODEX_SPARK_LABEL = "codex spark";
const SPARK_MODEL_ID = "gpt-5.3-codex-spark";
const SPARK_LIMIT_NAME = "GPT-5.3-Codex-Spark";
const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
const CODEX_USAGE_TTL_MS = 60_000;
const CODEX_MISSING_AUTH_PREFIX = "Missing openai-codex OAuth access/accountId";

function isCodexModel(model: ExtensionContext["model"]): boolean {
	if (!model) return false;
	return model.provider === CODEX_PROVIDER || model.api === CODEX_API;
}

function isSparkModel(modelId: string | undefined): boolean {
	return modelId === SPARK_MODEL_ID;
}

function getCodexLabel(modelId: string | undefined): string {
	return isSparkModel(modelId) ? CODEX_SPARK_LABEL : CODEX_LABEL;
}

async function loadCodexCredentials(): Promise<{ accessToken: string; accountId: string }> {
	const auth = await readAuthFile();
	const codexEntry = auth[CODEX_PROVIDER];
	const authEntry = codexEntry?.type === "oauth" ? codexEntry : undefined;

	const accessToken = authEntry?.access?.trim();
	const accountId = (authEntry?.accountId ?? authEntry?.account_id)?.trim();

	if (!accessToken || !accountId) {
		throw new Error(`${CODEX_MISSING_AUTH_PREFIX} in ${AUTH_FILE}`);
	}

	return { accessToken, accountId };
}

async function requestCodexUsageJson(): Promise<CodexUsageResponse> {
	const credentials = await loadCodexCredentials();
	const response = await fetch(CODEX_USAGE_URL, {
		headers: {
			accept: "*/*",
			authorization: `Bearer ${credentials.accessToken}`,
			"chatgpt-account-id": credentials.accountId,
		},
	});

	if (!response.ok) throwUsageRequestError("Codex", response, CODEX_USAGE_URL);
	return (await response.json()) as CodexUsageResponse;
}

function asObject(value: unknown): Record<string, unknown> | null {
	if (!value || typeof value !== "object" || Array.isArray(value)) return null;
	return value as Record<string, unknown>;
}

function normalizeRateLimitBucket(value: unknown): RateLimitBucket | null {
	const record = asObject(value);
	if (!record) return null;
	if (!("primary_window" in record || "limit_reached" in record || "allowed" in record)) {
		return null;
	}
	return record as RateLimitBucket;
}

function extractSparkRateLimitFromEntry(value: unknown): RateLimitBucket | null {
	const record = asObject(value);
	if (!record) return null;
	if (typeof record.limit_name !== "string" || record.limit_name.trim() !== SPARK_LIMIT_NAME) return null;
	return normalizeRateLimitBucket(record.rate_limit);
}

function findSparkRateLimitBucket(data: CodexUsageResponse): RateLimitBucket | null {
	const additional = data.additional_rate_limits;
	if (Array.isArray(additional)) {
		for (const entry of additional) {
			const bucket = extractSparkRateLimitFromEntry(entry);
			if (bucket) return bucket;
		}
	} else {
		const additionalMap = asObject(additional);
		if (additionalMap) {
			for (const value of Object.values(additionalMap)) {
				const bucket = extractSparkRateLimitFromEntry(value);
				if (bucket) return bucket;
			}
		}
	}

	return null;
}

function selectCodexRateLimitBucket(data: CodexUsageResponse, modelId: string | undefined): RateLimitBucket | null {
	if (isSparkModel(modelId)) {
		return findSparkRateLimitBucket(data);
	}
	return normalizeRateLimitBucket(data.rate_limit);
}

function getCodexResetInSeconds(window: UsageWindow | null | undefined, fetchedAtMs: number): number | null {
	if (typeof window?.reset_after_seconds === "number" && !Number.isNaN(window.reset_after_seconds)) {
		// reset_after_seconds is relative to when the response was fetched.
		return window.reset_after_seconds - (Date.now() - fetchedAtMs) / 1000;
	}
	if (typeof window?.reset_at === "number" && !Number.isNaN(window.reset_at)) {
		return window.reset_at - Date.now() / 1000;
	}
	return null;
}

function parseCodexWindow(window: UsageWindow | null | undefined, fetchedAtMs: number): UsageWindowSnapshot {
	return {
		leftPercent: usedToLeftPercent(window?.used_percent),
		resetInSeconds: getCodexResetInSeconds(window, fetchedAtMs),
	};
}

function parseCodexSnapshot(data: CodexUsageResponse, modelId: string | undefined, fetchedAtMs: number): UsageSnapshot {
	const selectedBucket = selectCodexRateLimitBucket(data, modelId);
	return {
		fiveHour: parseCodexWindow(selectedBucket?.primary_window, fetchedAtMs),
		weekly: parseCodexWindow(selectedBucket?.secondary_window, fetchedAtMs),
	};
}

// === Claude (Anthropic OAuth, Pro/Max) ==================================

type ClaudeUsageWindow = {
	utilization?: number | null;
	resets_at?: string | null;
};

type ClaudeUsageResponse = {
	five_hour?: ClaudeUsageWindow | null;
	seven_day?: ClaudeUsageWindow | null;
	seven_day_opus?: ClaudeUsageWindow | null;
};

const ANTHROPIC_PROVIDER = "anthropic";
const CLAUDE_LABEL = "claude";
const CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
// The Claude usage endpoint is rate limited to roughly one request per
// ~5 minutes per account, so cache snapshots for that long.
const CLAUDE_USAGE_TTL_MS = 5 * 60_000;
const ANTHROPIC_MISSING_AUTH_PREFIX = "Missing anthropic OAuth access";

function isClaudeModel(model: ExtensionContext["model"]): boolean {
	if (!model) return false;
	return model.provider === ANTHROPIC_PROVIDER;
}

// Top-tier Claude models (Opus, and the Opus-class "Fable" flagship) count
// against the dedicated Opus weekly bucket rather than the overall one.
function usesOpusWeeklyBucket(modelId: string | undefined): boolean {
	if (typeof modelId !== "string") return false;
	const id = modelId.toLowerCase();
	return id.includes("opus") || id.includes("fable");
}

async function loadAnthropicToken(): Promise<string> {
	const auth = await readAuthFile();
	const anthropicEntry = auth[ANTHROPIC_PROVIDER];
	const authEntry = anthropicEntry?.type === "oauth" ? anthropicEntry : undefined;

	const accessToken = authEntry?.access?.trim();
	if (!accessToken) {
		throw new Error(`${ANTHROPIC_MISSING_AUTH_PREFIX} in ${AUTH_FILE}`);
	}

	return accessToken;
}

async function requestClaudeUsageJson(): Promise<ClaudeUsageResponse> {
	const accessToken = await loadAnthropicToken();
	const response = await fetch(CLAUDE_USAGE_URL, {
		headers: {
			accept: "application/json",
			authorization: `Bearer ${accessToken}`,
			"anthropic-beta": "oauth-2025-04-20",
			"anthropic-version": "2023-06-01",
		},
	});

	if (!response.ok) throwUsageRequestError("Claude", response, CLAUDE_USAGE_URL);
	return (await response.json()) as ClaudeUsageResponse;
}

function getClaudeResetInSeconds(window: ClaudeUsageWindow | null | undefined): number | null {
	if (!window?.resets_at) return null;
	const resetAt = Date.parse(window.resets_at);
	if (Number.isNaN(resetAt)) return null;
	return (resetAt - Date.now()) / 1000;
}

function parseClaudeWindow(window: ClaudeUsageWindow | null | undefined): UsageWindowSnapshot {
	return {
		leftPercent: usedToLeftPercent(window?.utilization),
		resetInSeconds: getClaudeResetInSeconds(window),
	};
}

function parseClaudeSnapshot(data: ClaudeUsageResponse, modelId: string | undefined): UsageSnapshot {
	// Opus has its own weekly bucket; fall back to the overall weekly window
	// when it is absent (or not yet populated).
	const opusWeekly = data.seven_day_opus;
	const weeklyWindow = usesOpusWeeklyBucket(modelId) && opusWeekly ? opusWeekly : data.seven_day;

	return {
		fiveHour: parseClaudeWindow(data.five_hour),
		weekly: parseClaudeWindow(weeklyWindow),
	};
}

// === Provider dispatch ==================================================

type UsageSource = {
	label: string;
	cacheKey: string;
	ttlMs: number;
	ensureAuth: () => Promise<void>;
	fetchRaw: () => Promise<unknown>;
	parseSnapshot: (data: unknown, fetchedAtMs: number) => UsageSnapshot;
	isMissingAuthError: (error: unknown) => boolean;
};

function resolveUsageSource(model: ExtensionContext["model"]): UsageSource | null {
	const modelId = model?.id;

	if (isCodexModel(model)) {
		return {
			label: getCodexLabel(modelId),
			cacheKey: CODEX_PROVIDER,
			ttlMs: CODEX_USAGE_TTL_MS,
			ensureAuth: async () => {
				await loadCodexCredentials();
			},
			fetchRaw: requestCodexUsageJson,
			parseSnapshot: (data, fetchedAtMs) => parseCodexSnapshot(data as CodexUsageResponse, modelId, fetchedAtMs),
			isMissingAuthError: (error) => isMissingAuthError(error, CODEX_MISSING_AUTH_PREFIX),
		};
	}

	if (isClaudeModel(model)) {
		return {
			label: CLAUDE_LABEL,
			cacheKey: ANTHROPIC_PROVIDER,
			ttlMs: CLAUDE_USAGE_TTL_MS,
			ensureAuth: async () => {
				await loadAnthropicToken();
			},
			fetchRaw: requestClaudeUsageJson,
			parseSnapshot: (data) => parseClaudeSnapshot(data as ClaudeUsageResponse, modelId),
			isMissingAuthError: (error) => isMissingAuthError(error, ANTHROPIC_MISSING_AUTH_PREFIX),
		};
	}

	return null;
}

function createStatusRefresher() {
	let refreshTimer: ReturnType<typeof setInterval> | undefined;
	let activeContext: ExtensionContext | undefined;
	let activeModel: ExtensionContext["model"];
	let isRefreshInFlight = false;
	let queuedRefresh: { ctx: ExtensionContext; model: ExtensionContext["model"] } | null = null;

	async function updateFooterStatus(ctx: ExtensionContext, model = ctx.model): Promise<void> {
		if (!ctx.hasUI) return;

		// Only display the usage status for models we know how to report on.
		const source = resolveUsageSource(model);
		if (!source) {
			ctx.ui.setStatus(EXTENSION_ID, undefined);
			return;
		}

		if (isRefreshInFlight) {
			queuedRefresh = { ctx, model };
			return;
		}
		isRefreshInFlight = true;
		try {
			const now = Date.now();
			let entry = (await readUsageCache())[source.cacheKey];

			const hasFreshData =
				entry?.data !== undefined && typeof entry.fetchedAt === "number" && now - entry.fetchedAt < source.ttlMs;
			const inCooldown = typeof entry?.cooldownUntil === "number" && entry.cooldownUntil > now;
			const fetchPendingElsewhere =
				typeof entry?.pendingAt === "number" && now - entry.pendingAt < PENDING_FETCH_TIMEOUT_MS;

			if (!hasFreshData && !inCooldown && !fetchPendingElsewhere) {
				await updateUsageCacheEntry(source.cacheKey, { pendingAt: now });
				try {
					const data = await source.fetchRaw();
					entry = await updateUsageCacheEntry(source.cacheKey, {
						data,
						fetchedAt: Date.now(),
						cooldownUntil: undefined,
						pendingAt: undefined,
					});
				} catch (error) {
					if (source.isMissingAuthError(error)) {
						await updateUsageCacheEntry(source.cacheKey, { pendingAt: undefined });
						ctx.ui.setStatus(EXTENSION_ID, undefined);
						return;
					}

					// Back off (respecting Retry-After on 429) and keep showing stale data.
					entry = await updateUsageCacheEntry(source.cacheKey, {
						cooldownUntil: Date.now() + getFailureCooldownMs(error),
						pendingAt: undefined,
					});
				}
			}

			if (entry?.data !== undefined && typeof entry.fetchedAt === "number") {
				const usage = source.parseSnapshot(entry.data, entry.fetchedAt);
				ctx.ui.setStatus(EXTENSION_ID, formatStatus(ctx, source.label, usage));
			} else {
				ctx.ui.setStatus(EXTENSION_ID, ctx.ui.theme.fg("warning", `${source.label} unavailable`));
			}
		} finally {
			isRefreshInFlight = false;
			if (queuedRefresh) {
				const nextRefresh = queuedRefresh;
				queuedRefresh = null;
				void updateFooterStatus(nextRefresh.ctx, nextRefresh.model);
			}
		}
	}

	function refreshFor(ctx: ExtensionContext, model = ctx.model): Promise<void> {
		activeContext = ctx;
		activeModel = model;
		return updateFooterStatus(ctx, model);
	}

	function startAutoRefresh(): void {
		if (refreshTimer) clearInterval(refreshTimer);
		refreshTimer = setInterval(() => {
			if (!activeContext) return;
			void updateFooterStatus(activeContext, activeModel);
		}, REFRESH_INTERVAL_MS);
		refreshTimer.unref?.();
	}

	function stopAutoRefresh(ctx?: ExtensionContext): void {
		if (refreshTimer) {
			clearInterval(refreshTimer);
			refreshTimer = undefined;
		}
		ctx?.ui.setStatus(EXTENSION_ID, undefined);
	}

	async function setLoadingStatus(ctx: ExtensionContext): Promise<void> {
		if (!ctx.hasUI) return;

		// Only display the usage status for models we know how to report on.
		const source = resolveUsageSource(ctx.model);
		if (!source) {
			ctx.ui.setStatus(EXTENSION_ID, undefined);
			return;
		}

		try {
			await source.ensureAuth();
		} catch (error) {
			if (source.isMissingAuthError(error)) {
				ctx.ui.setStatus(EXTENSION_ID, undefined);
				return;
			}
		}

		const loadingStatus = `${source.label} loading...`;
		ctx.ui.setStatus(EXTENSION_ID, ctx.ui.theme.fg("dim", loadingStatus));
	}

	return {
		refreshFor,
		startAutoRefresh,
		stopAutoRefresh,
		setLoadingStatus,
	};
}

export default function (pi: ExtensionAPI) {
	const refresher = createStatusRefresher();

	pi.on("session_start", (_event, ctx) => {
		refresher.startAutoRefresh();
		void (async () => {
			await refresher.setLoadingStatus(ctx);
			await refresher.refreshFor(ctx);
		})();
	});

	pi.on("turn_end", (_event, ctx) => {
		void refresher.refreshFor(ctx);
	});

	pi.on("session_switch", (_event, ctx) => {
		void refresher.refreshFor(ctx);
	});

	pi.on("model_select", (event, ctx) => {
		void refresher.refreshFor(ctx, event.model);
	});

	pi.on("session_shutdown", (_event, ctx) => {
		refresher.stopAutoRefresh(ctx);
	});
}
