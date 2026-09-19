# runelite-mcp — OSRS LLM Companion

A RuneLite plugin that *is* an MCP server: it gives an MCP client (the Gielinor
app, Claude Code, any other) deep, live visibility into your Old School
RuneScape account — far beyond hiscores — for planning and progress tracking.

```
┌──────────────────────────┐   MCP over HTTP    ┌──────────────────┐
│ RuneLite + Gielinor      │  127.0.0.1:8765    │ Gielinor app     │
│ Companion plugin         │ ◄────────────────► │ (or any MCP      │
│  POST /mcp   MCP tools   │      /mcp          │  client)         │
│  GET  /state /snapshot … │                    │                  │
└──────────────────────────┘                    └────────┬─────────┘
                                                         │ enriches with
                                                         ▼
                                          OSRS wiki · GE prices · Wise Old Man
```

One thing to install: the plugin. It serves live account state as MCP tools —
skills, per-quest completion, diary tiers, combat-achievement tiers, slayer
task/points, boss killcounts, inventory, equipment, bank snapshot, collection
log. Reference data (wiki quest requirements, GE prices, WOM history) belongs to
the client app, which can update it without anyone reinstalling a plugin.

**This is read-only by design.** No clicking, no input simulation, no automation
— that would break Jagex's rules (and RuneLite's Plugin Hub explicitly rejects
"plugins exposing player info over HTTP", which is why this is a sideloaded
external plugin that will never be submitted to the hub).

---

## The MCP tools your client gets

Served by the plugin at `POST http://127.0.0.1:8765/mcp`.

| Tool | What it does |
|---|---|
| `client_status` | Is the client running and logged in, which account, ms since last tick. Cheap; call it first when a state read fails |
| `game_state` | The account in one call: skills (real/boosted/XP), quest points, every quest's completion state, diary tiers with per-tier task counts, combat achievement tier summary, slayer task/points/streak with decoded unlocks, boss killcounts, inventory, equipment, last bank snapshot, collection log counts. Takes a `sections` argument to fetch only part of it |
| `combat_achievements` | Every CA task (all 6 tiers, ~655) with per-task completion, decoded from the game's own task tables — no interface needed |
| `collection_log` | Aggregate counts plus the full tab/page/item catalog (~1,926 items); per-item state for pages viewed in game this session |
| `bank_snapshot` | Bank contents (id/name/qty) with the timestamp of when the bank was last open |

Equipment items also carry `slot` (`HEAD`, `CAPE`, `WEAPON`, …) so a client can
rebuild the worn loadout rather than just the set of owned items.

Transport: Streamable HTTP, request/response only. **Stateless** — no
`Mcp-Session-Id` is issued, so toggling the plugin or restarting the client
costs the app one re-`initialize`, never a stale-session error. `GET /mcp`
answers 405 (there is no SSE stream; nothing here is server-initiated), as the
spec permits. Tool results carry the same JSON twice — as `structuredContent` and
as a text block — so either kind of client can read them. Protocol versions
`2025-06-18`, `2025-03-26` and `2024-11-05` are accepted.

### The tool contract (source of truth)

Each tool is defined by one JSON file in
[`plugin/src/main/resources/mcp/tools/`](plugin/src/main/resources/mcp/tools) —
its `name`, `description`, `inputSchema` and `outputSchema`, as JSON Schema
(2020-12). The plugin loads those files at startup and serves them verbatim in
`tools/list`, so the schema a client validates against is the same document that
lives in this repo; the Java side supplies only the handler.

| File | Tool |
|---|---|
| `client_status.json` | liveness, login state, account name |
| `game_state.json` | the snapshot, plus every shared `$def` — skills, items, diary tiers, quest states |
| `combat_achievements.json` | per-task detail for all six tiers |
| `collection_log.json` | tab/page/item catalog |
| `bank_snapshot.json` | the bank container |

Every call returns the payload twice: as `structuredContent` (typed, validated
against `outputSchema`) and as a text block holding the same JSON. Consumers
should prefer `structuredContent`.

Two rules the schemas encode that are easy to get wrong:

- **Absent is not empty.** A container with `available: false` means the contents
  are *unknown* — the bank only exists client-side once opened, and a collection
  log item without `obtained` was never observed this session. Overwriting known
  data with these is how you lose a bank snapshot.
- **`loggedIn` gates everything else.** At the login screen the client still
  reports the last session's skill levels, so `playerState` only guarantees
  `capturedAt` and `loggedIn`.

Schemas are additive-friendly: `additionalProperties` is left open, so a new
field never breaks an existing client.

To check the plugin against its own contract — calls every advertised tool and
validates each result against the schema that same `tools/list` declared:

```
node scripts/validate-schemas.mjs          # needs the client running; ajv comes from server/
```

`gradlew test` covers the structural side (every `$ref` resolves, every
`required` names a declared property, the `game_state` sections enum matches the
snapshot the code actually builds).

No CORS headers are sent, deliberately: combined with the loopback bind and the
Host check they are the one thing that would let any web page you visit read
your account. A native client (no browser origin) is unaffected.

## Plain REST endpoints (127.0.0.1:8765, GET)

Kept alongside `/mcp` for curl-level debugging and non-MCP consumers:

`/health` `/state` `/quests` `/diaries` `/combat-achievements` `/slayer` `/kc` `/inventory` `/equipment` `/bank` `/collection-log` `/snapshot`

- `/combat-achievements` lists **every CA task** (all 6 tiers, ~655 tasks) with per-task completion, decoded from the game's own task tables — no interface needed.
- `/collection-log` serves the aggregate counts (unique obtained/total) plus the **full tab/page/item catalog** (~1,926 items). Per-item obtained state accumulates for pages you actually view in-game this session (the game only materializes it for the visible page) — browse your log to capture more.

Anything other than `GET`/`HEAD` on these is refused (`405 read-only server: GET
only`) — MCP lives at `/mcp` and is the only path that takes a POST.

## Distribution status

**The Plugin Hub will not take this plugin as it stands.** RuneLite's rejected
features list names the exact shape of it:

> Plugins which expose player information over HTTP.

That is this plugin's whole design, so a hub submission would be closed on
sight. Two consequences follow, and they bite harder than they look:

1. **Sideloading is the only channel**, and sideloaded plugins load only in
   developer mode — which the official and Jagex launchers deliberately refuse
   to enable. Anyone installing this has to launch the client from a script
   (`scriptsunelite-dev.cmd`). That is fine for you and for technical testers;
   it is not a channel you can put in front of ordinary players.
2. **Nothing here is malicious or rule-breaking** — it is read-only, runs no
   subprocesses, uses no reflection or JNI, vendors nothing at runtime, and makes
   no outbound connections at all. The rejection is about the *shape* of the
   integration, not its behaviour.

### The route to a hub listing

Invert the connection. A plugin that *listens* is rejected; a plugin that
*sends* to a service the player has opted into is ordinary — several hub plugins
sync to third-party sites today. Concretely: the plugin opens an outbound
connection to Gielinor and answers requests over it, rather than binding 8765
and waiting.

That is already the preferred option in Gielinor's own hosting question, so the
shippable design and the multi-user design are the same piece of work. Caveats
worth knowing before committing to it:

- A reviewer could still read a tunnelled request/response channel as the same
  thing wearing a coat. Worth asking in the PR before building it all.
- Plugins that talk to third-party servers must **warn the user what data is
  sent**, on the plugin or on the config option that enables it.
- The hub builds one repository from source at a pinned commit, with
  `build.gradle` at its root. This monorepo (plugin + server + scripts) would
  need the plugin split into its own repository.
- The hub requires code it can review end to end: no reflection, JNI,
  subprocesses, or runtime-vendored code. This plugin already complies.

### Shipping the sideload build today

Tag a release and CI publishes the jar plus a checksum:

```
git tag v0.1.0 && git push --tags
```

`.github/workflows/release.yml` builds on Java 11, runs the tests, refuses a tag
whose version does not match `plugin/build.gradle`, and attaches
`gielinor-companion-<version>.jar` and its `.sha256`.

Before a public release, note the plugin has **no authentication** — any local
process running as the user can read the account state while the client is up.
That is a reasonable trade for a personal tool and documented below, but it is a
different proposition once strangers install it.

## Setup

### 1. Plugin

Prereqs: JDK 11+ (Temurin). Daily development loop:

```
cd plugin
gradlew run          # boots a dev client with the plugin loaded
```

For playing with the sideloaded plugin (the official launcher cannot load sideloaded plugins):

1. Run the normal RuneLite launcher once (populates `%USERPROFILE%\.runelite\repository2`).
2. `cd plugin && gradlew jar`
3. `scripts\install-sideload.cmd` (copies the jar into `%USERPROFILE%\.runelite\sideloaded-plugins`)
4. Launch the client with `scripts\runelite-dev.cmd`
5. Enable **Gielinor Companion** in the plugin list; verify with `curl http://127.0.0.1:8765/health`

The port is configurable in the plugin's settings (restarts automatically on change).

#### Jagex account login (one-time capture)

The Jagex Launcher can never load sideloaded plugins (developer mode is disabled whenever any launcher spawns the client — this is deliberate and won't change). But RuneLite's developer wiki documents an official credential-capture flow so a directly-launched developer-mode client can log into a Jagex account:

1. Open the RuneLite launcher config: Start menu → **"RuneLite (configure)"** (or run `"%LOCALAPPDATA%\RuneLite\RuneLite.exe" --configure`). Needs launcher 2.6.3+.
2. Add `--insecure-write-credentials` to the **Client arguments** box and save.
3. Launch RuneLite through the **Jagex Launcher** once and log in normally. This writes your session credentials to `%USERPROFILE%\.runelite\credentials.properties`.
4. Remove the flag from the config (the captured file persists).

Now `scripts\runelite-dev.cmd` auto-logs into your Jagex account. If auto-login ever stops (expired session), re-add the flag and repeat step 3.

**Treat `credentials.properties` like a password** — it can log into your account without one. Delete it (or use "End sessions" in account settings on runescape.com) to revoke it. Classic username/password accounts don't need any of this — just log in on the client's own login screen.

Keep using the Jagex Launcher for normal play whenever you don't need the companion; the plugin is only active in developer-mode sessions.

### 2. Point your MCP client at the plugin

There is no second process to install — the plugin is the MCP server. Its
address is:

```
http://127.0.0.1:8765/mcp
```

**Gielinor app** — put that URL in the Address field. If you changed the plugin's
port in its RuneLite settings, change the URL to match.

**Claude Code**:

```
claude mcp add --transport http gielinor http://127.0.0.1:8765/mcp
```

**Any other client**: standard MCP Streamable HTTP, no auth, no session id.

Sanity check without a client — a successful handshake looks like this:

```
curl -s -X POST http://127.0.0.1:8765/mcp -H "Content-Type: application/json"   -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"gielinor-runelite","version":"0.1.0"}}}
```

If that comes back `405 {"error":"read-only server: GET only"}`, the client is
running an older build of the plugin that had no `/mcp` route — rebuild, run
`scripts\install-sideload.cmd`, and restart RuneLite.

Note: Claude Desktop's "custom connector" dialog is for *remote* MCP servers and
requires a public `https://` URL — it can never point at `http://127.0.0.1`.
Desktop needs a stdio server; see [The optional Node server](#the-optional-node-server).

#### Agent running inside WSL

WSL2 uses its own network namespace, so `127.0.0.1` inside WSL does not reach the Windows-side plugin. Two fixes:

**Preferred — mirrored networking** (WSL 2.0+, Windows 11): `%USERPROFILE%\.wslconfig` containing

```ini
[wsl2]
networkingMode=mirrored
```

then `wsl --shutdown` and start WSL again (closes everything running inside WSL). Linux and Windows now share loopback, so the default `127.0.0.1:8765` works from WSL as-is. Register the server inside WSL's own Claude config (`claude mcp add ...` within WSL).

**Fallback — NAT mode**: run an elevated portproxy plus a firewall rule, then point the server at the Windows host:

```bat
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=8765 connectaddress=127.0.0.1 connectport=8765
netsh advfirewall firewall add rule name="RuneLite MCP bridge (WSL)" dir=in action=allow protocol=TCP localport=8765 remoteip=172.16.0.0/12
```

then point the client at the Windows host instead of loopback:

```
http://<windows-host-ip>:8765/mcp        # from WSL: ip route show default
```

The plugin still only binds `127.0.0.1`; the portproxy forwards to it, and its Host check accepts the machine's own addresses (including the WSL vEthernet IP). Note this exposes port 8765 on your LAN unless you keep the firewall rule scoped to the WSL subnet as above.

### The optional Node server

`server/` is the original TypeScript MCP server: a stdio server that proxied the
plugin and enriched it with the OSRS wiki (structured quest requirements via
`Module:Questreq/data`), real-time GE prices, Wise Old Man history, and a
requirement solver. **Nothing needs it any more** — the plugin serves MCP itself,
and that enrichment belongs to the client app.

It is kept because it is the working reference for that logic while it moves
into Gielinor, and because a stdio server is still the only way into Claude
Desktop:

```
cd server && npm install && npm run build
node dist/index.js                       # stdio
node dist/index.js --http                # http://127.0.0.1:8766/mcp
```

Set `RUNELITE_BRIDGE_PORT` if you changed the plugin port. Delete the directory
once Gielinor owns the enrichment.

### Tests

```
cd plugin && gradlew test      # JSON-RPC dispatch, HTTP transport, tool schemas, diary decoding (23 tests)
node scripts/validate-schemas.mjs   # live payloads vs. the schemas the plugin serves
cd server && npm test          # Lua parser + requirement solver unit tests
```

## Honest limitations

- **Bank** contents only exist client-side after you open the bank once per session; the snapshot is timestamped so the assistant knows how fresh it is (and can ask you to reopen the bank).
- **Collection log** per-item obtained state only exists for pages the game has rendered — the plugin captures each page as you view it (session-scoped) and always serves the full item catalog plus your aggregate counts. Cross-session persistence and collectionlog.net sync are not implemented.
- **Diary tiers** read three varbit families per tier under the game's own names: `*_DIARY_<TIER>_COMPLETE` (1 = finished), `*_<TIER>_COUNT` (tasks done so far) and `*_<TIER>_REWARD` (reward claimed), plus `STARTED_*_DIARY` per region. Karamja predates the system and uses the old `ATJUN_*` varbits for easy/medium/hard. The **task total per tier is not stored client-side**, so `tasksComplete` is a bare count — pair it with wiki data to render "7 of 10".
- **Slayer unlock names** are decoded from the reward table using the bit→varp packing rule (bits 0–31 in varp 1076, 32–63 in varp 1344 — the same pattern the game uses for CA tasks). Cross-check once against your in-game slayer rewards screen.
- **Quest points** are read from `VarPlayer.QUEST_POINTS` and served as `state.questPoints`; kudos from varbit 3637.
- **Reference data is the client app's job.** The plugin serves what the game client knows and nothing else — no wiki, GE prices, or WOM. Whatever consumes it owes those APIs the usual etiquette: a descriptive `User-Agent` (required by prices.runescape.wiki), cached responses, and ≥1h between WOM player updates.
- The plugin has no authentication (localhost only, Host-header checked, no CORS) — any local process under your user can read game state while it runs.

## Not implemented yet (ideas)

DPS calculator (the wiki's open-source calc is GPL-3.0 TypeScript — vendorable in an isolated module), community search (Reddit/YouTube, needs API keys), per-tick event buffers, screenshots, loot/xp session tracking, collection log cross-session persistence.
