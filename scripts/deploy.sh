#!/usr/bin/env bash
# Build, install, and launch fieldnode on the connected device — with NO on-phone taps.
#
# MIUI shows a confirmation dialog for every `adb install`. Installing through the
# package-manager shell instead (push to /data/local/tmp + `pm install`) bypasses
# MIUI's installer UI entirely, so the dev loop stays one command.
#
# Usage:
#   scripts/deploy.sh              build (debug) + install + launch
#   scripts/deploy.sh --no-build   skip the Gradle build, install the existing APK
#   scripts/deploy.sh --no-launch  install but don't start the activity
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$adb" ] || adb="$(command -v adb)"

application_id="de.christopherrehm.fieldnode"
apk="app/build/outputs/apk/debug/app-debug.apk"
remote_apk="/data/local/tmp/fieldnode.apk"

do_build=true
do_launch=true
for arg in "$@"; do
  case "$arg" in
    --no-build) do_build=false ;;
    --no-launch) do_launch=false ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

device_state="$("$adb" get-state 2>/dev/null || true)"
if [ "$device_state" != "device" ]; then
  echo "No authorized device (adb state: ${device_state:-none}). Plug in / authorize first." >&2
  exit 1
fi

if $do_build; then
  echo "▶ building debug APK…"
  ./gradlew :app:assembleDebug -q
fi

echo "▶ pushing APK…"
"$adb" push "$apk" "$remote_apk" >/dev/null

echo "▶ installing via pm (no MIUI prompt)…"
"$adb" shell pm install -r -t "$remote_apk"

"$adb" shell rm -f "$remote_apk" 2>/dev/null || true

if $do_launch; then
  echo "▶ launching…"
  "$adb" shell am start -n "$application_id/.MainActivity" >/dev/null
fi

echo "✔ done."
