import { cachedJson } from "./cache.js";
import { rawWikitext } from "./wiki.js";

/**
 * Structured quest requirement data from the wiki's Module:Questreq/data Lua
 * table — the ground truth for prerequisite chains and skill requirements.
 */

export interface QuestRequirement {
	name: string;
	mode: "full" | "started"; // Started:/Full: prefixes; plain = full
}

export interface SkillRequirement {
	skill: string;
	level: number;
	boostable: boolean;
	ironmanOnly: boolean;
}

export interface QuestReqEntry {
	name: string;
	quests: QuestRequirement[];
	skills: SkillRequirement[];
}

export type QuestData = Map<string, QuestReqEntry>;

const WEEK = 7 * 24 * 3600_000;

export async function getQuestData(): Promise<QuestData> {
	const raw = await cachedJson("questreq-data", WEEK, async () =>
		rawWikitext("Module:Questreq/data"),
	);
	return parseQuestReqLua(raw);
}

/** Strip Lua block comments and line comments. */
function stripComments(text: string): string {
	return text
		.replace(/--\[\[[\s\S]*?\]\]/g, "")
		.replace(/--[^\n]*/g, "");
}

/** Find the matching closing brace for the `{` at `openIdx`, skipping strings. */
function matchingBrace(text: string, openIdx: number): number {
	let depth = 0;
	let inString: "'" | '"' | null = null;
	for (let i = openIdx; i < text.length; i++) {
		const c = text[i];
		if (inString) {
			if (c === "\\") {
				i++;
			} else if (c === inString) {
				inString = null;
			}
			continue;
		}
		if (c === "'" || c === '"') {
			inString = c;
		} else if (c === "{") {
			depth++;
		} else if (c === "}") {
			depth--;
			if (depth === 0) {
				return i;
			}
		}
	}
	return -1;
}

function parseQuestList(block: string): QuestRequirement[] {
	const out: QuestRequirement[] = [];
	const re = /'((?:Started|Full):\s*)?((?:\\.|[^'\\])+)'/g;
	let m: RegExpExecArray | null;
	while ((m = re.exec(block)) !== null) {
		out.push({
			name: m[2].trim().replace(/\\'/g, "'"),
			mode: m[1]?.startsWith("Started") ? "started" : "full",
		});
	}
	return out;
}

function parseSkillList(block: string): SkillRequirement[] {
	const out: SkillRequirement[] = [];
	const re = /\{\s*'((?:\\.|[^'\\])+)',\s*(\d+)\s*(,[^}]*)?\}/g;
	let m: RegExpExecArray | null;
	while ((m = re.exec(block)) !== null) {
		const flags = m[3] ?? "";
		out.push({
			skill: m[1].trim().replace(/\\'/g, "'"),
			level: Number(m[2]),
			boostable: flags.includes("'boostable'"),
			ironmanOnly: flags.includes("'ironman'"),
		});
	}
	return out;
}

/** Parse the specific Lua subset used by Module:Questreq/data. */
export function parseQuestReqLua(rawText: string): QuestData {
	const text = stripComments(rawText);
	const data: QuestData = new Map();
	const headerRe = /\['((?:\\.|[^'\\])+?)'\]\s*=\s*\{/g;
	let m: RegExpExecArray | null;
	while ((m = headerRe.exec(text)) !== null) {
		const openIdx = m.index + m[0].length - 1;
		const closeIdx = matchingBrace(text, openIdx);
		if (closeIdx < 0) {
			break;
		}
		const body = text.slice(openIdx + 1, closeIdx);

		const questsMatch = /\['quests'\]\s*=\s*\{/.exec(body);
		const skillsMatch = /\['skills'\]\s*=\s*\{/.exec(body);
		const quests = questsMatch
			? parseQuestList(
					body.slice(
						questsMatch.index + questsMatch[0].length,
						matchingBrace(body, questsMatch.index + questsMatch[0].length - 1),
					),
				)
			: [];
		const skills = skillsMatch
			? parseSkillList(
					body.slice(
						skillsMatch.index + skillsMatch[0].length,
						matchingBrace(body, skillsMatch.index + skillsMatch[0].length - 1),
					),
				)
			: [];

		const name = m[1].trim().replace(/\\'/g, "'");
		if (!data.has(name)) {
			data.set(name, { name, quests, skills });
		}
		headerRe.lastIndex = closeIdx;
	}
	return data;
}
