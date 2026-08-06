#!/usr/bin/env bash
# Run BlackNote unit + instrumented tests.
# Uses /opt paths when present; otherwise falls back to Nix-provided toolchain.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
NIX_SHELL="$ROOT/scripts/nix-test-shell.nix"

run_gradle() {
  if [[ -x /opt/gradle-8.4/bin/gradle && -d /opt/android-sdk ]]; then
    export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$APP_DIR/local.properties"
    cd "$APP_DIR"
    /opt/gradle-8.4/bin/gradle "$@"
  else
    cd "$ROOT"
    nix-shell "$NIX_SHELL" --run "cd app && gradle $* --no-daemon"
  fi
}

echo "==> JVM unit tests (Robolectric)"
run_gradle :app:testDebugUnitTest

UNIT_REPORT="$APP_DIR/app/build/reports/tests/testDebugUnitTest/index.html"
echo "==> Unit report: $UNIT_REPORT"

if [[ "${BLACKNOTE_SKIP_INSTRUMENTED:-}" == "1" ]]; then
  echo "==> Skipping instrumented tests (BLACKNOTE_SKIP_INSTRUMENTED=1)"
  exit 0
fi

echo "==> Instrumented tests on virtual device (API 34)"
if [[ -x /opt/gradle-8.4/bin/gradle && -d /opt/android-sdk ]]; then
  run_gradle :app:pixel7DebugAndroidTest
  INSTR_REPORT="$APP_DIR/app/build/reports/androidTests/managedDevice/pixel7/debug/index.html"
else
  "$ROOT/scripts/run_emulator_tests.sh"
  INSTR_REPORT="$APP_DIR/app/build/reports/androidTests/connected/index.html"
fi

echo "==> Instrumented report: $INSTR_REPORT"