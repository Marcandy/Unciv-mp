#!/usr/bin/env bash
# Unciv multiplayer MVP: install, build and run on macOS.
#   bash ~/Downloads/setup-unciv-mp.sh            # install + build + run the 80-turn demo + open replay
#   bash ~/Downloads/setup-unciv-mp.sh push       # fork to your GitHub and push the mpserver-mvp branch
# Expects unciv-mpserver-mvp.bundle next to this script (same Downloads folder).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUNDLE="$HERE/unciv-mpserver-mvp.bundle"
DIR="${UNCIV_MP_DIR:-$HOME/dev/unciv-mp}"
BASE_COMMIT=fce8c98
PORT=8090

say() { printf "\n\033[1;34m==> %s\033[0m\n" "$*"; }
die() { printf "\n\033[1;31mError: %s\033[0m\n" "$*"; exit 1; }

# ---------- Java 21 ----------
find_java() {
  for v in 21 22 23 24 25; do
    if /usr/libexec/java_home -v "$v" >/dev/null 2>&1; then /usr/libexec/java_home -v "$v"; return 0; fi
  done
  for p in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 "$HOME/.homebrew/opt/openjdk@21"; do
    [ -x "$p/bin/java" ] && { echo "$p/libexec/openjdk.jdk/Contents/Home"; return 0; }
  done
  return 1
}

ensure_java() {
  if JH="$(find_java)"; then export JAVA_HOME="$JH"; return; fi
  command -v brew >/dev/null || die "Java 21+ not found and Homebrew is missing. Install a JDK 21 (e.g. https://adoptium.net) and rerun."
  say "Installing OpenJDK 21 with Homebrew"
  brew install openjdk@21
  JH="$(find_java)" || die "OpenJDK 21 installed but not found; set JAVA_HOME to it and rerun."
  export JAVA_HOME="$JH"
}

# ---------- Code ----------
ensure_code() {
  [ -f "$BUNDLE" ] || die "unciv-mpserver-mvp.bundle not found next to this script ($HERE)."
  if [ ! -d "$DIR/.git" ]; then
    say "Cloning Unciv into $DIR (blobless clone, a few minutes)"
    mkdir -p "$(dirname "$DIR")"
    git clone --filter=blob:none https://github.com/yairm210/Unciv.git "$DIR"
  fi
  cd "$DIR"
  git cat-file -e "$BASE_COMMIT^{commit}" 2>/dev/null || git fetch origin
  say "Applying the mpserver-mvp commits"
  git checkout -q --detach 2>/dev/null || true
  git fetch "$BUNDLE" mpserver-mvp:mpserver-mvp 2>/dev/null || git fetch -f "$BUNDLE" mpserver-mvp:mpserver-mvp
  git checkout -q mpserver-mvp
  git log --oneline -4
}

# ---------- Build + run ----------
wait_for_server() {
  for _ in $(seq 1 180); do
    curl -s "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && return 0
    sleep 2
  done
  die "Server did not start. See $DIR/mpserver/server.log"
}

run_demo() {
  cd "$DIR"
  say "Java: $JAVA_HOME"
  say "Building (first build downloads dependencies, about 5-10 minutes)"
  ./gradlew :mpserver:classes --console=plain -q

  if curl -s "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    say "Server already running on port $PORT"
  else
    say "Starting game server on http://127.0.0.1:$PORT (log: mpserver/server.log)"
    nohup ./gradlew :mpserver:run --console=plain > mpserver/server.log 2>&1 &
    echo $! > mpserver/server.pid
    wait_for_server
  fi

  POLICY="persona"; [ -n "${ANTHROPIC_API_KEY:-}" ] && POLICY="claude"
  say "Playing an 80-turn game: Rome = agent seat ($POLICY), offline turns 40-55"
  python3 mpserver/agents/strategist.py --policy "$POLICY" --turns 80 --every 3 \
    --offline-at 40 --offline-turns 15 --out mpserver/replay.json

  python3 mpserver/viewer/build_viewer.py mpserver/replay.json mpserver/replay.html
  say "Opening the replay"
  open mpserver/replay.html

  cat <<EOF

Done. The server keeps running on http://127.0.0.1:$PORT
  Stop it:      kill \$(cat $DIR/mpserver/server.pid)
  Run again:    bash "$0"
  Use Claude:   export ANTHROPIC_API_KEY=...  then rerun (Rome is then played by Claude)
  MCP seat:     see $DIR/mpserver/README.md
  Push to GitHub: bash "$0" push
EOF
}

# ---------- Push ----------
push_branch() {
  cd "$DIR"
  command -v gh >/dev/null || { command -v brew >/dev/null && brew install gh || die "Install GitHub CLI (gh) and rerun."; }
  gh auth status >/dev/null 2>&1 || gh auth login
  ME="$(gh api user -q .login)"
  say "Forking yairm210/Unciv to $ME/Unciv (skipped if it exists)"
  gh repo fork yairm210/Unciv --remote=false --clone=false >/dev/null 2>&1 || true
  git remote get-url fork >/dev/null 2>&1 || git remote add fork "https://github.com/$ME/Unciv.git"
  gh auth setup-git
  say "Pushing mpserver-mvp to $ME/Unciv"
  git push -u fork mpserver-mvp
  echo "https://github.com/$ME/Unciv/tree/mpserver-mvp"
}

ensure_code
case "${1:-run}" in
  push) push_branch ;;
  code) ;;
  *) ensure_java; run_demo ;;
esac
