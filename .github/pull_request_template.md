<!--
Label this PR `major`, `minor` or `patch` — exactly one. Merging cuts the
release, and the label picks the version bump. CI fails without it.
See CONTRIBUTING.md.

Title it as a Conventional Commit, e.g. "fix: keep the bank snapshot across
world hops". Merges are squashed, so the title becomes the release note.
-->

## What this changes

## Why

## Checked

- [ ] `cd plugin && ./gradlew test`
- [ ] `cd server && npm run typecheck && npm test` (if `server/` changed)
- [ ] `node scripts/validate-schemas.mjs` against a running client (if a tool schema or provider changed)
- [ ] Still read-only: no input, menu actions, or writes to the game
