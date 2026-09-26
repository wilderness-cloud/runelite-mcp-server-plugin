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
