# Contributing

## Versioning: label the PR

This repo releases on merge. Every PR needs **exactly one** of these labels, and
the label decides the version bump:

| Label | Bump | Use it for |
|---|---|---|
| `patch` | 1.4.2 → 1.4.3 | Fixes, docs, internals — nothing a consumer has to react to |
| `minor` | 1.4.2 → 1.5.0 | New tools, new fields, new optional arguments |
| `major` | 1.4.2 → 2.0.0 | Anything that breaks an existing client (see below) |

CI fails a PR with no version label, or with more than one. That check is there
because merging is what cuts the release: an unlabelled merge ships nothing, and
by then the PR is closed and the mistake is awkward to undo.

**What counts as a breaking change here** is whatever breaks a client reading the
MCP tools: removing or renaming a tool, a field, or an enum value; narrowing a
type; making an optional input required. Adding a field is *not* breaking — the
output schemas leave `additionalProperties` open precisely so it isn't.

Write the PR title as a [Conventional Commit](https://www.conventionalcommits.org/)
(`fix: keep the bank snapshot across world hops`). Merges are squashed, so the
title becomes the commit subject, and that is what the release notes and
CHANGELOG are generated from. A title that doesn't parse still releases — it just
won't appear in the notes.

## What happens on merge

`.github/workflows/release.yml` runs [semantic-release](https://semantic-release.gitbook.io/),
which:

1. reads the release type from the merged PR's label
   (`release/label-release-type.mjs` is the only custom piece — semantic-release
   reads commit messages by default, and this maps our label convention onto it);
2. writes the new version into `plugin/build.gradle`, and into
   `server/package.json`;
3. runs `release/build-jar.sh`, which builds and tests the plugin at that version
   and checksums the jar;
4. commits the bump back to `main`, tags it `v<version>`;
5. publishes the GitHub release with `runelite-mcp-server-plugin-<version>.jar`
   and its `.sha256` attached.

**The version has to reach the wire, not just the tag.** `plugin/build.gradle` is
the single source of it: the build stamps it into `build-info.properties`, and the
plugin reports it as its MCP `serverInfo.version` and as `client_status.version`.
A client compares that against the release it expects and prompts for a restart
when they differ — so a hardcoded copy would ask for a restart that no update
ever satisfies. `BuildInfoTest` pins that chain; don't route around it.

If a release run fails partway, or a PR got merged without a label, re-run the
release by hand: **Actions → release → Run workflow**, and pick the bump.

## Running the checks locally

```
cd plugin  && ./gradlew test            # 50 tests: JSON-RPC, transport, schemas, diaries, farming, version
cd server  && npm ci && npm run typecheck && npm test
node scripts/validate-schemas.mjs       # live payloads vs. served schemas; needs the client running
```

`npm run release:dry` at the repo root dry-runs semantic-release. It needs to be
on `main` with the remote reachable, since semantic-release resolves release
branches against the remote.

## House style

- **Java**: tabs, Allman braces, RuneLite's own conventions. Java 11 — the client
  runs on it and `build.gradle` pins `release 11`.
- **TypeScript**: tabs, `strict` on.
- Comments explain *why*, especially where the game's own data model is the
  reason something looks odd. The existing code is the reference for the level of
  detail; match it rather than adding a running commentary.
- Tool contracts live in `plugin/src/main/resources/mcp/tools/*.json` and are
  served verbatim. Change the schema and the provider in the same commit —
  `gradlew test` checks their structure, and `scripts/validate-schemas.mjs`
  checks live payloads against them.
- **This plugin is read-only, and that is not negotiable.** No input, no menu
  actions, no writes, no automation. A PR that clicks anything in game will be
  closed.
