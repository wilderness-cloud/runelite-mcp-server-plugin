import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import {
	bridgeGet,
	tryBridgeGet,
	type BankPayload,
	type HealthPayload,
	type SnapshotPayload,
} from "./bridge.js";
import { getQuestData } from "./questdata.js";
import { getTimeseries, priceReport, resolveItem, summarizeSeries, valuate, type Lookback } from "./prices.js";
import { solve, playerStateFromSnapshot } from "./solver.js";
import { wikiUrl } from "./wiki.js";
import { summarizeGained, womGained, type GainedPeriod } from "./wom.js";

function text(body: string, isError = false)
{
	return {
		content: [{ type: "text" as const, text: body }],
		...(isError ? { isError: true } : {}),
	};
}

function json(body: unknown, isError = false)
{
	return text(JSON.stringify(body, null, 1), isError);
}

function titleCaseMetric(metric: string): string
{
	return metric.replaceAll("_", " ");
}

/** Transitive prerequisite closure of a quest from the wiki data. */
function transitiveClosure(name: string, data: Awaited<ReturnType<typeof getQuestData>>): string[]
{
	const out: string[] = [];
	const seen = new Set<string>();
	const walk = (n: string) =>
	{
		if (seen.has(n))
		{
			return;
		}
		seen.add(n);
		const entry = data.get(n);
		if (!entry)
		{
			return;
		}
		for (const req of entry.quests)
		{
			out.push(req.mode === "started" ? `${req.name} (started)` : req.name);
			walk(req.name);
		}
	};
	walk(name);
	return [...new Set(out)];
}


/** One item's entry in the plugin's observed four-hour buying, if it has one. */
interface BuyLimitUsage {
	buyLimitUsage?: {
		isFloor?: boolean;
		trackingSince?: string;
		note?: string;
		items?: { itemId: number; boughtInWindow: number; windowResetsAt?: string }[];
	};
}

/**
 * The half of a buy limit each side knows: the per-item cap comes from the GE
 * mapping, what is already spent against it only from watching the player's own
 * offers. Neither is any use alone when the question is "can I place this
 * order", so they are joined here.
 *
 * Absent when the game client is not running, and always a best case: the
 * plugin's count is a floor (see the grand_exchange tool).
 */
async function buyLimitFor(itemId: number, limit: number | undefined) {
	if (limit === undefined) {
		return { limit: null, note: "The Grand Exchange mapping lists no buy limit for this item." };
	}
	const ge = await tryBridgeGet<BuyLimitUsage>("/grand-exchange");
	if (!ge.ok || !ge.data.buyLimitUsage) {
		return {
			limit,
			note: "Buy every 4 hours. How much of that is already spent is unknown: it needs the game client "
				+ "running with the RuneLite MCP Server plugin, which watches your own offers fill.",
		};
	}
	const usage = ge.data.buyLimitUsage;
	const row = usage.items?.find((i) => i.itemId === itemId);
	return {
		limit,
		observedBought: row?.boughtInWindow ?? 0,
		remainingAtBest: Math.max(0, limit - (row?.boughtInWindow ?? 0)),
		windowResetsAt: row?.windowResetsAt,
		isFloor: true,
		note: usage.note ?? "Observed buying is a lower bound, so remainingAtBest is an upper bound.",
	};
}

export function registerTools(server: McpServer): void
{
	server.tool(
		"game_state",
		"Live account state from the RuneLite client: skills (real/boosted/XP), every quest's completion state, achievement diary tiers, combat achievement summary (per-task detail on request), slayer task/points with decoded unlocks, boss killcounts, inventory, equipment, last bank snapshot, kudos, and collection log aggregate. Requires the game running with the MCP Bridge plugin.",
		{
			detail: z.array(z.enum(["combat_achievements", "collection_log"]))
				.optional()
				.describe("Fetch full detail endpoints in addition to the snapshot: per-task combat achievements and/or the full collection log catalog"),
		},
		async ({ detail }) => {
			const result = await tryBridgeGet<SnapshotPayload>("/snapshot");
			if (!result.ok) {
				return text(result.error, true);
			}
			const payload: Record<string, unknown> = result.data;

			if (detail?.includes("combat_achievements")) {
				const ca = await tryBridgeGet<Record<string, unknown>>("/combat-achievements");
				if (ca.ok) {
					payload.combatAchievements = ca.data;
				}
			}
			if (detail?.includes("collection_log")) {
				const clog = await tryBridgeGet<Record<string, unknown>>("/collection-log");
				if (clog.ok) {
					payload.collectionLog = clog.data;
				}
			}
			return json(payload);
		},
	);

	server.tool(
		"bank_snapshot",
		"Bank contents (item ids/qty/names, timestamped from when the bank was last open) with a Grand Exchange valuation: total value range, most valuable items, and unpriced item count. Requires the bank to have been opened once this session.",
		{},
		async () =>
		{
			const bank = await tryBridgeGet<BankPayload>("/bank");
			if (!bank.ok)
			{
				return text(bank.error, true);
			}
			if (!bank.data.available)
			{
				return text(`Bank snapshot unavailable. ${bank.data.note ?? ""}`);
			}

			const items = bank.data.items ?? [];
			let valuation: unknown = null;
			try
			{
				const v = await valuate(items);
				valuation = {
					totalHigh: v.totalHigh,
					totalLow: v.totalLow,
					valuedItems: v.valuedItems,
					unpricedCount: v.unpriced.length,
					topItems: v.top,
				};
			}
			catch
			{
				// price feed unreachable; still return contents
			}

			return json({
				capturedAt: bank.data.capturedAt,
				ageSeconds: bank.data.ageSeconds,
				uniqueItems: items.length,
				valuation,
				items,
			});
		},
	);

	server.tool(
		"ge_price",
		"Grand Exchange price for an item by exact or partial name (or numeric item id): latest instant buy/sell plus 5m and 1h averaged prices with volumes.",
		{ nameOrId: z.string().describe("Item name (exact, prefix, or substring) or numeric item id") },
		async ({ nameOrId }) =>
		{
			const item = await resolveItem(nameOrId);
			if (!item)
			{
				return text(`No GE-traded item matches "${nameOrId}".`, true);
			}
			const report = await priceReport(item);
			return json({
				...report,
				buyLimit: await buyLimitFor(item.id, item.limit),
				wiki: wikiUrl(item.name),
			});
		},
	);


	server.tool(
		"ge_history",
		"Grand Exchange price history for one item: averaged high/low prices with volumes over the window you ask for, plus a summary — change across the window, min/max, and traded volume per day. USE THIS before a bulk order or a flip: the spot price alone cannot say whether an item is drifting, spiking, or barely traded, and a thin item will not fill 100 of anything at the price ge_price quotes. Pair it with the buy limit in ge_price when sizing an order.",
		{
			nameOrId: z.string().describe("Item name (exact, prefix, or substring) or numeric item id"),
			lookback: z.enum(["24h", "7d", "30d", "1y"]).default("30d")
				.describe("How far back to look. The price API picks the granularity to match: 24h arrives in 5-minute steps, 7d hourly, 30d six-hourly, 1y daily."),
			points: z.number().int().min(1).max(365).optional()
				.describe("How many of the most recent points to return in full. The summary always covers the whole series. Default 48."),
		},
		async ({ nameOrId, lookback, points }) =>
		{
			const item = await resolveItem(nameOrId);
			if (!item)
			{
				return text(`No GE-traded item matches "${nameOrId}".`, true);
			}
			const series = await getTimeseries(item.id, lookback as Lookback);
			if (series.length === 0)
			{
				return text(`The price API has no ${lookback} history for ${item.name}.`);
			}
			const keep = points ?? 48;
			return json({
				item: { id: item.id, name: item.name, limit: item.limit },
				lookback,
				summary: summarizeSeries(series),
				pointsReturned: Math.min(keep, series.length),
				pointsOmitted: Math.max(0, series.length - keep),
				series: series.slice(-keep).map((p) => ({
					at: new Date(p.timestamp * 1000).toISOString(),
					avgHighPrice: p.avgHighPrice,
					avgLowPrice: p.avgLowPrice,
					highPriceVolume: p.highPriceVolume,
					lowPriceVolume: p.lowPriceVolume,
				})),
				wiki: wikiUrl(item.name),
			});
		},
	);

	server.tool(
		"quest_info",
		"Structured quest data from the OSRS wiki: direct prerequisites (with started/finished mode), skill requirements (with boostable/ironman flags), the full transitive prerequisite chain, and a wiki link.",
		{ name: z.string().describe("Quest name as it appears on the wiki, e.g. 'Dragon Slayer II'") },
		async ({ name }) =>
		{
			const data = await getQuestData();
			const exact = data.get(name);
			const entry =
				exact ??
				[...data.entries()].find(([k]) => k.toLowerCase() === name.toLowerCase())?.[1] ??
				[...data.entries()].find(([k]) => k.toLowerCase().includes(name.toLowerCase()))?.[1];

			if (!entry)
			{
				return text(`No quest requirement data for "${name}". Check spelling against the wiki.`, true);
			}
			return json({
				...entry,
				fullPrerequisiteChain: transitiveClosure(entry.name, data),
				wiki: wikiUrl(entry.name),
			});
		},
	);

	server.tool(
		"item_info",
		"Item reference data: GE mapping info (trade limit, alch values, store price, members, examine), current GE prices, and a wiki link.",
		{ name: z.string().describe("Item name or numeric item id") },
		async ({ name }) =>
		{
			const item = await resolveItem(name);
			if (!item)
			{
				return text(`No item matches "${name}".`, true);
			}
			const report = await priceReport(item);
			return json({
				...report,
				buyLimit: await buyLimitFor(item.id, item.limit),
				wiki: wikiUrl(item.name),
			});
		},
	);

	server.tool(
		"solve_requirements",
		"Compute the exact gap between a player's live state and a target quest: every missing quest in the full recursive chain (ordered so prerequisites come first), per-skill level gaps with boostable flags, and pseudo-requirements (quest points, kudos, combat level). Requires the game client running.",
		{ quest: z.string().describe("Target quest name, e.g. 'Dragon Slayer II'") },
		async ({ quest }) =>
		{
			const snapshot = await tryBridgeGet<SnapshotPayload>("/snapshot");
			if (!snapshot.ok)
			{
				return text(snapshot.error, true);
			}
			if (!snapshot.data.state?.loggedIn)
			{
				return text("The game client is running but not logged in.", true);
			}

			const data = await getQuestData();
			const target =
				data.get(quest) ??
				[...data.entries()].find(([k]) => k.toLowerCase() === quest.toLowerCase())?.[1];
			if (!target)
			{
				return text(`No quest requirement data for "${quest}". Check spelling against the wiki.`, true);
			}

			const result = solve(target.name, data, playerStateFromSnapshot(snapshot.data));
			return json(result);
		},
	);

	server.tool(
		"progress_delta",
		"XP/KC/activity gains over a period from Wise Old Man — what the account actually did (needs the player tracked on wiseoldman.net; default username comes from the running game client).",
		{
			period: z.enum(["day", "week", "month", "year"]).default("week"),
			username: z.string().optional().describe("RuneScape username; defaults to the logged-in player"),
		},
		async ({ period, username }) =>
		{
			let user = username;
			if (!user)
			{
				try
				{
					const health = await bridgeGet<HealthPayload>("/health", 4000);
					user = health.username;
				}
				catch
				{
					// offline; username must have been provided
				}
			}
			if (!user)
			{
				return text("No username available: pass the 'username' argument (game client not running).", true);
			}

			const gained = await womGained(user, period as GainedPeriod);
			const rows = summarizeGained(gained).slice(0, 30).map((r) => ({
				...r,
				metric: titleCaseMetric(r.metric),
			}));
			return json({
				username: user,
				period,
				window: { from: gained.startsAt, to: gained.endsAt },
				gains: rows,
			});
		},
	);
}
