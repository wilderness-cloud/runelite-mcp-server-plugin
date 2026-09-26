# runelite-mcp-server-plugin

A RuneLite plugin that **is** an MCP server. It gives any MCP client — Claude
Code, a desktop assistant, something you wrote yourself — deep, live visibility
into your Old School RuneScape account: far more than the hiscores expose, and
exact rather than inferred.

```
┌───────────────────────────────┐   MCP over HTTP   ┌──────────────────┐
│ RuneLite                      │  127.0.0.1:8765   │ Any MCP client   │
│  + RuneLite MCP Server plugin │ ◄───────────────► │                  │
│                               │       /mcp        │                  │
│  POST /mcp     MCP tools      │                   └────────┬─────────┘
│  GET  /state /snapshot …      │                            │ enriches with
└───────────────────────────────┘                            ▼
                                              OSRS wiki · GE prices · Wise Old Man
```

There is one thing to install: the plugin. It serves live account state as MCP
tools — skills, per-quest completion, diary tiers, combat achievements, slayer
task and points, boss killcounts, inventory, equipment, bank snapshot,
collection log, farming patches, Grand Exchange offers. Reference data (wiki
quest requirements, market prices, historical gains) is deliberately *not* here;
that belongs to whatever consumes this, which can update it without anyone
reinstalling a plugin.

**This is read-only by design.** It cannot click, move, or change anything in
game. No input simulation, no automation, no writes of any kind — that would
break Jagex's rules, and it is not what this is for.

> [!IMPORTANT]
> The plugin has **no authentication**. While RuneLite is running with it
> enabled, any process on your machine running as you can read your account
> state. It binds loopback only, checks the `Host` header, and sends no CORS
> headers, so a web page you visit cannot reach it — but a local program can.
> See [Security model](#security-model).

---

## The MCP tools

Served at `POST http://127.0.0.1:8765/mcp`.

| Tool | What it answers |
|---|---|
| `client_status` | Is the client running and logged in, which account, ms since the last tick, and which build of the plugin is running (`version`, `build` = short git SHA, `builtAt`). Cheap — call it first when a state read fails, and to confirm an update actually took |
| `game_state` | The account in one call: skills (real/boosted/XP), quest points, every quest's state, diary tiers with per-tier task counts, combat achievement summary, slayer task/points/streak with decoded unlocks, boss killcounts, inventory, equipment, last bank snapshot, collection log counts, Kourend favour, minigame reward points, and charges on charged gear. Takes a `sections` argument to fetch only part of it |
| `combat_achievements` | Every CA task (all six tiers, ~655) with per-task completion, decoded from the game's own task tables — no interface needed |
| `collection_log` | Aggregate counts plus the full tab/page/item catalog (~1,926 items); per-item state for pages viewed in game this session |
| `bank_snapshot` | Bank contents (id/name/qty/slot) with tab layout, and when the bank was last open |
| `find_item` | "Do I have this, and where?" — searches bank, inventory and equipment by name or id, returning only matches, with tab or slot |
| `farming_state` | "What needs doing on my farm run, and when?" — every patch you have visited, with crop, state (ready / growing / diseased / dead) and finish time, ready first, plus the bird house cycle |
| `grand_exchange` | Your eight GE slots, and how much of each item the plugin has watched you buy inside the four-hour buy-limit window |

Equipment items carry `slot` (`HEAD`, `CAPE`, `WEAPON`, …), so a client can
rebuild the worn loadout rather than just the set of owned items.

Transport: Streamable HTTP, request/response only. **Stateless** — no
`Mcp-Session-Id` is issued, so toggling the plugin or restarting the client costs
a client one re-`initialize`, never a stale-session error. `GET /mcp` answers 405
(there is no SSE stream; nothing here is server-initiated), as the spec permits.
Protocol versions `2025-06-18`, `2025-03-26` and `2024-11-05` are accepted.

### The tool contract is the source of truth

Each tool is one JSON file in
[`plugin/src/main/resources/mcp/tools/`](plugin/src/main/resources/mcp/tools) —
its `name`, `description`, `inputSchema` and `outputSchema`, as JSON Schema
(2020-12). The plugin loads those at startup and serves them verbatim in
`tools/list`, so the schema a client validates against is the same document that
lives in this repo. The Java side supplies only the handler.

Every call returns its payload twice: as `structuredContent` (typed, matching
`outputSchema`) and as a text block holding the same JSON, so either kind of
client can read it. Prefer `structuredContent`.

Two rules the schemas encode that are easy to get wrong:

- **Absent is not empty.** A container with `available: false` means its contents
  are *unknown* — the bank only exists client-side once opened, and a collection
  log item with no `obtained` was never observed this session. Overwriting known
  data with one of these is how you lose a bank snapshot.
- **`loggedIn` gates everything else.** At the login screen the client still
  reports the previous session's skill levels, so `playerState` only guarantees
  `capturedAt` and `loggedIn`.

Schemas are additive-friendly: `additionalProperties` stays open, so a new field
never breaks an existing client. That is also why adding one is a `minor` release
and not a `major` — see [CONTRIBUTING.md](CONTRIBUTING.md).

To check the plugin against its own contract — calls every advertised tool and
validates each result against the schema that same `tools/list` declared:

```
node scripts/validate-schemas.mjs        # needs the client running; ajv comes from server/
```

### Versions

The plugin reports its release as `client_status.version` and as the MCP
`serverInfo.version` from `initialize`. That is the same version as the release
tag (`v0.2.0` ships `0.2.0`), so a client can compare what it is talking to
against the release it expects. `build` and `builtAt` identify a specific jar
within one release, which is what you want mid-deploy: the version string does
not move between builds, so on its own it cannot tell a fresh sideload from a
stale one.

### Where the farming tables come from

`farming_state` needs to know every patch in the game and how each patch's varbit
encodes produce, crop state and growth stage. RuneLite's own Time Tracking plugin
knows all of it — but `FarmingWorld`, `FarmingPatch`, `PatchImplementation` and
`Produce` are **package-private**, so a plugin outside that package cannot name
them, and reflection is ruled out (see [Distribution](#distribution)).
Reimplementing ~100 patches and 23 decode tables by hand would be large and
quietly wrong in places nobody would notice.

So they are lifted mechanically instead:

```
node scripts/gen-farming-data.mjs [--tag runelite-parent-1.12.39]
```

which parses RuneLite's source at a pinned tag into
`plugin/src/main/resources/farming/farming-data.json` (95 produce, 555 varbit
ranges, 43 regions, 107 patches). Re-run it when the game gains patches or crops,
then bump the tag. `FarmingProvider` ports `predictPatch`/`getTickTime` on top of
that table — learned farm tick offset, leagues tick rate and autoweed included.
Attribution for the derived data is in [`NOTICE`](NOTICE).

## Plain REST endpoints

Kept alongside `/mcp` for curl-level debugging and non-MCP consumers. All `GET`,
all on `127.0.0.1:8765`:

`/health` `/state` `/quests` `/diaries` `/combat-achievements` `/slayer` `/kc`
`/inventory` `/equipment` `/bank` `/collection-log` `/farming` `/birdhouses`
`/activities` `/charges` `/grand-exchange` `/snapshot`

Anything other than `GET`/`HEAD` on these is refused (`405 read-only server: GET
only`). MCP lives at `/mcp` and is the only path that takes a POST.

## Setup

### 1. Install the plugin

Prereqs: JDK 11+ (Temurin).

Grab `runelite-mcp-server-plugin-<version>.jar` from
[Releases](https://github.com/wilderness-cloud/runelite-mcp-server-plugin/releases),
or build it yourself with `cd plugin && ./gradlew jar`. Verify a downloaded jar
against its `.sha256`:

```
sha256sum -c runelite-mcp-server-plugin-<version>.jar.sha256
```

Then:

1. Run the normal RuneLite launcher once, to populate `%USERPROFILE%\.runelite\repository2`.
2. Copy the jar into `%USERPROFILE%\.runelite\sideloaded-plugins`
   (`scripts\install-sideload.cmd` does this from a local build, clearing older copies first).
3. Launch the client with `scripts\runelite-dev.cmd`.
4. Enable **RuneLite MCP Server** in the plugin list.
5. Check it: `curl http://127.0.0.1:8765/health`

The port is configurable in the plugin's settings; the server restarts on change.

Sideloaded plugins load **only in developer mode**, which the official and Jagex
launchers deliberately refuse to enable — hence the launch script. For
development, `cd plugin && ./gradlew run` boots a dev client with the plugin
already loaded.

#### Jagex account login (one-time)

The Jagex Launcher can never load sideloaded plugins: developer mode is disabled
whenever a launcher spawns the client, deliberately. RuneLite's developer
documentation covers an official credential-capture flow so a directly-launched
developer-mode client can log into a Jagex account:

1. Open the RuneLite launcher config — Start menu → **RuneLite (configure)**, or
   `"%LOCALAPPDATA%\RuneLite\RuneLite.exe" --configure`. Needs launcher 2.6.3+.
2. Add `--insecure-write-credentials` to **Client arguments** and save.
3. Launch RuneLite through the Jagex Launcher once and log in normally. This
   writes your session credentials to `%USERPROFILE%\.runelite\credentials.properties`.
4. Remove the flag again. The captured file persists.

`scripts\runelite-dev.cmd` then auto-logs in. If it stops working the session has
expired; repeat step 3.

> [!WARNING]
> **Treat `credentials.properties` like a password** — it can log into your
> account without one. Delete it, or use "End sessions" in your account settings
> on runescape.com, to revoke it.

Accounts with a classic username and password need none of this — just log in on
the client's own login screen. And keep using the Jagex Launcher for normal play;
the plugin is only active in developer-mode sessions.

### 2. Point your MCP client at it

There is no second process to install. The address is:

```
http://127.0.0.1:8765/mcp
```

**Claude Code**:

```
claude mcp add --transport http runelite http://127.0.0.1:8765/mcp
```

**Any other client**: standard MCP Streamable HTTP, no auth, no session id. If
you changed the plugin's port, change the URL to match.

Sanity check without a client — a successful handshake looks like this:

```
curl -s -X POST http://127.0.0.1:8765/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"runelite-mcp-server","version":"0.1.0"}}}
```

If that comes back `405 {"error":"read-only server: GET only"}`, the client is
running an older jar with no `/mcp` route — reinstall and restart RuneLite.

Note that Claude Desktop's "custom connector" dialog is for *remote* MCP servers
and needs a public `https://` URL, so it can never point at `http://127.0.0.1`.
Desktop needs a stdio server; see [the companion server](#the-companion-server-optional).

#### Agent running inside WSL

WSL2 has its own network namespace, so `127.0.0.1` inside WSL does not reach the
Windows-side plugin. Two fixes.

**Preferred — mirrored networking** (WSL 2.0+, Windows 11). Put this in
`%USERPROFILE%\.wslconfig`:

```ini
[wsl2]
networkingMode=mirrored
```

then `wsl --shutdown` and start WSL again (this closes everything running inside
WSL). Linux and Windows now share loopback, so the default `127.0.0.1:8765` works
from WSL as-is.

**Fallback — NAT mode.** Run an elevated portproxy plus a firewall rule:

```bat
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=8765 connectaddress=127.0.0.1 connectport=8765
netsh advfirewall firewall add rule name="RuneLite MCP (WSL)" dir=in action=allow protocol=TCP localport=8765 remoteip=172.16.0.0/12
```

then point the client at the Windows host instead of loopback (`ip route show
default` from WSL gives you the address). The plugin still binds `127.0.0.1` only;
the portproxy forwards to it, and its `Host` check accepts the machine's own
addresses, including the WSL vEthernet IP. **This exposes port 8765 on your LAN**
unless you keep the firewall rule scoped to the WSL subnet as above.

## The companion server (optional)

`server/` is a small TypeScript MCP server that proxies the plugin and adds the
market and history data the game client cannot know. Nothing *needs* it to read
game state — the plugin serves MCP itself — but a stdio server is the only way
into clients that cannot take a URL, Claude Desktop among them.

```
cd server && npm ci && npm run build
node dist/index.js                       # stdio
node dist/index.js --http                # http://127.0.0.1:8766/mcp
```

Set `RUNELITE_BRIDGE_PORT` if you changed the plugin's port, and
`RUNELITE_BRIDGE_HOST` if it isn't on loopback from the server's point of view.

| Tool | What it adds |
|---|---|
| `ge_price`, `item_info` | Spot prices, and the **buy limit joined to observed usage**: the cap from the GE mapping, against what the plugin has watched you spend |
| `ge_history` | Price history over 24h / 7d / 30d / 1y with a summary — change across the window, min/max, volume per day |
| `quest_info`, `solve_requirements` | Quest requirements, and the exact gap between them and live account state |
| `game_state`, `bank_snapshot`, `progress_delta` | Plugin passthrough, bank valuation, and Wise Old Man gains |

**No wiki browsing here.** Article text, infoboxes and drop tables are a client's
job. The only wiki call left is `questdata.ts` reading `Module:Questreq/data`,
which is a machine-readable requirements table rather than an article.

If you build on the wiki, prices or Wise Old Man APIs yourself, owe them the
usual etiquette: a descriptive `User-Agent` (prices.runescape.wiki requires one
and blocks generic agents), cached responses, and at least an hour between Wise
Old Man updates for a given player.

## Security model

- **Loopback only.** The socket binds `127.0.0.1`; it is never reachable from
  your network unless you forward it yourself.
- **`Host` header checked**, as DNS-rebinding hardening. The machine's own
  interface addresses are accepted too, so a WSL-side agent can reach it through
  a portproxy.
- **No CORS headers, deliberately.** Combined with the loopback bind and the Host
  check, they are the one thing that would let any web page you visit read your
  account. A native client has no browser origin and is unaffected.
- **No authentication.** Any local process running as you can read game state
  while the client is up. That is a reasonable trade for a local tool on a
  single-user machine, and it is the thing to know before installing this.
- **Read-only, and inert beyond that.** No input or menu actions, no
  subprocesses, no reflection, no JNI, nothing vendored at runtime, and no
  outbound connections at all — the plugin only answers.
- Request bodies are capped at 1 MiB, and every client read is marshalled onto
  the client thread with a 10s timeout, so a slow or wedged client cannot pile up
  HTTP workers.

Found something? Open an issue — or for anything you would rather not file in
public, say so in an issue without details and we will take it from there.

## Distribution

**The RuneLite Plugin Hub will not take this plugin as it stands.** Its rejected
features list names exactly this shape:

> Plugins which expose player information over HTTP.

That is the whole design, so a hub submission would be closed on sight. Two
consequences:

1. **Sideloading is the only channel**, and sideloaded plugins load only in
   developer mode, which the official and Jagex launchers refuse to enable. Every
   install therefore needs a launch script. That is fine for technical users; it
   is not a channel to put in front of everyone.
2. **Nothing here is malicious or rule-breaking.** It is read-only, runs no
   subprocesses, uses no reflection or JNI, vendors nothing at runtime, and makes
   no outbound connections. The rejection is about the *shape* of the
   integration, not its behaviour.

The route to a hub listing, if anyone wants it, is to invert the connection: a
plugin that *listens* is rejected, while one that *sends* to a service the player
opted into is ordinary — several hub plugins sync to third-party sites today.
That would mean the plugin opening an outbound connection and answering requests
over it, rather than binding a port and waiting. Worth knowing before committing
to that work: a reviewer could still read a tunnelled request/response channel as
the same thing wearing a coat; plugins that talk to third-party servers must warn
the user what data is sent; and the hub builds one repository from source with
`build.gradle` at its root, so `plugin/` would need to be its own repository.

## Releases

Releases are cut automatically when a labelled PR is merged to `main`. The
label — `major`, `minor` or `patch` — picks the bump; semantic-release writes the
version, tags `v<version>`, and attaches the jar and its checksum.
[CONTRIBUTING.md](CONTRIBUTING.md) has the details.

## Tests

```
cd plugin && ./gradlew test      # 50 tests: JSON-RPC dispatch, HTTP transport, tool schemas, diary decoding, farming tables, version plumbing
cd server && npm ci && npm run typecheck && npm test
node scripts/validate-schemas.mjs   # live payloads vs. the schemas the plugin serves
```

## Known limitations

These are properties of what the game client actually knows, not things waiting
to be fixed.

- **Bank** contents only exist client-side after you open the bank once per
  session. The snapshot is timestamped so a client can tell how fresh it is, and
  ask you to reopen the bank.
- **Collection log** per-item state only exists for pages the game has rendered.
  The plugin captures each page as you view it (session-scoped) and always serves
  the full catalog plus your aggregate counts. Cross-session persistence and
  collectionlog.net sync are not implemented.
- **Diary task totals are not stored client-side**, so `tasksComplete` is a bare
  count — pair it with wiki data to render "7 of 10". Karamja predates the
  system and uses the legacy `ATJUN_*` varbits for easy/medium/hard, where 1
  means in progress and 2 means complete; every other region completes at 1.
- **Slayer unlock names** are decoded from the game's reward table using the
  bit→varp packing rule (bits 0–31 in varp 1076, 32–63 in varp 1344). Worth
  cross-checking once against your in-game rewards screen.
- **Farming patches are as fresh as your last visit.** The game only reveals a
  patch while you are in its region, so `farming_state` reads RuneLite's Time
  Tracking observations and predicts growth forward from them — exact, because
  farming ticks are wall-clock, but a patch changed by other means reads stale
  until you next walk past it. Patches you have never visited are omitted rather
  than reported empty, and the tool needs RuneLite's **Time Tracking** plugin
  enabled (it is, by default).
- **Buy-limit usage is a floor, never the truth.** Jagex expose neither the
  per-item limit nor an account's usage of it, so `grand_exchange` counts only
  offers this plugin watched fill. Buys made before it started, while it was off,
  on another device, or already filled when an offer was first seen are invisible.
  The limit itself comes from the GE mapping on the client side, which is why
  `ge_price` and `item_info` join the two halves.
- **Item charges arrive three different ways.** A count in the item name (`Ring
  of wealth (4)`, `Prayer potion(4)`) is broken out as a `charges` field on the
  item itself. A count in a varbit (Xeric's talisman, tridents, tomes, crystal
  gear) is exact and lives in the `charges` section. The rest — dodgy necklace,
  ring of forging, amulet of chemistry — have no counter the client can read, so
  they come from RuneLite's **Item Charges** plugin counting chat messages; those
  are marked `source: "runelite"` and drift if the item was used with that plugin
  off. A varbit reading zero means "uncharged **or** never owned", so zeroes are
  omitted.
- **Reference data is not the plugin's job.** It serves what the game client
  knows and nothing else — no wiki, prices, or Wise Old Man.

## Not implemented (ideas)

DPS calculator (the wiki's open-source calc is GPL-3.0 TypeScript — vendorable in
an isolated module), community search (Reddit/YouTube, needs API keys), per-tick
event buffers, screenshots, loot and XP session tracking, collection log
cross-session persistence.

## Licence

[BSD 2-Clause](LICENSE). Farming reference data is derived from RuneLite, also
BSD 2-Clause; see [`NOTICE`](NOTICE).

Not an official Old School RuneScape or Jagex product, and not affiliated with
Jagex or with RuneLite.
