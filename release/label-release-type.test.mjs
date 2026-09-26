import assert from "node:assert/strict";
import test from "node:test";
import { analyzeCommits } from "./label-release-type.mjs";

/**
 * This shim decides what every release is, so its edge cases are worth pinning:
 * it is the difference between a patch and a major, and between a quiet no-op and
 * a failed release run.
 */
const context = { logger: { log() {} } };

function withReleaseType(value, fn) {
	const had = Object.prototype.hasOwnProperty.call(process.env, "RELEASE_TYPE");
	const previous = process.env.RELEASE_TYPE;
	if (value === undefined) {
		delete process.env.RELEASE_TYPE;
	} else {
		process.env.RELEASE_TYPE = value;
	}
	try {
		return fn();
	} finally {
		if (had) {
			process.env.RELEASE_TYPE = previous;
		} else {
			delete process.env.RELEASE_TYPE;
		}
	}
}

for (const type of ["major", "minor", "patch"]) {
	test(`"${type}" is passed through as the release type`, async () => {
		const result = await withReleaseType(type, () => analyzeCommits({}, context));
		assert.equal(result, type);
	});
}

test("the label is normalised for case and stray whitespace", async () => {
	const result = await withReleaseType("  Minor \n", () => analyzeCommits({}, context));
	assert.equal(result, "minor");
});

// No label must mean "no release", not a failed run: by the time this executes
// the PR is already merged, so failing would only paint main red over something
// that can no longer be fixed on that PR.
test("an unset RELEASE_TYPE releases nothing", async () => {
	const result = await withReleaseType(undefined, () => analyzeCommits({}, context));
	assert.equal(result, null);
});

test("an empty RELEASE_TYPE releases nothing", async () => {
	const result = await withReleaseType("   ", () => analyzeCommits({}, context));
	assert.equal(result, null);
});

// A wrong label is a different case from a missing one: it means someone meant
// to release and the label does not say what, so guessing would be worse.
test("an unrecognised label fails loudly", async () => {
	await assert.rejects(
		() => withReleaseType("moinor", () => analyzeCommits({}, context)),
		/not a release type/,
	);
});

test("a semver string is not mistaken for a release type", async () => {
	await assert.rejects(
		() => withReleaseType("1.2.0", () => analyzeCommits({}, context)),
		/not a release type/,
	);
});
