import type { QuestData, QuestReqEntry } from "./questdata.js";
import type { SnapshotPayload } from "./bridge.js";

/**
 * Requirement solver: given a target quest and the live player snapshot from
 * the RuneLite plugin, compute the exact delta — recursively missing quests in
 * dependency order and per-skill level gaps (with boostable flags).
 */

export interface PlayerState {
	quests: Record<string, "FINISHED" | "IN_PROGRESS" | "NOT_STARTED">;
	skills: Record<string, number>;
}

export interface MissingQuest {
	name: string;
	requiredBy: string[];
	mode: "full" | "started";
	known: boolean; // known = present in the player's quest state (RuneLite Quest enum)
}

export interface SkillGap {
	skill: string;
	have: number | null;
	need: number;
	boostable: boolean;
	from: string[];
}

export interface SolveResult {
	target: string;
	targetState: "FINISHED" | "IN_PROGRESS" | "NOT_STARTED" | null;
	ready: boolean;
	missingQuests: MissingQuest[]; // dependency order: prerequisites first
	skillGaps: SkillGap[];
	otherRequirements: string[]; // pseudo-skills like Quest points / Kudos / Combat
	notes: string[];
}

export function playerStateFromSnapshot(snapshot: SnapshotPayload): PlayerState {
	const skills: Record<string, number> = {};
	for (const [name, s] of Object.entries(snapshot.state.skills ?? {})) {
		skills[name] = s.level;
	}
	if (typeof snapshot.state.combatLevel === "number") {
		skills["Combat"] = snapshot.state.combatLevel;
	}
	if (typeof snapshot.state.kudos === "number") {
		skills["Kudos"] = snapshot.state.kudos;
	}
	return { quests: snapshot.quests?.quests ?? {}, skills };
}

function isSatisfied(
	player: PlayerState,
	name: string,
	mode: "full" | "started",
): { satisfied: boolean; known: boolean } {
	const state = player.quests[name];
	if (state === undefined) {
		return { satisfied: false, known: false };
	}
	if (mode === "full") {
		return { satisfied: state === "FINISHED", known: true };
	}
	return { satisfied: state !== "NOT_STARTED", known: true };
}

const PSEUDO_SKILLS = new Set(["Quest point", "Quest points", "Kudos", "Combat"]);

export function solve(target: string, data: QuestData, player: PlayerState): SolveResult {
	const notes: string[] = [];
	const missing = new Map<string, MissingQuest>();
	const skillSources = new Map<string, SkillGap>();

	const targetState = player.quests[target] ?? null;
	if (targetState === undefined && !(target in player.quests) && !data.has(target)) {
		notes.push(`"${target}" not found in wiki quest requirement data; check spelling (names must match the wiki exactly)`);
	}

	const visit = (name: string, mode: "full" | "started", requiredBy: string, seen: Set<string>) => {
		const key = `${name}|${mode}`;
		if (seen.has(key)) {
			return;
		}
		seen.add(key);

		const { satisfied, known } = isSatisfied(player, name, mode);
		if (satisfied) {
			return;
		}

		const existing = missing.get(name);
		if (existing) {
			if (mode === "full") {
				existing.mode = "full";
			}
			if (!existing.requiredBy.includes(requiredBy)) {
				existing.requiredBy.push(requiredBy);
			}
		} else {
			missing.set(name, { name, requiredBy: [requiredBy], mode, known });
		}

		const entry: QuestReqEntry | undefined = data.get(name);
		if (!entry) {
			notes.push(`No requirement data for "${name}" (likely a miniquest or out-of-data name) — verify manually`);
			return;
		}

		for (const req of entry.quests) {
			visit(req.name, req.mode, name, seen);
		}
	};

	// Collect the full transitive closure of unsatisfied prerequisites
	const entries: { name: string; entry: QuestReqEntry }[] = [];
	const collectEntries = (name: string, seen: Set<string>): void => {
		if (seen.has(name)) {
			return;
		}
		seen.add(name);
		const entry = data.get(name);
		if (!entry) {
			return;
		}
		entries.push({ name, entry });
		for (const req of entry.quests) {
			collectEntries(req.name, seen);
		}
	};

	visit(target, "full", "(target)", new Set());
	// The target itself isn't "missing" — its state is reported separately
	missing.delete(target);
	collectEntries(target, new Set());

	// Skill requirements: target itself plus every missing quest
	const addSkill = (skill: string, level: number, boostable: boolean, from: string) => {
		const have = player.skills[skill];
		if (have !== undefined && have >= level) {
			return;
		}
		const gap = skillSources.get(skill);
		if (gap) {
			if (level > gap.need) {
				gap.need = level;
				gap.boostable = gap.boostable || boostable;
			}
			if (!gap.from.includes(from)) {
				gap.from.push(from);
			}
		} else {
			skillSources.set(skill, { skill, have: have ?? null, need: level, boostable, from: [from] });
		}
	};

	const others = new Map<string, number>();
	const addOther = (skill: string, level: number) => {
		others.set(skill, Math.max(others.get(skill) ?? 0, level));
	};

	for (const { name, entry } of entries) {
		const isTarget = name === target;
		const needed = isTarget || missing.has(name);
		if (!needed) {
			continue;
		}
		for (const s of entry.skills) {
			// Pseudo-skills (quest points, kudos, combat level) resolve to gaps
			// when the player value is known, otherwise surface as requirements
			const resolvable = !PSEUDO_SKILLS.has(s.skill) || player.skills[s.skill] !== undefined;
			if (resolvable) {
				if (isTarget || missing.has(name)) {
					addSkill(s.skill, s.level, s.boostable, name);
				}
			} else {
				addOther(s.skill === "Quest points" ? "Quest point" : s.skill, s.level);
			}
		}
	}

	// Order missing quests so prerequisites come before dependents (DFS postorder)
	const ordered: MissingQuest[] = [];
	const emitted = new Set<string>();
	const emit = (name: string, seen: Set<string>) => {
		if (seen.has(name)) {
			return;
		}
		seen.add(name);
		const entry = data.get(name);
		if (entry) {
			for (const req of entry.quests) {
				if (missing.has(req.name)) {
					emit(req.name, seen);
				}
			}
		}
		const m = missing.get(name);
		if (m && !emitted.has(name)) {
			emitted.add(name);
			ordered.push(m);
		}
	};
	emit(target, new Set());

	const skillGaps = [...skillSources.values()].sort((a, b) => b.need - a.need);

	return {
		target,
		targetState,
		// "Ready to start", not "done": targetState reports completion separately.
		ready: ordered.length === 0 && skillGaps.length === 0,
		missingQuests: ordered,
		skillGaps,
		otherRequirements: [...others.entries()].map(([skill, level]) => `${skill}: ${level}`),
		notes,
	};
}
