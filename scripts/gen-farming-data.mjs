#!/usr/bin/env node
/*
 * Regenerates plugin/src/main/resources/farming/farming-data.json from RuneLite's
 * own timetracking sources.
 *
 * Why generate rather than call RuneLite's classes: FarmingWorld, FarmingPatch,
 * PatchImplementation and Produce are all package-private, so a plugin outside
 * net.runelite.client.plugins.timetracking.farming cannot name them. Reflection
 * would reach them but is ruled out (see README: the hub route requires code a
 * reviewer can follow end to end). The data those classes hold is not secret,
 * though, and their source is regular enough to lift mechanically -- which is
 * what this does, pinned to one RuneLite tag so the result is reproducible and
 * re-runnable when patches are added to the game.
 *
 * The extracted tables are derived from RuneLite (BSD-2-Clause, Copyright (c)
 * 2018-2019 Abex and contributors); the attribution travels in the generated
 * file's "source" field and in NOTICE.
 *
 *   node scripts/gen-farming-data.mjs [--tag runelite-parent-1.12.39] [--src DIR]
 *
 * --src reads the four .java files from a local directory instead of fetching
 * them, for working offline or against an unreleased checkout.
 */
import { writeFileSync, readFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(REPO, "plugin", "src", "main", "resources", "farming", "farming-data.json");

const FARMING = "runelite-client/src/main/java/net/runelite/client/plugins/timetracking/farming";
const TIMETRACKING = "runelite-client/src/main/java/net/runelite/client/plugins/timetracking";
const API = "runelite-api/src/main/java/net/runelite/api/gameval";

const SOURCES = {
	"Produce.java": FARMING,
	"PatchImplementation.java": FARMING,
	"FarmingWorld.java": FARMING,
	"Tab.java": TIMETRACKING,
	"VarbitID.java": API,
	"VarPlayerID.java": API,
};

function parseArgs(argv)
{
	const opts = { tag: "runelite-parent-1.12.39", src: null };
	for (let i = 0; i < argv.length; i++)
	{
		if (argv[i] === "--tag") opts.tag = argv[++i];
		else if (argv[i] === "--src") opts.src = argv[++i];
	}
	return opts;
}

async function load(opts, file)
{
	if (opts.src)
	{
		return readFileSync(join(opts.src, file), "utf8");
	}
	const url = `https://raw.githubusercontent.com/runelite/runelite/${opts.tag}/${SOURCES[file]}/${file}`;
	const res = await fetch(url);
	if (!res.ok)
	{
		throw new Error(`${url} -> HTTP ${res.status}`);
	}
	return res.text();
}

/** Splits a Java argument list on top-level commas (string literals and nesting respected). */
function splitArgs(s)
{
	const out = [];
	let depth = 0;
	let inStr = false;
	let cur = "";
	for (let i = 0; i < s.length; i++)
	{
		const c = s[i];
		if (inStr)
		{
			cur += c;
			if (c === "\\") { cur += s[++i]; }
			else if (c === '"') { inStr = false; }
			continue;
		}
		if (c === '"') { inStr = true; cur += c; continue; }
		if (c === "(" || c === "[") depth++;
		if (c === ")" || c === "]") depth--;
		if (c === "," && depth === 0) { out.push(cur.trim()); cur = ""; continue; }
		cur += c;
	}
	if (cur.trim()) out.push(cur.trim());
	return out;
}

/** Index of the ")" matching the "(" at `open`, skipping string literals. */
function matchParen(s, open)
{
	let depth = 0;
	let inStr = false;
	for (let i = open; i < s.length; i++)
	{
		const c = s[i];
		if (inStr)
		{
			if (c === "\\") i++;
			else if (c === '"') inStr = false;
			continue;
		}
		if (c === '"') { inStr = true; continue; }
		if (c === "(") depth++;
		else if (c === ")" && --depth === 0) return i;
	}
	throw new Error("unbalanced parentheses");
}

const unquote = (s) => s.replace(/^"|"$/g, "");

/** name -> numeric value, from a gameval constants file. */
function parseConstants(src)
{
	const out = new Map();
	for (const m of src.matchAll(/public static final int (\w+)\s*=\s*(-?\d+)/g))
	{
		out.set(m[1], Number(m[2]));
	}
	return out;
}

/**
 * Produce.java: five constructor overloads, told apart by argument count.
 *   4: (name, itemID, tickrate, stages)
 *   5: (name, impl, itemID, tickrate, stages)
 *   6: (name, contractVarbit, impl, itemID, tickrate, stages)
 *   7: (name, impl, itemID, tickrate, stages, regrowTickrate, harvestStages)
 *   8: (name, contractVarbit, impl, itemID, tickrate, stages, regrowTickrate, harvestStages)
 * itemID is dropped: it only drives RuneLite's icons, and resolving ItemID
 * constants would pull in a second 10k-constant file for nothing.
 */
function parseProduce(src)
{
	const body = src.slice(src.indexOf("public enum Produce"), src.indexOf("\tprivate final String name;"));
	const produce = {};
	for (const m of body.matchAll(/^\t([A-Z][A-Z_0-9]*)\(/gm))
	{
		const open = m.index + m[0].length - 1;
		const args = splitArgs(src.slice(0, 0) + body.slice(open + 1, matchParen(body, open)));
		const n = args.length;
		let name, contract, impl, tickrate, stages, regrow, harvest;
		if (n === 4)
		{
			[name, , tickrate, stages] = args;
			contract = "-1"; impl = null; regrow = "0"; harvest = "1";
		}
		else if (n === 5)
		{
			[name, impl, , tickrate, stages] = args;
			contract = "-1"; regrow = "0"; harvest = "1";
		}
		else if (n === 6)
		{
			[name, contract, impl, , tickrate, stages] = args;
			regrow = "0"; harvest = "1";
		}
		else if (n === 7)
		{
			[name, impl, , tickrate, stages, regrow, harvest] = args;
			contract = "-1";
		}
		else if (n === 8)
		{
			[name, contract, impl, , tickrate, stages, regrow, harvest] = args;
		}
		else
		{
			throw new Error(`Produce.${m[1]}: unexpected arity ${n}`);
		}
		produce[m[1]] = {
			name: unquote(name),
			// Value of VarbitID.FARMGUILD_CONTRACT_TYPE that asks for this crop; -1 when it is never contracted
			contractVarbitValue: Number(contract),
			implementation: impl && impl !== "null" ? impl.replace("PatchImplementation.", "") : null,
			tickrate: Number(tickrate),
			stages: Number(stages),
			regrowTickrate: Number(regrow),
			harvestStages: Number(harvest),
		};
	}
	return produce;
}

/**
 * Normalises RuneLite's stage expressions to `stage = a + b * varbitValue`.
 * Every form that appears in PatchImplementation.java is linear in `value`:
 *   "value - N" | "N" | "N - value" | "N + value - M" | "Produce.X.getStages() - N"
 */
function stageFormula(expr, produce, where)
{
	const e = expr.replace(/\/\/.*$/, "").replace(/\s+/g, " ").trim();
	let m;
	if ((m = /^(-?\d+)$/.exec(e))) return [Number(m[1]), 0];
	if ((m = /^value - (\d+)$/.exec(e))) return [-Number(m[1]), 1];
	if ((m = /^(\d+) - value$/.exec(e))) return [Number(m[1]), -1];
	if ((m = /^(\d+) \+ value - (\d+)$/.exec(e))) return [Number(m[1]) - Number(m[2]), 1];
	if ((m = /^Produce\.(\w+)\.getStages\(\) - (\d+)$/.exec(e)))
	{
		const p = produce[m[1]];
		if (!p) throw new Error(`${where}: unknown Produce.${m[1]}`);
		return [p.stages - Number(m[2]), 0];
	}
	throw new Error(`${where}: unhandled stage expression "${e}"`);
}

/**
 * PatchImplementation.java: each constant overrides forVarbitValue(int) with a
 * flat chain of `if (<range>) { return new PatchState(produce, state, <stage>); }`.
 * Three condition forms appear; all reduce to one or two [lo, hi] ranges.
 */
function parseImplementations(src, produce)
{
	const headers = [...src.matchAll(/^\t([A-Z][A-Z_0-9]*)\(Tab\.(\w+), "([^"]*)", (true|false)\)$/gm)];
	if (headers.length === 0) throw new Error("PatchImplementation: no enum constants matched");

	const impls = {};
	headers.forEach((h, i) =>
	{
		const body = src.slice(h.index, i + 1 < headers.length ? headers[i + 1].index : src.length);
		const ranges = [];
		const branch = /if \(value (?:>= (\d+) && value <= (\d+)|== (\d+)(?: \|\| value == (\d+))?)\)\s*\{\s*(?:\/\/[^\n]*\n\s*)*return new PatchState\(Produce\.(\w+), CropState\.(\w+), ([^;]+)\);/g;
		for (const b of body.matchAll(branch))
		{
			const where = `${h[1]} value branch`;
			const [a, mul] = stageFormula(b[7], produce, where);
			if (!produce[b[5]]) throw new Error(`${where}: unknown Produce.${b[5]}`);
			const spans = b[1] !== undefined
				? [[Number(b[1]), Number(b[2])]]
				: b[4] !== undefined
					? [[Number(b[3]), Number(b[3])], [Number(b[4]), Number(b[4])]]
					: [[Number(b[3]), Number(b[3])]];
			for (const [lo, hi] of spans)
			{
				ranges.push({ lo, hi, produce: b[5], state: b[6], a, b: mul });
			}
		}
		if (ranges.length === 0) throw new Error(`PatchImplementation.${h[1]}: no branches parsed`);
		impls[h[1]] = { tab: h[2], contractName: h[3], healthCheckRequired: h[4] === "true", ranges };
	});
	return impls;
}

/**
 * FarmingWorld.java: add(new FarmingRegion("Name", regionId, definite, patch...), aliases...).
 * The config key RuneLite stores a patch under is "<regionId>.<varbitId>", which
 * is all a reader needs -- the NPC id and patch number in the tail of some
 * FarmingPatch constructors only drive its UI.
 */
function parseRegions(src, varbits, impls)
{
	const regions = [];
	for (const m of src.matchAll(/new FarmingRegion\(/g))
	{
		const open = m.index + m[0].length - 1;
		const args = splitArgs(src.slice(open + 1, matchParen(src, open)));
		const name = unquote(args[0]);
		const regionId = Number(args[1]);
		if (!Number.isInteger(regionId)) throw new Error(`FarmingRegion "${name}": non-literal region id ${args[1]}`);

		const patches = [];
		for (const p of args.slice(3))
		{
			const pm = /^new FarmingPatch\((.*)\)$/s.exec(p);
			if (!pm) throw new Error(`FarmingRegion "${name}": unparsed patch ${p}`);
			const pa = splitArgs(pm[1]);
			const varbitName = pa[1].replace("VarbitID.", "");
			const varbit = varbits.get(varbitName);
			if (varbit === undefined) throw new Error(`FarmingRegion "${name}": unknown ${pa[1]}`);
			const impl = pa[2].replace("PatchImplementation.", "");
			if (!impls[impl]) throw new Error(`FarmingRegion "${name}": unknown PatchImplementation.${impl}`);
			patches.push({ name: unquote(pa[0]), varbit, implementation: impl });
		}
		regions.push({ name, regionId, definite: args[2] === "true", patches });
	}
	if (regions.length === 0) throw new Error("FarmingWorld: no regions matched");
	return regions;
}

function parseTabs(src)
{
	const tabs = {};
	for (const m of src.matchAll(/^\t([A-Z][A-Z_0-9]*)\("([^"]+)",/gm))
	{
		tabs[m[1]] = m[2];
	}
	return tabs;
}

async function main()
{
	const opts = parseArgs(process.argv.slice(2));
	const read = async (f) => load(opts, f);

	const [produceSrc, implSrc, worldSrc, tabSrc, varbitSrc, varpSrc] = await Promise.all([
		read("Produce.java"), read("PatchImplementation.java"), read("FarmingWorld.java"),
		read("Tab.java"), read("VarbitID.java"), read("VarPlayerID.java"),
	]);

	const produce = parseProduce(produceSrc);
	const implementations = parseImplementations(implSrc, produce);
	const varbits = parseConstants(varbitSrc);
	const varps = parseConstants(varpSrc);
	const regions = parseRegions(worldSrc, varbits, implementations);
	const tabs = parseTabs(tabSrc);

	// Bird houses live in the same config group and are small enough to carry here
	// rather than in a second file: four varps, a type every three values, 50 min.
	const birdhouses = ["A", "B", "C", "D"].map((suffix, i) =>
	{
		const varp = varps.get(`BIRDHOUSE_TRANSMIT_${suffix}`);
		if (varp === undefined) throw new Error(`unknown VarPlayerID.BIRDHOUSE_TRANSMIT_${suffix}`);
		return {
			name: ["Mushroom Meadow (North)", "Mushroom Meadow (South)", "Verdant Valley (Northeast)", "Verdant Valley (Southwest)"][i],
			varp,
		};
	});

	const data = {
		source: `runelite/runelite @ ${opts.tag} (BSD-2-Clause) -- regenerate with scripts/gen-farming-data.mjs`,
		generatedAt: new Date().toISOString().replace(/\.\d+Z$/, "Z"),
		tabs,
		produce,
		implementations,
		regions,
		birdhouses: {
			durationSeconds: 50 * 60,
			types: ["Bird House", "Oak Bird House", "Willow Bird House", "Teak Bird House", "Maple Bird House",
				"Mahogany Bird House", "Yew Bird House", "Magic Bird House", "Redwood Bird House"],
			spaces: birdhouses,
		},
	};

	mkdirSync(dirname(OUT), { recursive: true });
	writeFileSync(OUT, `${JSON.stringify(data, null, 1)}\n`);

	const patchCount = regions.reduce((n, r) => n + r.patches.length, 0);
	const rangeCount = Object.values(implementations).reduce((n, i) => n + i.ranges.length, 0);
	console.log(`${OUT}`);
	console.log(`  ${Object.keys(produce).length} produce, ${Object.keys(implementations).length} implementations `
		+ `(${rangeCount} varbit ranges), ${regions.length} regions, ${patchCount} patches`);
}

main().catch((err) =>
{
	console.error(err.message);
	process.exit(1);
});
