#!/usr/bin/env bash
# Builds the Android app and puts it where it's easy to find: dist/undying-dominion.apk.
# Install it on a phone with `adb install -r dist/undying-dominion.apk`, or copy the file over
# and open it (docs/design/android.md, README "Play on Android").
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
( cd "$root/android" && ./gradlew --no-daemon -q assembleDebug )
mkdir -p "$root/dist"
cp "$root/android/app/build/outputs/apk/debug/app-debug.apk" "$root/dist/undying-dominion.apk"
echo "$root/dist/undying-dominion.apk"
