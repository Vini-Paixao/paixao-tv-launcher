#!/usr/bin/env bash
# Installs the launcher on a Fire TV Stick and turns on its Home redirect.
# Usage: FIRE_TV=192.168.1.50 tools/deploy.sh [path to apk]
# If trakt-token.json or tmdb.txt sit in the project root, the app reads them once and deletes its copy.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${FIRE_TV:?set FIRE_TV to the stick's IP address}"
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SERVICE=dev.leanhome/dev.leanhome.GuardService
DROP=/sdcard/Android/data/dev.leanhome/files
TV="$FIRE_TV:5555"

adb connect "$TV"
adb -s "$TV" install -r "$APK"

# First launch after boot goes through an alarm, which Fire OS delays by default.
adb -s "$TV" shell settings put global alarm_manager_constants min_futurity=0

# The pause matters: with less, Android keeps the previous install's capabilities.
adb -s "$TV" shell settings put secure enabled_accessibility_services null
sleep 2
adb -s "$TV" shell settings put secure enabled_accessibility_services "$SERVICE"
adb -s "$TV" shell settings put secure accessibility_enabled 1

adb -s "$TV" shell mkdir -p "$DROP"
for secret in trakt-token.json tmdb.txt; do
  if [ -f "$secret" ]; then adb -s "$TV" push "$secret" "$DROP/$secret"; fi
done

echo "Done. Press Home on the remote: the app reads those files the next time Home opens."
