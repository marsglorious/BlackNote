#!/usr/bin/env bash
set -euo pipefail

GRADLE=app/app/build.gradle.kts
DEST=/var/www/downloads
export JAVA_HOME=/nix/store/q6zvjlnhypm88y989l8fq0hjqrz9agda-openjdk-17.0.20+2/lib/openjdk

# ── 1. Bump versionCode ──────────────────────────────────────────────────────
current_code=$(grep -oP 'versionCode = \K[0-9]+' "$GRADLE")
current_name=$(grep -oP 'versionName = "\K[^"]+' "$GRADLE")
new_code=$((current_code + 1))

sed -i "s/versionCode = $current_code/versionCode = $new_code/" "$GRADLE"
echo "versionCode $current_code → $new_code  (versionName $current_name)"

# ── 2. Build ─────────────────────────────────────────────────────────────────
cd app
./gradlew --no-configuration-cache assembleRelease
cd ..

# ── 3. Commit ────────────────────────────────────────────────────────────────
git add "$GRADLE"
git commit -m "Release versionCode $new_code ($current_name)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

# ── 4. Deploy ────────────────────────────────────────────────────────────────
hash=$(git rev-parse --short HEAD)
apk="blacknote-${current_name}-${hash}.apk"
cp app/app/build/outputs/apk/release/app-release.apk "$DEST/$apk"
printf '{"version":"%s","versionCode":%d,"url":"https://georealms.net/downloads/%s"}\n' \
  "$current_name" "$new_code" "$apk" > "$DEST/latest.json"

echo "Deployed $apk (versionCode $new_code)"
cat "$DEST/latest.json"
