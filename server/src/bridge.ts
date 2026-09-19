/**
 * Client for the RuneLite MCP Bridge plugin (see ../plugin). The plugin serves
 * read-only game state JSON on 127.0.0.1. If your agent runs inside WSL with
 * NAT networking, set RUNELITE_BRIDGE_HOST to the Windows host address (with
 * mirrored networking, the default 127.0.0.1 works as-is).
 */
export const DEFAULT_BRIDGE_PORT = 8765;

export function bridgeHost(): string {
	return process.env.RUNELITE_BRIDGE_HOST ?? "127.0.0.1";
}

export function bridgePort(): number {
	return Number(process.env.RUNELITE_BRIDGE_PORT ?? DEFAULT_BRIDGE_PORT);
}

export function bridgeUrl(path: string): string {
	return `http://${bridgeHost()}:${bridgePort()}${path}`;
}

export async function bridgeGet<T>(path: string, timeoutMs = 15000): Promise<T> {
	const res = await fetch(bridgeUrl(path), { signal: AbortSignal.timeout(timeoutMs) });
	if (!res.ok) {
		throw new Error(`bridge HTTP ${res.status} for ${path}`);
	}
	return (await res.json()) as T;
}

export async function tryBridgeGet<T>(path: string): Promise<{ ok: true; data: T } | { ok: false; error: string }> {
	try {
		return { ok: true, data: await bridgeGet<T>(path) };
	} catch (e) {
		return {
			ok: false,
			error: `RuneLite bridge unreachable at ${bridgeUrl(path)} — is the game running with the MCP Bridge plugin enabled? (${e instanceof Error ? e.message : String(e)})`,
		};
	}
}

// Loose shapes of the plugin's responses (kept permissive on purpose)
export interface SkillState {
	level: number;
	boosted: number;
	xp: number;
}

export interface StatePayload {
	loggedIn: boolean;
	username?: string;
	combatLevel?: number;
	kudos?: number;
	skills: Record<string, SkillState>;
	overall: { totalLevel: number; xp: number };
	[key: string]: unknown;
}

export interface BankItem {
	id: number;
	qty: number;
	name?: string;
}

export interface BankPayload {
	available: boolean;
	capturedAt?: string;
	ageSeconds?: number;
	items?: BankItem[];
	note?: string;
}

export interface QuestsPayload {
	quests: Record<string, "FINISHED" | "IN_PROGRESS" | "NOT_STARTED">;
	summary: { finished: number; inProgress: number; notStarted: number };
}

export interface SnapshotPayload {
	state: StatePayload;
	quests: QuestsPayload;
	bank: BankPayload;
	[key: string]: unknown;
}

export interface HealthPayload {
	status: string;
	loggedIn: boolean;
	username?: string;
	gameState: string;
}
