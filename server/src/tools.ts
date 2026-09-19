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
import { priceReport, resolveItem, valuate } from "./prices.js";
import { solve, playerStateFromSnapshot } from "./solver.js";
import { rawWikitext, searchTitles, truncate, wikiUrl } from "./wiki.js";
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
			return json({ ...report, wiki: wikiUrl(item.name) });
		},
	);

	server.tool(
		"boss_info",
		"Boss summary: your current killcount (if the game client is running) plus the intro section of the boss's wiki page and a link to its strategy page.",
		{ name: z.string().describe("Boss name, e.g. 'Vorkath' or 'Zulrah'") },
		async ({ name }) =>
		{
			const q = name.toLowerCase();
			let killcount: number | undefined;
			try
			{
				const kc = await bridgeGet<{ bosses: { name: string; kills?: number }[] }>("/kc", 4000);
				killcount = kc.bosses?.find((b) => b.name.toLowerCase().includes(q))?.kills;
			}
			catch
			{
				// client offline; KC just omitted
			}

			const titles = await searchTitles(name, 5);
			const best = titles[0] ?? name;
			let excerpt = "";
			try
			{
				excerpt = truncate(await rawWikitext(best), 2500);
			}
			catch
			{
				// wiki unreachable
			}

			return json({
				query: name,
				killcount,
				wikiPage: wikiUrl(best),
				strategyPage: wikiUrl(`${best}/Strategies`),
				wikitextExcerpt: excerpt,
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
