import assert from "node:assert/strict";
import { test } from "node:test";
import { parseQuestReqLua } from "../src/questdata.js";

const FIXTURE = `
-- Part of [[Template:Questreq]]'s implementation
--[[
    ['Name_of_quest'] = {
        ['quests'] = {
            'subquest'
        },
    },
--]]

return {
	['Dragon Slayer II'] = {
		['quests'] = {
			'Animal Magnetism',
			'Legends\\' Quest',
			'Started: A Tail of Two Cats'
		},
		['skills'] = {
			{'Quest point', 200},
			{'Agility', 60},
			{'Magic', 75, 'boostable'},
			{'Firemaking', 50, 'ironman'},
			{'Herblore', 10, 'ironman', 'boostable'}
		}
	},
	['Druidic Ritual'] = {
		['quests'] = {},
		['skills'] = {}
	},
}
`;

test("parses entries with quests, modes, and skill flags", () =>
{
	const data = parseQuestReqLua(FIXTURE);
	assert.equal(data.size, 2);

	const ds2 = data.get("Dragon Slayer II");
	assert.ok(ds2);
	assert.equal(ds2.quests.length, 3);
	assert.deepEqual(ds2.quests[0], { name: "Animal Magnetism", mode: "full" });
	assert.deepEqual(ds2.quests[2], { name: "A Tail of Two Cats", mode: "started" });

	assert.equal(ds2.skills.length, 5);
	const magic = ds2.skills.find((s) => s.skill === "Magic");
	assert.ok(magic);
	assert.equal(magic.level, 75);
	assert.equal(magic.boostable, true);
	assert.equal(magic.ironmanOnly, false);

	const herb = ds2.skills.find((s) => s.skill === "Herblore");
	assert.ok(herb);
	assert.equal(herb.boostable, true);
	assert.equal(herb.ironmanOnly, true);

	const firemaking = ds2.skills.find((s) => s.skill === "Firemaking");
	assert.ok(firemaking);
	assert.equal(firemaking.ironmanOnly, true);
	assert.equal(firemaking.boostable, false);
});

test("ignores the commented-out template block", () =>
{
	const data = parseQuestReqLua(FIXTURE);
	assert.equal(data.has("Name_of_quest"), false);
});

test("handles empty requirement lists", () =>
{
	const data = parseQuestReqLua(FIXTURE);
	const druidic = data.get("Druidic Ritual");
	assert.ok(druidic);
	assert.deepEqual(druidic.quests, []);
	assert.deepEqual(druidic.skills, []);
});
