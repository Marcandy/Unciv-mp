# mpserver: authoritative multiplayer + agent seats for Unciv

MVP of the v3 architecture, plus AI-agent seats. New module only: no existing Unciv file is changed
(diff against upstream: `settings.gradle.kts` and `build.gradle.kts`, a few lines each).

## Run

```bash
./gradlew :mpserver:run                       # server on 127.0.0.1:8090
python3 mpserver/agents/strategist.py --turns 60 --offline-at 30 --offline-turns 10
python3 mpserver/viewer/build_viewer.py replay.json replay.html
```

`strategist.py` uses Claude when `ANTHROPIC_API_KEY` is set (`--policy claude`), otherwise a
deterministic persona with the same inputs and outputs (`--policy persona`).

Play a seat from Claude Code or any MCP client:

```bash
claude mcp add unciv-seat -e GAME_ID=<id> -e SEAT=Rome -e SEAT_TOKEN=<token> -- python3 mpserver/agents/mcp_server.py
```

## Architecture decisions

| Decision | Why |
|---|---|
| **Server runs the real `core` module headless** (libGDX headless backend, Ktor/Netty) | One rules engine and one AI for server, Android, desktop and iOS. Confirms v3 Gate 0 is reachable. |
| **Masked view built on upstream's `com.unciv.view` layer** (`GameView`, `TileView`, `ForeignCivView`) instead of v3's `GameInfoMasker` | Upstream added a per-viewer view layer after v3 was written, explicitly to enable "http-server-like request-response" (issue #15280). Emitting a view model, not a censored `GameInfo`, removes v3's fatal A1 (unknown terrain crashes the client), the A1 residual terrain leak, and the per-recipient deep clone that dominated cost (A7). |
| **Wire format = `kotlinx.serialization` DTOs** (`Protocol.kt`) | Codegen, no reflection, so the same types compile for iOS and a future web client. The save format is untouched. |
| **Intents, not moves, in the MVP** (`research`, `build`, `declareWar`, `note`) | Strategic intents carry no tile references, so they cannot probe the fog (v3 A9). Unit-level intents come next and must validate against the viewer's explored/visible tiles. |
| **One intent gate for everyone** (`IntentValidator`) | Humans, LLM agents and modified clients are all untrusted input: ownership check, then "is it known to you", then engine legality. |
| **Hybrid agent seat**: agent sets goals, Unciv AI moves units | What worked in CivAgent (Unciv) and Vox Deorum (Civ V). LLMs did not beat native AI on win rate, so use them for personality, diplomacy and goals, not difficulty. Keeps cost to one call every few turns. |
| **External seats are `PlayerType.Human`; OFFLINE seats are played by Unciv AI immediately** | Implements "never wait for someone who isn't there" with no engine change: `nextTurn()` stops at each external seat, and the server runs `NextTurnAutomation.automateCivMoves` for it when it is absent. Queues the seat set are respected. |
| **Canonical settings profile** (`canonicalSettings()`) | Settings that change game logic (v3 A2) are pinned per server, not taken from players. |
| **One game thread per process** | `UncivGame.Current` and `RulesetCache` are globals. Scale by process-per-shard. |
| **Durable intent log before apply** (`data/games/<id>/intents.jsonl`) + save each turn | Crash replays instead of losing acknowledged input (v3 A12). |
| **Seat tokens** (`X-Seat-Token`) | A seat can only read its own view and act for itself. |

## API

| Method | Path | |
|---|---|---|
| POST | `/games` | Create. Body: `CreateGameRequest`. Returns `gameId` and a token per external seat. |
| GET | `/games/{id}` | Seats, presence, turn, winner |
| GET | `/games/{id}/view/{civ}` | Masked view model (token) |
| GET | `/games/{id}/brief/{civ}` | Same view narrated for an LLM (token) |
| POST | `/games/{id}/intents` | `IntentBatch` → accepted/rejected with reasons (token) |
| POST | `/games/{id}/end-turn` | Ends the seat's turn; returns timings and events (token) |
| POST | `/games/{id}/presence/{civ}?present=false` | Mark a seat OFFLINE / PRESENT (token) |
| POST | `/games/{id}/autoplay?turns=N` | All seats OFFLINE, world plays N turns |
| GET | `/games/{id}/replay` | Spectator replay frames for the viewer |

## Timings recorded per turn (Phase 0 numbers)

`aiMs` (all AI civs in `nextTurn`), `seatAutomationMs`, `maskMs` (views for present seats),
`saveMs` (serialize + gzip), `loadMs` (every 10 turns), `saveBytes`.

## Next

1. WebSocket push of `EndTurnResult` + client `LiveClient` in the Android/desktop app.
2. Unit-level intents validated against `TileView.isExplored/isVisible` (+ `IntentVisibilityTest`).
3. Presence by heartbeat (PRESENT / AWAY / OFFLINE with the 120 s rule) and the roster-scaled pace ceiling.
4. Diplomacy chat: LLM leaders negotiate via `TradeLogic`, proposals still pass the validator.
5. Away Report written from the round log.
