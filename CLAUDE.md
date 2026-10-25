# Unciv multiplayer fork: context for Claude Code

This is Marc's fork of yairm210/Unciv (base commit `fce8c98`, 23 Oct 2026), branch `mpserver-mvp`.
Goal (MVP, decided 25 Oct 2026): server-authoritative 8v8 fixed-team games (Allies vs Axis) on Android and iOS,
where no player waits long: Plan & Commit rounds with a turn timer, AI plays late or absent seats.
LLM agent seats exist but are not part of the MVP.
Local dev diary (gitignored): `mpserver/DEV-DIARY.md`; add an entry for every important bug and its root cause.
Planning docs live in Google Drive, folder "Unciv Multiplayer Project" (start with `00-README-START-HERE.md`;
current plan is `Unciv-Multiplayer-Architecture-v3.md`). Detailed module docs: `mpserver/README.md`.

## Rules for this fork
- Add new files; keep changes to existing Unciv files minimal (budget about 1,200 lines total, measure it:
  `git diff --stat fce8c98 -- . ':!mpserver' ':!CLAUDE.md'`). So far only `settings.gradle.kts` and
  `build.gradle.kts` are touched (about 18 lines).
- Base rulesets only in multiplayer (no user mods: `RulesetCache` is a global keyed by mod name). The one
  exception is our in-memory `MP Fixed Teams` mod (`TeamRules`).
- One game per process (`GameRegistry.host`): engine logic reads `UncivGame.Current.gameInfo`.
- Every client action goes through `IntentValidator` (`check` on submit, `apply` at resolution). All input is untrusted.
- All game work runs on the single game thread (`gameThread` in `Main.kt`). The engine uses globals
  (`UncivGame.Current`, `RulesetCache`), so scale with processes, not threads.
- Do not change the save format. The wire protocol is `kotlinx.serialization` DTOs in `Protocol.kt`.

## Module map: `mpserver/`
- `src/.../Main.kt`: boots headless libGDX + Unciv (needs `UncivFiles`, silent `MusicController`,
  canonical settings), Ktor routes, seat tokens (`X-Seat-Token`), admin token (`X-Admin-Token`, `MP_ADMIN_TOKEN`),
  1 s round scheduler, restore on boot, `bench` mode.
- `src/.../GameHost.kt`: one game. Round lifecycle (plan, seal on all-present-committed or timer, resolve),
  presence by last request (120 s), per-seat events, timings, replay frames.
- `src/.../RoundEngine.kt`: one Plan & Commit round on public `TurnManager` calls (replaces `nextTurn()`);
  seeded, team-interleaved resolution order.
- `src/.../TeamRules.kt`: fixed teams via the `Diplomatic relationships cannot change` mod option, meet/war
  setup at start, team victory.
- `src/.../GameStore.kt`: `meta.json`, atomic `save.txt` per round, fsynced `plans.jsonl`; restore.
- `src/.../MaskedView.kt`: per-civ view built on upstream `com.unciv.view` (GameView/TileView). Unexplored
  tiles are omitted. Also `brief()`: narrated text for LLM seats.
- `src/.../IntentValidator.kt`: research, build, note (diplomacy intents rejected: fixed teams).
- `test/.../RoundTest.kt`: 2v2 round rules (`./gradlew :mpserver:test`).
- `agents/strategist.py`: plays a seat. `--policy claude` (needs `ANTHROPIC_API_KEY`) or `persona`.
- `agents/mcp_server.py`: MCP server for one seat (tools: get_brief, get_view, submit_intents, end_turn).
- `viewer/`: `build_viewer.py replay.json replay.html` builds a self-contained replay page.
- `scripts/setup-mac.sh`: install, build, run demo; `push` subcommand forks and pushes.

## Commands
```bash
./gradlew :mpserver:run                     # server on 127.0.0.1:8090 (first build ~5-10 min)
./gradlew :mpserver:run -PserverArgs="bench Large 100"   # 8v8 all-AI timing benchmark
./gradlew :mpserver:test
python3 mpserver/agents/strategist.py --turns 80 --every 3 --offline-at 40 --offline-turns 15 --out mpserver/replay.json
python3 mpserver/viewer/build_viewer.py mpserver/replay.json mpserver/replay.html
```
Gotchas found so far: the VS Code Java extension builds this project in the background into the same
`build/` dirs (different JDK/Kotlin) and races Gradle runs (NoClassDefFoundError). Use a separate build
dir for CLI runs (init script setting `layout.buildDirectory`) or disable `java.autobuild.enabled`. headless boot needs `gdxNatives("desktop")`, `game.files = UncivFiles(...)` and a
`MusicController` (nextTurn touches music every 10 turns). `GameStarter` saves settings, which needs `files`.
`GameInfo.setTransients` needs at least one human player: all-AI games get a spectator.

## Status (25 Oct 2026)
- Server reworked for 8v8 teams + Plan & Commit (see `mpserver/README.md`). 10 tests pass (`RoundTest`).
- Bench, Huge map (4,921 tiles), 16 AI civs + 8 city-states, after the AI fixes below (M-series Mac, not the VPS):
  - turn 100: vanilla diplomacy 83 cities / 0 captures; teams war@30 75 cities / 5 captures / 1 civ out;
    teams war@0 73 cities / 4 captures / 1 out. Turn 200 (war@30): 87 cities, 13 captures, 3 civs out.
  - server time ~100 ms/round early, ~330 ms at turn 200; save 193 KB at turn 200; load ~150 ms.
- AI fixes for team wars (core, ~60 lines + new `logic/automation/unit/AlliedMilitaryAutomation.kt`):
  local threat per city instead of "own army vs sum of all enemies" (that made every city a permanent
  barracks in 8v8), allies (defensive pact partners) count in sieges and defend each other's damaged
  cities, personality scales siege commitment (Aggressive) and help radius (Loyal).
- `useAStarPathfinding` must stay false: the experimental A* never reaches enemy city tiles, so the AI
  found no siege targets (0 captures). Upstream bug worth reporting.
- Pushed to remote `fork` = `marcandy/unciv-mp` (public fork of Unciv), branch `mpserver-mvp`. `origin` is upstream: never push there.
- `mpserver/build/` is tracked in git from earlier commits; now gitignored, still needs `git rm -r --cached mpserver/build`.

## Status addendum (25 Oct 2026, night)
- Defaults: Huge, war at turn 20, 4 city-states, grouped team starts. 11 tests pass.
- Grouped vs mixed starts, 5 seeds × 250 turns: grouped = +14% cities, 2.4 vs 4.4 civs eliminated, same captures (~14).
  Axis ahead in 7/10 games: check team composition next. Details in `mpserver/DEV-DIARY.md`.
- Plans written to Drive: `08-Mobile-Plan-Android-iOS.md`, `09-Architecture-Least-Maintenance-8v8.md`,
  `10-Release-under-LLC-and-Monetization-Guardrails.md`. Key new fact: Apple rejected Unciv4iOS under 4.3(a) on 28 Sep 2026.

## Status addendum (25 Oct 2026, day 2)
- Defaults: Huge, war at 20, **3 contested city-states on the front line** (rich resources, at war with both teams,
  no starting defenders yet), **random leaders**, grouped starts. 15 tests pass.
- 15 seeds × 300 turns (old AI): turn-150 leader wins 13/15, Allies 8 / Axis 7 (random leaders fixed the Axis bias).
- AI: rear civs reinforce their team's threatened cities and train units for the team (`AlliedMilitaryAutomation`);
  **teammates share vision** (fixed-team pacts, `CivInfoTransientCache`): done. 45-game re-bench (2/3/4 CS) running;
  4 CS: leader wins 12/15, 10/15 games decided before 300.
- Opt-in anti-snowball `CatchUp` (overextension + bounty), `CreateGameRequest.catchUp`, bench arg `catchup`. Not measured.
- **Focus now: phone clients.** Plan in Drive `13-Mobile-Adversarial-Review-and-Cheapest-Path.md` (supersedes the
  order in doc 08). Team balance resumes later (doc 12 / design paper).

## Next, in order
1. S0: Android SDK + turn-200 Huge save on a real phone; in parallel a gdx-teavm web spike on iPhone Safari.
2. CI (GitHub Actions): `:mpserver:test` + Android debug build.
3. Client protocol on upstream's View layer: serialise each `View.try…` call as an intent, replay it on the server
   (60 `try…` functions exist; upstream issue #15280). Full (unmasked) state in the closed beta.
4. Android closed beta (own name/icon, FCM push, WebSocket rounds); then web build for iOS players.
5. Masking (Stage 1 + `MaskLeakTest`) before any public link. App Store only after that (4.3 risk).
6. Game: city-state starting defenders; longer early timer when humans are seated; catch-up measured; team resource gifting.
