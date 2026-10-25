# mpserver: authoritative team multiplayer for Unciv

Server for fixed-team games (default 8v8, Allies vs Axis) where no player waits long: everyone plans
the same turn at once, a turn timer bounds every round, and the AI covers absent or late players.
New module only: outside `mpserver/` the fork changes `settings.gradle.kts` and `build.gradle.kts`, a few lines each.

## Run

```bash
./gradlew :mpserver:run                                   # server on 127.0.0.1:8090, prints the admin token
./gradlew :mpserver:run -PserverArgs="bench Large 100"    # 8v8 timing benchmark, all 16 civs AI
./gradlew :mpserver:test                                  # round rules: teams, sealing, offline, timeout, restart
```

Set `MP_ADMIN_TOKEN` to fix the admin token. One process hosts one game; it restores the latest game
from `mpserver/data/games/` on boot.

## Round model: Plan & Commit with a turn timer

1. **Plan.** Every seat sees the state at the start of the round and submits intents. They are checked
   right away against that seat's knowledge, buffered, and fsynced to `plans.jsonl` before the reply.
2. **Seal.** The round closes when every PRESENT seat has committed, or when the timer runs out
   (`TurnTimer`: 12 s per turn up to turn 50, ramping to 36 s at turn 100; ~350 ms of server time per round on Huge at turn 150). If nobody is present the game
   pauses instead of running unattended.
3. **Resolve** (`RoundEngine`). Civs act one at a time in a seeded order that alternates teams
   (Allies, Axis, Allies, ...), then city-states and barbarians, each followed by its `endTurn()`; then
   every civ gets `startTurn()`. Per seat:
   - committed: its plan; with `delegateRest` (default) the AI also handles units and empty queues, without diplomacy or spending (`PLAN+AI`), otherwise nothing else moves (`PLAN`)
   - present but late: whatever it planned, then the same AI stewardship (`PLAN+AI`)
   - OFFLINE (no authenticated request for 120 s, or it left): played fully by the AI (`AI`)
4. **Persist.** The new save replaces `save.txt` atomically, then `plans.jsonl` is cleared. A crash at any
   point restarts from the last round boundary with every acknowledged plan re-applied.

## Teams

`TeamRules` builds fixed teams from what Unciv already has, without touching core:
- an in-memory extension mod `MP Fixed Teams` carrying the `Diplomatic relationships cannot change`
  mod option, listed in `GameParameters.mods` so every reload rebuilds it. Unciv's AI then never declares
  war or offers peace, and trade logic drops war/peace offers;
- at game start all majors meet; teammates are at peace; the teams go to war at `warFromTurn` (default 30: with war from turn 0 the AI barely expands);
- a team wins when every enemy major is gone or it holds every enemy original capital; Unciv's own
  victories (science, culture, diplomacy, time) count for the winner's team.

Teammates also get a permanent defensive pact (no expiry flag) and open borders. The AI reads defensive
pact partners as allies (`core/.../AlliedMilitaryAutomation.kt`): it judges threats per city from nearby
units, besieges together with allies, and defends allied cities under attack; Aggressive and Loyal
personality values scale siege commitment and help radius.

Clients need the same one-line mod so their diplomacy screen hides war/peace buttons. Not done yet:
shared vision, starting positions grouped by team, open borders between teammates.

## Architecture decisions

| Decision | Why |
|---|---|
| **Server runs the real `core` module headless** | One rules engine and one AI for server, Android and iOS. |
| **Plan & Commit, not live simultaneous or sequential** | Round time is the slowest *present* player capped by the timer, never the sum of 16 players. No first-mover race against enemies, and the engine still sees one civ acting at a time. |
| **Round loop in `RoundEngine`, built on public `TurnManager` calls** | Replaces `GameInfo.nextTurn()` without editing it. |
| **One game per process** | `UncivGame.Current.gameInfo` is read by engine logic (trade offer text, era filters), so two games in one JVM would read each other's state. A second `POST /games` is refused. |
| **Masked view model** (`MaskedView` on upstream `GameView`/`TileView`) | Used by bots and tests now. The Android/iOS client (Unciv UI) needs a masked `GameInfo` instead: see Next. |
| **One intent gate** (`IntentValidator`): `check` on submit, re-checked by `apply` at resolution | Earlier civs in the order may have changed the world. Everyone's input is untrusted. |
| **Canonical settings profile** (`canonicalSettings()`) | Settings that change game logic are game rules on the server, not preferences. |
| **Admin token** for create, autoplay and replay; seat tokens for everything per seat | The replay shows the whole map; autoplay moves the game for everyone. |

## API

| Method | Path | Auth | |
|---|---|---|---|
| POST | `/games` | admin | Create. Body `CreateGameRequest` (teams, externalSeats, mapSize, turnTimer). Returns `gameId` and a token per seat. |
| GET | `/games/{id}` | none | Turn, seats with team/presence/committed, round deadline, winning team |
| GET | `/games/{id}/wait?after=N&civ=X` | seat (optional) | Long-poll until turn > N (25 s max). With a token it is the seat's heartbeat. |
| GET | `/games/{id}/view/{civ}` | seat | Masked view model |
| GET | `/games/{id}/brief/{civ}` | seat | Same view as text |
| POST | `/games/{id}/intents` | seat | `IntentBatch` → planned / rejected with reasons |
| POST | `/games/{id}/commit` (alias `/end-turn`) | seat | `CommitRequest{civ, delegateRest}` → `CommitResult`, with the `RoundResult` if this commit sealed the round |
| POST | `/games/{id}/presence/{civ}?present=false` | seat | Leave: the AI plays the seat from now on |
| POST | `/games/{id}/autoplay?turns=N` | admin | Every seat leaves; the world plays N rounds |
| GET | `/games/{id}/replay` | admin | Full-map replay frames for `viewer/` |

## Agents (not part of the MVP)

`agents/strategist.py` and `agents/mcp_server.py` still work against this API (they need `MP_ADMIN_TOKEN`
to create a game). Note that agents running on the server host can read `data/games/*/save.txt`.

## Next

1. **Client path for Android and iOS** (Unciv app + Unciv4iOS): masked `GameInfo` (v3 Stage-1 masker) with
   `MaskRoundTripTest` and `MaskLeakTest` written first; the client plays the round locally on it and
   sends its actions as intents.
2. **Unit intents** (move, attack, found city) validated against the viewer's explored/visible tiles
   (`IntentVisibilityTest`), skipped (never substituted) when stale at resolution.
3. WebSocket push of round results; push notifications (APNs/FCM).
4. Team features: shared vision in the masker, team-grouped starts, teammate open borders.
5. Measure a 16-civ map on a mid-range Android phone (client apply time per round).
