/**
 * semantic-release plugin: take the release type from the merged PR's label
 * instead of from commit messages.
 *
 * semantic-release is commit-driven by default (@semantic-release/commit-analyzer
 * reads Conventional Commits). This project versions by PR label instead — every
 * PR carries exactly one of `major`, `minor`, `patch` — so this is the one shim
 * that maps that convention onto the release pipeline. Everything downstream
 * (notes, tag, CHANGELOG, the GitHub release, the version written into
 * plugin/build.gradle and server/package.json) is off-the-shelf semantic-release.
 *
 * The workflow passes the label through RELEASE_TYPE. No label means no release,
 * which is deliberately not an error: a merge that skipped the label check should
 * leave main alone rather than fail after the fact. `.github/workflows/ci.yml`
 * is where a missing label is caught, while the PR is still open and fixable.
 */
const VALID = new Set(["major", "minor", "patch"]);

export async function analyzeCommits(_pluginConfig, context) {
	const { logger } = context;
	const requested = (process.env.RELEASE_TYPE ?? "").trim().toLowerCase();

	if (!requested) {
		logger.log("RELEASE_TYPE is empty: no version label on the merged PR, so no release.");
		return null;
	}

	if (!VALID.has(requested)) {
		throw new Error(
			`RELEASE_TYPE="${requested}" is not a release type. Label the PR major, minor or patch.`,
		);
	}

	logger.log(`Release type "${requested}", from the merged PR's label.`);
	return requested;
}
