#!/usr/bin/env node
/*
 * Validates the release configuration without running a release.
 *
 * `semantic-release --dry-run` would be the obvious check, except that it calls
 * verifyAuth unconditionally — a real `git push --dry-run` — even in dry-run
 * mode. That needs a write-capable token, which a job running on pull requests
 * must not have. So this checks the things that actually break instead: the
 * config parsing, every plugin resolving, and the local shim behaving.
 *
 *   node release/verify-config.mjs
 */
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { pathToFileURL } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(join(REPO, "package.json"));

let failures = 0;
const fail = (message) => {
	console.error(`FAIL ${message}`);
	failures++;
};
const ok = (message) => console.log(`ok   ${message}`);

const config = JSON.parse(readFileSync(join(REPO, ".releaserc.json"), "utf8"));
ok(".releaserc.json parses");

if (config.tagFormat !== "v${version}") {
	fail(`tagFormat is ${JSON.stringify(config.tagFormat)}; releases must be tagged v<version>`);
} else {
	ok("tagFormat tags releases as v<version>");
}

if (!Array.isArray(config.branches) || !config.branches.includes("main")) {
	fail(`branches is ${JSON.stringify(config.branches)}; releases must come from main`);
} else {
	ok("releases come from main");
}

// Every plugin has to resolve, or the release fails at merge with the config
// already reviewed and the PR already closed.
for (const entry of config.plugins ?? []) {
	const name = Array.isArray(entry) ? entry[0] : entry;
	try {
		if (name.startsWith(".")) {
			await import(pathToFileURL(join(REPO, name)).href);
		} else {
			require.resolve(name);
		}
		ok(`plugin resolves: ${name}`);
	} catch (error) {
		fail(`plugin does not resolve: ${name} (${error.message})`);
	}
}

// A `preset` names a package that release-notes-generator does NOT bundle — it
// ships only conventional-changelog-angular — and it is loaded lazily, during
// generateNotes. So a missing preset is invisible until a release is already
// half done: the version is computed, then it dies with MODULE_NOT_FOUND. Run
// the step for real, against synthetic commits so this stays independent of
// whatever history the branch happens to have.
for (const entry of config.plugins ?? []) {
	if (!Array.isArray(entry) || entry[0] !== "@semantic-release/release-notes-generator") {
		continue;
	}
	const pluginConfig = entry[1] ?? {};
	if (pluginConfig.preset) {
		const presetPackage = `conventional-changelog-${String(pluginConfig.preset).toLowerCase()}`;
		try {
			// import.meta.resolve, not require.resolve: these presets are ESM-only,
			// so a require resolution fails on a package that is perfectly present.
			import.meta.resolve(presetPackage);
			ok(`notes preset is installed: ${presetPackage}`);
		} catch {
			fail(`notes preset "${pluginConfig.preset}" needs ${presetPackage}, which is not installed.`
				+ ` release-notes-generator bundles only conventional-changelog-angular, so add it`
				+ ` to devDependencies or the release dies at generateNotes.`);
		}
	}

	try {
		const { generateNotes } = await import("@semantic-release/release-notes-generator");
		const notes = await generateNotes(pluginConfig, {
			cwd: REPO,
			options: { repositoryUrl: "https://github.com/wilderness-cloud/runelite-mcp-server-plugin" },
			lastRelease: { version: "0.0.0", gitTag: "v0.0.0" },
			nextRelease: { version: "0.0.1", gitTag: "v0.0.1", type: "patch" },
			commits: [
				{ hash: "0".repeat(40), message: "fix: a fix\n", subject: "a fix", body: "", committerDate: new Date() },
				{ hash: "1".repeat(40), message: "ci: a pipeline change\n", subject: "a pipeline change", body: "", committerDate: new Date() },
			],
			logger: { log() {}, error() {}, warn() {} },
		});
		if (typeof notes !== "string") {
			fail("generateNotes did not return notes");
		} else {
			ok("generateNotes renders with the configured preset");
			// The types mapping exists so that ci/chore/docs commits appear at all;
			// the angular default drops them, which is a silently empty changelog.
			if (pluginConfig.presetConfig?.types && !/pipeline change/.test(notes)) {
				fail("the configured presetConfig.types is not taking effect:"
					+ " a ci: commit did not appear in the rendered notes");
			} else if (pluginConfig.presetConfig?.types) {
				ok("presetConfig.types keeps non-feat/fix commits in the notes");
			}
		}
	} catch (error) {
		fail(`generateNotes failed: ${error.message}`);
	}
}

// The shim is what turns a PR label into a release type; without analyzeCommits
// semantic-release would silently fall through to commit-message analysis.
try {
	const shim = await import(pathToFileURL(join(REPO, "release/label-release-type.mjs")).href);
	if (typeof shim.analyzeCommits !== "function") {
		fail("release/label-release-type.mjs does not export analyzeCommits");
	} else {
		ok("the label shim exports analyzeCommits");
	}
} catch (error) {
	fail(`cannot load the label shim (${error.message})`);
}

// The jar the release attaches is built by this; a syntax error in it would only
// surface after the version had already been written and committed.
const jarScript = join(REPO, "release", "build-jar.sh");
const { spawnSync } = await import("node:child_process");
const syntax = spawnSync("bash", ["-n", jarScript], { encoding: "utf8" });
if (syntax.error) {
	console.log(`skip build-jar.sh syntax check (no bash: ${syntax.error.message})`);
} else if (syntax.status !== 0) {
	fail(`release/build-jar.sh has a syntax error: ${syntax.stderr.trim()}`);
} else {
	ok("release/build-jar.sh parses");
}

// The release asset must be named runelite-mcp-server-plugin-<version>.jar, which
// only holds while the Gradle project keeps that name.
const settings = readFileSync(join(REPO, "plugin", "settings.gradle"), "utf8");
if (!/rootProject\.name\s*=\s*'runelite-mcp-server-plugin'/.test(settings)) {
	fail("plugin/settings.gradle no longer names the project runelite-mcp-server-plugin,"
		+ " so the release asset filename would change");
} else {
	ok("the Gradle project name matches the release asset filename");
}

console.log(failures === 0 ? "\nrelease config is valid" : `\n${failures} problem(s)`);
process.exit(failures === 0 ? 0 : 1);
