import { cachedJson } from "./cache.js";
import type { BankItem } from "./bridge.js";
import { fetchJson } from "./http.js";

/**
 * Weird Gloop real-time GE prices: https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices
 * A descriptive User-Agent is required (handled in http.ts).
 */
const BASE = "https://prices.runescape.wiki/api/v2/osrs";

export interface ItemMapping {
	id: number;
	name: string;
	limit?: number;
	value: number;
	highalch?: number;
	lowalch?: number;
	members: boolean;
	examine: string;
}

export interface LatestEntry {
	high: number;
	highTime: number;
	low: number;
	lowTime: number;
}

export interface AvgEntry {
	avgHighPrice: number | null;
	highPriceVolume: number;
	avgLowPrice: number | null;
	lowPriceVolume: number;
}

const HOUR = 3600_000;

export async function getMapping(): Promise<ItemMapping[]> {
	return cachedJson("prices-mapping", 12 * HOUR, async () =>
		(await fetchJson<ItemMapping[]>(`${BASE}/mapping`)),
	);
}

export async function getLatest(): Promise<Record<string, LatestEntry>> {
	return cachedJson("prices-latest", 90_000, async () =>
		(await fetchJson<{ data: Record<string, LatestEntry> }>(`${BASE}/latest`)).data,
	);
}

export async function getAvg(period: "5m" | "1h"): Promise<Record<string, AvgEntry>> {
	return cachedJson(`prices-${period}`, 5 * 60_000, async () =>
		(await fetchJson<{ data: Record<string, AvgEntry> }>(`${BASE}/${period}`)).data,
	);
}

export async function resolveItem(query: string): Promise<ItemMapping | undefined> {
	const mapping = await getMapping();
	const q = query.trim().toLowerCase();
	const byId = Number(q);
	if (Number.isInteger(byId) && byId > 0) {
		return mapping.find((m) => m.id === byId);
	}
	return (
		mapping.find((m) => m.name.toLowerCase() === q) ??
		mapping.find((m) => m.name.toLowerCase().startsWith(q)) ??
		mapping.find((m) => m.name.toLowerCase().includes(q))
	);
}

export interface PriceReport {
	item: ItemMapping;
	latest?: LatestEntry;
	avg1h?: AvgEntry;
	avg5m?: AvgEntry;
}

export async function priceReport(item: ItemMapping): Promise<PriceReport> {
	const key = String(item.id);
	const [latest, avg1h, avg5m] = await Promise.all([getLatest(), getAvg("1h"), getAvg("5m")]);
	return {
		item,
		latest: latest[key],
		avg1h: avg1h[key],
		avg5m: avg5m[key],
	};
}

export interface ValuedItem extends BankItem {
	unitHigh: number | null;
	unitLow: number | null;
	totalHigh: number | null;
	totalLow: number | null;
}

export interface Valuation {
	totalHigh: number;
	totalLow: number;
	valuedItems: number;
	unpriced: BankItem[];
	top: ValuedItem[];
}

export async function valuate(items: BankItem[], topN = 15): Promise<Valuation> {
	const latest = await getLatest();
	let totalHigh = 0;
	let totalLow = 0;
	const valued: ValuedItem[] = [];
	const unpriced: BankItem[] = [];

	for (const item of items) {
		const entry = latest[String(item.id)];
		if (!entry || (!entry.high && !entry.low)) {
			unpriced.push(item);
			continue;
		}
		const unitHigh = entry.high || entry.low;
		const unitLow = entry.low || entry.high;
		const v: ValuedItem = {
			...item,
			unitHigh,
			unitLow,
			totalHigh: unitHigh * item.qty,
			totalLow: unitLow * item.qty,
		};
		valued.push(v);
		if (v.totalHigh !== null) totalHigh += v.totalHigh;
		if (v.totalLow !== null) totalLow += v.totalLow;
	}

	valued.sort((a, b) => (b.totalHigh ?? 0) - (a.totalHigh ?? 0));
	return { totalHigh, totalLow, valuedItems: valued.length, unpriced, top: valued.slice(0, topN) };
}
