import { fetchJson } from "./http.js";

/**
 * Wise Old Man v2 API (https://docs.wiseoldman.net/). Player reads are public;
 * be gentle: poll a given player at most once per hour.
 */
const BASE = "https://api.wiseoldman.net/v2";

export interface WomMetricDelta {
	gained: number;
	start: number;
	end: number;
}

export interface WomGained {
	startsAt: string;
	endsAt: string;
	data: {
		skills: Record<string, Record<string, WomMetricDelta>>;
		bosses: Record<string, Record<string, WomMetricDelta>>;
		activities: Record<string, Record<string, WomMetricDelta>>;
		computed: Record<string, Record<string, WomMetricDelta>>;
	};
}

export type GainedPeriod = "day" | "week" | "month" | "year";

export async function womGained(username: string, period: GainedPeriod): Promise<WomGained> {
	const u = encodeURIComponent(username.trim().toLowerCase());
	return fetchJson<WomGained>(`${BASE}/players/${u}/gained?period=${period}`);
}

export interface GainedRow {
	metric: string;
	kind: "skill" | "boss" | "activity";
	gained: number;
	end: number;
}

/** Flatten a gained payload into non-zero deltas, biggest first. */
export function summarizeGained(gained: WomGained, minGain = 1): GainedRow[] {
	const rows: GainedRow[] = [];
	const collect = (obj: Record<string, Record<string, WomMetricDelta>>, kind: GainedRow["kind"], field: string) => {
		for (const [metric, fields] of Object.entries(obj)) {
			const d = fields[field];
			if (d && Math.abs(d.gained) >= minGain) {
				rows.push({ metric, kind, gained: d.gained, end: d.end });
			}
		}
	};
	collect(gained.data.skills ?? {}, "skill", "experience");
	collect(gained.data.bosses ?? {}, "boss", "kills");
	collect(gained.data.activities ?? {}, "activity", "score");
	rows.sort((a, b) => Math.abs(b.gained) - Math.abs(a.gained));
	return rows;
}

export interface WomPlayer {
	id: number;
	username: string;
	displayName: string;
	type: string;
	build: string;
	exp: number;
	ehp: number;
	ehb: number;
	ttm: number;
	latestSnapshot?: {
		createdAt: string;
		data: {
			skills: Record<string, { experience: number; level: number; rank: number }>;
			bosses: Record<string, { kills: number; rank: number }>;
			activities: Record<string, { score: number; rank: number }>;
		};
	};
}

export async function womPlayer(username: string): Promise<WomPlayer> {
	const u = encodeURIComponent(username.trim().toLowerCase());
	return fetchJson<WomPlayer>(`${BASE}/players/${u}`);
}
