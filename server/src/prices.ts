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

export interface TimeseriesPoint {
	timestamp: number;
	avgHighPrice: number | null;
	avgLowPrice: number | null;
	highPriceVolume: number;
	lowPriceVolume: number;
}

/**
 * How far back to look. The API picks the granularity to match — 24h comes back
 * in 5-minute steps, 1y in daily ones — so a caller asks for a span, not a step.
 */
export type Lookback = "24h" | "7d" | "30d" | "1y";

/**
 * Price history for one item. Spot price alone says nothing about whether the
 * thing is moving or whether it trades at all, which is what a bulk order
 * actually turns on.
 *
 * Note this is the only call here that uses v2's "lookback" rather than a
 * timestep: v2 rejects timestep outright, and v1's timestep form is the older
 * spelling of the same series.
 */
export async function getTimeseries(itemId: number, lookback: Lookback): Promise<TimeseriesPoint[]> {
	const ttl: Record<Lookback, number> = {
		"24h": 5 * 60_000,
		"7d": 30 * 60_000,
		"30d": HOUR,
		"1y": 6 * HOUR,
	};
	return cachedJson(`timeseries-${itemId}-${lookback}`, ttl[lookback], async () =>
		(await fetchJson<{ data: TimeseriesPoint[] }>(`${BASE}/timeseries?lookback=${lookback}&id=${itemId}`)).data,
	);
}

export interface TrendSummary {
	points: number;
	stepMinutes: number | null;
	from?: string;
	to?: string;
	firstPrice: number | null;
	lastPrice: number | null;
	minPrice: number | null;
	maxPrice: number | null;
	changePercent: number | null;
	totalVolume: number;
	averageDailyVolume: number | null;
}

/**
 * Reduces a series to the handful of numbers a buying decision turns on. The
 * step is measured from the timestamps rather than assumed from the lookback,
 * so a change at the API's end cannot quietly skew volume per day.
 */
export function summarizeSeries(points: TimeseriesPoint[]): TrendSummary {
	const mid = (p: TimeseriesPoint): number | null => {
		const high = p.avgHighPrice;
		const low = p.avgLowPrice;
		if (high !== null && low !== null) return Math.round((high + low) / 2);
		return high ?? low;
	};
	const prices = points.map(mid).filter((p): p is number => p !== null);
	const totalVolume = points.reduce((n, p) => n + p.highPriceVolume + p.lowPriceVolume, 0);

	const first = points[0];
	const last = points[points.length - 1];
	const spanSeconds = first && last ? last.timestamp - first.timestamp : 0;
	const stepSeconds = points.length > 1 ? spanSeconds / (points.length - 1) : 0;

	const firstPrice = prices[0] ?? null;
	const lastPrice = prices[prices.length - 1] ?? null;
	return {
		points: points.length,
		stepMinutes: stepSeconds > 0 ? Math.round(stepSeconds / 60) : null,
		from: first ? new Date(first.timestamp * 1000).toISOString() : undefined,
		to: last ? new Date(last.timestamp * 1000).toISOString() : undefined,
		firstPrice,
		lastPrice,
		minPrice: prices.length ? Math.min(...prices) : null,
		maxPrice: prices.length ? Math.max(...prices) : null,
		changePercent: firstPrice && lastPrice
			? Math.round(((lastPrice - firstPrice) / firstPrice) * 1000) / 10
			: null,
		totalVolume,
		averageDailyVolume: spanSeconds > 0
			? Math.round(totalVolume / (spanSeconds / 86400))
			: null,
	};
}
