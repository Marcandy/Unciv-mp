# Unciv multiplayer fork: context for Claude Code

This is Marc's fork of yairm210/Unciv (base commit `fce8c98`, 23 Oct 2026), branch `mpserver-mvp`.
Goal: server-authoritative multiplayer where offline seats never block the game, plus AI-agent seats.
Planning docs live in Google Drive, folder "Unciv Multiplayer Project" (start with `00-README-START-HERE.md`;
current plan is `Unciv-Multiplayer-Architecture-v3.md`). Detailed module docs: `mpserver/README.md`.

## Rules for this fork
- Add new files; keep changes to existing Unciv files minimal (budget about 1,200 lines total, measure it:
  `git diff --stat fce8c98 -- . ':!mpserver' ':!CLAUDE.md'`). So far only `settings.gradle.kts` and
  `build.gradle.kts` are touched (about 18 lines).
- Base rulesets only in multiplayer (no mods yet: `RulesetCache` is a global keyed by mod name).
- Every client action, human or agent, goes through `IntentValidator`. Treat agent output as untrusted input.
- All game work runs on the single game thread (`gameThread` in `Main.kt`). The engine uses globals
  (`UncivGame.Current`, `RulesetCache`), so scale with processes, not threads.
- Do not change the save format. The wire protocol is `kotlinx.serialization` DTOs in `Protocol.kt`.

## Module map: `mpserver/`
- `src/.../Main.kt`: boots headless libGDX + Unciv (needs `UncivFiles`, silent `MusicController`,
  canonical settings), Ktor routes, seat tokens (`X-Seat-Token`).
- `src/.../GameHost.kt`: one game. Creates it, runs turns, presence (PRESENT/OFFLINE), per-turn timings,
  durable intent log (`data/games/<id>/intents.jsonl`), replay frames.
- `src/.../MaskedView.kt`: per-civ view built on upstream `com.unciv.view` (GameView/TileView). Unexplored
  tiles are omitted. Also `brief()`: narrated text for LLM seats.
- `src/.../IntentValidator.kt`: research, build, declareWar, note. Ownership, then "known to you", then legality.
- `agents/strategist.py`: plays a seat. `--policy claude` (needs `ANTHROPIC_API_KEY`) or `persona`.
- `agents/mcp_server.py`: MCP server for one seat (tools: get_brief, get_view, submit_intents, end_turn).
- `viewer/`: `build_viewer.py replay.json replay.html` builds a self-contained replay page.
- `scripts/setup-mac.sh`: install, build, run demo; `push` subcommand forks and pushes.

## Commands
```bash
./gradlew :mpserver:run                     # server on 127.0.0.1:8090 (first build ~5-10 min)
python3 mpserver/agents/strategist.py --turns 80 --every 3 --offline-at 40 --offline-turns 15 --out mpserver/replay.json
python3 mpserver/viewer/build_viewer.py mpserver/replay.json mpserver/replay.html
```
Gotchas found so far: headless boot needs `gdxNatives("desktop")`, `game.files = UncivFiles(...)` and a
`MusicController` (nextTurn touches music every 10 turns). `GameStarter` saves settings, which needs `files`.

## Status (24 Oct 2026)
- Working: 80-turn demo on Small map, 4 majors + 2 city-states, Rome as agent seat, offline turns 40-55
  played by Unciv AI with no wait.
- Measured per turn (Small, early game): AI 45 ms avg (max 151), seat automation 6 ms, masked view 1 ms,
  save+gzip 7 ms, load 20-82 ms, save 24 KB at turn 80. Not yet measured: Medium/Huge, late game.
- Branch not yet pushed. Push target: `marcandy/Unciv` (fork), branch `mpserver-mvp`.

## Next, in order
1. Push the branch to the fork.
2. Run the strategist with a real Claude key; compare to the persona.
3. Gate 0: Medium and Huge maps, 6-10 civs, late-game saves, on the target VPS.
4. WebSocket push of `EndTurnResult` and a `LiveClient` in the desktop/Android app so a human joins a seat.
5. Unit-level intents validated against `TileView.isExplored/isVisible`, plus `IntentVisibilityTest`.
6. Presence by heartbeat (PRESENT/AWAY/OFFLINE, 120 s rule) and the roster-scaled pace ceiling.
7. LLM diplomacy through `TradeLogic`, still through the validator. Away Report from the round log.
