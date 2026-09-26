# Changelog

Every release is cut by semantic-release from a merged PR's version label. See CONTRIBUTING.md.

## [0.2.0](https://github.com/wilderness-cloud/runelite-mcp-server-plugin/compare/v0.1.0...v0.2.0) (2026-09-26)

### ⚠ BREAKING CHANGES

* the config group is now runelitemcpserver, so a saved HTTP port
resets to the 8765 default. The jar filename and the MCP serverInfo.name both
change, and any client pinned to the old name will not match.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>

### Features

* rename to runelite-mcp-server-plugin and prepare for release ([43a7500](https://github.com/wilderness-cloud/runelite-mcp-server-plugin/commit/43a75003e6872e66fdd381941c6436a3b087fbc1))

### Fixes

* **release:** install the notes preset and cover prepare in CI ([798efec](https://github.com/wilderness-cloud/runelite-mcp-server-plugin/commit/798efec135ff66c3bca2ad7101879472a1652b8b))

### Build and release

* fix the two failures in the first run on main ([757aeab](https://github.com/wilderness-cloud/runelite-mcp-server-plugin/commit/757aeabde0c0631d786f923bb85ae950a9210f92))
