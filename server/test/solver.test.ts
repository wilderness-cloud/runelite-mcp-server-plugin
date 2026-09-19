import assert from "node:assert/strict";
import { test } from "node:test";
import { parseQuestReqLua } from "../src/questdata.js";
import { solve, type PlayerState } from "../src/solver.js";

const GRAPH = `
return {
	['Target Quest'] = {
		['quests'] = {
			'Quest B',
			'Started: Quest C'
		},
		['skills'] = {
			{'Quest point', 50},
			{'Magic', 75, 'boostable'},
			{'Crafting', 62}
		}
	},
	['Quest B'] = {
		['quests'] = {
			'Quest D'
		},
		['skills'] = {
			{'Crafting', 62},
			{'Thieving', 60}
		}
	},
	['Quest C'] = {
		['quests'] = {},
		['skills'] = {}
	},
	['Quest D'] = {
		['quests'] = {},
		['skills'] = {}
	},
	['Unrelated'] = {
		['quests'] = {},
		['skills'] = {}
	},
}
`;

function player(quests: PlayerState["quests"], skills: Record<string, number>): PlayerState
{
	return { quests, skills };
}

test("computes recursive missing chain and skill gaps", () =>
{
	const data = parseQuestReqLua(GRAPH);
	const p = player(
		{
			"Quest C": "IN_PROGRESS", // started-mode requirement satisfied
			"Quest D": "NOT_STARTED",
		},
		{ Crafting: 80, Thieving: 55, Magic: 70 },
	);

	const result = solve("Target Quest", data, p);

	assert.equal(result.ready, false);
	assert.equal(result.missingQuests.length, 2);
	// dependency order: D before B
	assert.equal(result.missingQuests[0].name, "Quest D");
	assert.equal(result.missingQuests[1].name, "Quest B");
	assert.equal(result.missingQuests[1].requiredBy[0], "Target Quest");

	const skills = Object.fromEntries(result.skillGaps.map((g) => [g.skill, g]));
	assert.equal(skills.Magic.need, 75);
	assert.equal(skills.Magic.have, 70);
	assert.equal(skills.Magic.boostable, true);
	assert.equal(skills.Thieving.need, 60);
	assert.equal(skills.Thieving.have, 55);
	assert.equal(skills.Crafting, undefined); // 80 >= 62, satisfied

	assert.deepEqual(result.otherRequirements, ["Quest point: 50"]);
});

test("reports unknown/miniquest prerequisites as notes", () =>
{
	const data = parseQuestReqLua(`
return {
	['Outer'] = {
		['quests'] = { 'Barbarian Firemaking' },
		['skills'] = {}
	},
}
`);
	const result = solve("Outer", data, player({}, {}));
	assert.equal(result.missingQuests.length, 1);
	assert.equal(result.missingQuests[0].known, false);
	assert.ok(result.notes.some((n) => n.includes("Barbarian Firemaking")));
});

test("resolves kudos and combat pseudo-skills when player values known", () =>
{
	const data = parseQuestReqLua(`
return {
	['Museum Helper'] = {
		['quests'] = {},
		['skills'] = { {'Kudos', 100}, {'Combat', 85}, {'Quest point', 50} }
	},
}
`);

	const withValues = solve("Museum Helper", data, player({}, { Kudos: 80, Combat: 90 }));
	const skills = Object.fromEntries(withValues.skillGaps.map((g) => [g.skill, g]));
	assert.equal(skills.Kudos.have, 80);
	assert.equal(skills.Kudos.need, 100);
	assert.equal(skills.Combat, undefined); // 90 >= 85, satisfied
	assert.deepEqual(withValues.otherRequirements, ["Quest point: 50"]);

	const without = solve("Museum Helper", data, player({}, {}));
	assert.deepEqual(without.otherRequirements, ["Kudos: 100", "Combat: 85", "Quest point: 50"]);
});

test("ready when everything is satisfied", () =>
{
	const data = parseQuestReqLua(GRAPH);
	const p = player(
		{ "Target Quest": "NOT_STARTED", "Quest B": "FINISHED", "Quest C": "FINISHED", "Quest D": "FINISHED" },
		{ Crafting: 80, Thieving: 70, Magic: 80 },
	);
	const result = solve("Target Quest", data, p);
	assert.equal(result.ready, true);
	assert.deepEqual(result.missingQuests, []);
	assert.deepEqual(result.skillGaps, []);
});
