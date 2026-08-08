#!/usr/bin/env bash
# Run the BlackNote JVM/Robolectric unit tests.
#
# Why this wrapper exists: the Android SDK build-tools (AAPT2) shipped in this
# workspace are Windows .exe binaries, so the Linux/WSL Gradle can't assemble
# resources. Windows binaries ARE reachable from WSL, though — so we drive the
# *Windows* Gradle (gradlew.bat) via cmd.exe. That runs AAPT2 natively and the
# whole suite passes. This is what lets tests be run headlessly from WSL.
#
# Usage:
#   ./run-tests.sh              # run the full unit-test suite
#   ./run-tests.sh <testFilter> # e.g. ./run-tests.sh "*FileAccessTest*"
set -euo pipefail

cd "$(dirname "$0")/app"

if [[ $# -gt 0 ]]; then
  cmd.exe /c "gradlew.bat testDebugUnitTest --tests \"$1\" --console=plain"
else
  cmd.exe /c "gradlew.bat testDebugUnitTest --console=plain"
fi
