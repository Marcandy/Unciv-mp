#!/usr/bin/env bash
# Android setup and phone test (doc 13, step S0) on macOS.
#   bash mpserver/scripts/android-phone.sh setup            # SDK command-line tools, platform 37, local.properties
#   bash mpserver/scripts/android-phone.sh build            # debug APK (app id com.unciv.app.mp.debug)
#   bash mpserver/scripts/android-phone.sh install [saves]  # install on the USB phone, copy saves to Download/
#   bash mpserver/scripts/android-phone.sh log              # follow the game's log on the phone
# Phone: Settings > About phone > tap "Build number" 7 times, then Developer options > USB debugging on.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
APP_ID=com.unciv.app.mp.debug

say() { printf "\n\033[1;34m==> %s\033[0m\n" "$*"; }
die() { printf "\n\033[1;31mError: %s\033[0m\n" "$*"; exit 1; }

java_home() {
  [ -n "${JAVA_HOME:-}" ] && { echo "$JAVA_HOME"; return; }
  /usr/libexec/java_home -v 21 2>/dev/null && return
  command -v mise >/dev/null && mise where java 2>/dev/null && return
  die "Java 21 not found"
}

setup() {
  export JAVA_HOME="$(java_home)"
  command -v sdkmanager >/dev/null || { say "Installing Android command-line tools"; brew install --cask android-commandlinetools; }
  mkdir -p "$SDK"
  say "Accepting SDK licences"
  yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null || true
  say "Installing platform-tools, platform 37, build-tools 37"
  sdkmanager --sdk_root="$SDK" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
  echo "sdk.dir=$SDK" > "$DIR/local.properties"
  say "SDK ready at $SDK"
}

build() {
  export JAVA_HOME="$(java_home)"
  [ -f "$DIR/local.properties" ] || die "run setup first"
  (cd "$DIR" && ./gradlew :android:assembleDebug)
  find "$DIR/android/build" -name "*-debug.apk" | head -1
}

install() {
  "$ADB" get-state >/dev/null 2>&1 || die "no phone found: plug it in by USB, enable USB debugging, accept the prompt on the phone"
  local apk="${APK:-$(find "$DIR/android/build" -name "*-debug.apk" | head -1)}"
  [ -f "$apk" ] || die "no APK: run build first (or set APK=path)"
  say "Installing $apk"
  "$ADB" install -r "$apk"
  for save in "$@"; do
    say "Copying $(basename "$save") to the phone's Download folder"
    "$ADB" push "$save" "/sdcard/Download/$(basename "$save")"
  done
  "$ADB" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
  say "Started. In the game: Load game > Load from custom location > Download > pick a save"
}

log() { "$ADB" logcat --pid="$("$ADB" shell pidof -s "$APP_ID")"; }

case "${1:-}" in
  setup) setup ;;
  build) build ;;
  install) shift; install "$@" ;;
  log) log ;;
  *) sed -n 2,7p "$0" ;;
esac
