#!/usr/bin/env bash
# Boot a local Android emulator and run instrumented tests via connectedDebugAndroidTest.
# Works on NixOS where Gradle Managed Devices cannot use the Nix-patched emulator.
#
# Without /dev/kvm (pure TCG software emulation):
#   - Prefer API 30 AOSP (default) — boots in ~10–20 min under TCG.
#   - API 34 google_apis is too heavy (watchdog thrash / multi-hour first-boot).
#   - Disable ModemSimulator (broken ::1 modem socket leaves guest offline).
#   - Raise ro.hw_timeout_multiplier so Watchdog does not kill system_server.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
NIX_SHELL="$ROOT/scripts/nix-test-shell.nix"
EMU_PID=""
EMU_LOG="/tmp/blacknote-emulator.log"
PID_FILE="/tmp/blacknote-emulator.pid"
WRITABLE_SDK="${BLACKNOTE_SDK:-$HOME/.cache/blacknote-android-sdk}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

cleanup() {
  if [[ "${BLACKNOTE_KEEP_EMULATOR:-}" == "1" ]]; then
    return 0
  fi
  if [[ -n "$EMU_PID" ]] && kill -0 "$EMU_PID" 2>/dev/null; then
    kill "$EMU_PID" 2>/dev/null || true
    wait "$EMU_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# Only /dev/kvm means usable hardware accel. CPU vmx/svm alone is useless without
# the device node (common in VMs without nested virt).
has_kvm() {
  [[ -r /dev/kvm ]]
}

run_in_nix() {
  cd "$ROOT"
  nix-shell "$NIX_SHELL" --run "$1"
}

# Under pure TCG, swapon of zram right after boot_completed OOMs zygote.
# Comment out the zram fstab line and swapon_all in vendor.img (same-length patches).
disable_zram_in_sysimg() {
  local sysimg="$1"
  python3 - "$sysimg" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1])
p = root / "vendor.img"
if not p.is_file():
    raise SystemExit(0)
data = bytearray(p.read_bytes())
patches = [
    (b"dev/block/zram0 none swap  defaults zramsize=75%",
     b"#ev/block/zram0 none swap  defaults zramsize=75%"),
    (b"swapon_all", b"#wapon_all"),
]
changed = False
for old, new in patches:
    assert len(old) == len(new)
    n = data.count(old)
    if n:
        print(f"  patched {n}x {old!r} in vendor.img")
        data = data.replace(old, new)
        changed = True
if changed:
    bak = p.with_suffix(p.suffix + ".bak-zram")
    if not bak.exists():
        bak.write_bytes(p.read_bytes())
    p.write_bytes(data)
PY
}

# Manually create a lightweight API 30 AVD. Nix-wrapped avdmanager only sees
# packages in the nix store SDK, not images installed into the writable cache,
# so we write config.ini with an absolute image.sysdir.1 path.
ensure_api30_avd() {
  local name="$1"
  local avd_dir="$AVD_HOME/${name}.avd"
  local sysimg="$WRITABLE_SDK/system-images/android-30/default/x86_64"
  if [[ ! -f "$sysimg/system.img" ]]; then
    echo "Missing system image: $sysimg" >&2
    echo "Install with: sdkmanager --sdk_root=$WRITABLE_SDK 'system-images;android-30;default;x86_64' 'platforms;android-30'" >&2
    exit 1
  fi
  echo "==> Ensuring zram is disabled in API 30 system images (TCG OOM fix)"
  disable_zram_in_sysimg "$sysimg"
  if [[ -f "$avd_dir/config.ini" ]]; then
    return 0
  fi
  echo "==> Creating lightweight AVD $name (API 30 AOSP x86_64)"
  mkdir -p "$avd_dir"
  cp -a "$sysimg/userdata.img" "$avd_dir/userdata.img"
  cat > "$AVD_HOME/${name}.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avd_dir
path.rel=avd/${name}.avd
target=android-30
EOF
  cat > "$avd_dir/config.ini" <<EOF
AvdId=${name}
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=${name}
avd.ini.encoding=UTF-8
disk.cachePartition=yes
disk.cachePartition.size=66MB
disk.dataPartition.size=2G
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.device.manufacturer=Google
hw.device.name=pixel_4
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.gsmModem=no
hw.keyboard=yes
hw.lcd.density=320
hw.lcd.height=1280
hw.lcd.width=720
hw.mainKeys=no
hw.ramSize=4096
hw.sdCard=yes
image.sysdir.1=${sysimg}/
runtime.network.latency=none
runtime.network.speed=full
tag.display=Default Android System Image
tag.id=default
vm.heapSize=256
EOF
}

tune_avd_config() {
  local cfg="$1"
  [[ -f "$cfg" ]] || return 0
  grep -q '^hw.cpu.ncore=' "$cfg" && sed -i 's/^hw.cpu.ncore=.*/hw.cpu.ncore=2/' "$cfg" \
    || echo 'hw.cpu.ncore=2' >> "$cfg"
  sed -i 's/^hw.lcd.width=.*/hw.lcd.width=720/' "$cfg" || true
  sed -i 's/^hw.lcd.height=.*/hw.lcd.height=1280/' "$cfg" || true
  sed -i 's/^hw.lcd.density=.*/hw.lcd.density=320/' "$cfg" || true
  if grep -q '^hw.gsmModem=' "$cfg"; then
    sed -i 's/^hw.gsmModem=.*/hw.gsmModem=no/' "$cfg"
  else
    echo 'hw.gsmModem=no' >> "$cfg"
  fi
}

if has_kvm; then
  AVD_NAME="${BLACKNOTE_AVD:-blacknote_pixel7}"
  SYS_PKG='system-images;android-34;google_apis;x86_64'
  DEVICE_PROFILE='pixel_7'
  ACCEL_FLAGS="-gpu swiftshader_indirect -no-snapshot-save"
  BOOT_TIMEOUT="${BLACKNOTE_BOOT_TIMEOUT:-300}"
  echo "==> KVM available — API 34 google_apis, hardware CPU accel"
else
  AVD_NAME="${BLACKNOTE_AVD:-blacknote_api30}"
  SYS_PKG='system-images;android-30;default;x86_64'
  DEVICE_PROFILE='pixel_4'
  # ModemSimulator broken under some TCG hosts; watchdog multiplier prevents
  # system_server SIGKILL during slow first-boot.
  # zram enable-on-boot OOMs zygote under TCG (observed right after "Boot completed");
  # keep it off. More RAM + watchdog multiplier keep system_server alive.
  ACCEL_FLAGS="-no-accel -gpu swiftshader_indirect -no-snapshot -cores 2 -memory 4096 -feature -ModemSimulator -append-userspace-opt ro.hw_timeout_multiplier=50"
  BOOT_TIMEOUT="${BLACKNOTE_BOOT_TIMEOUT:-3600}"
  echo "==> No /dev/kvm — API 30 AOSP TCG (no ModemSimulator, zram patched off, watchdog x50, ${BOOT_TIMEOUT}s budget)"
fi

echo "==> Preparing emulator AVD ($AVD_NAME)"
mkdir -p "$AVD_HOME"

if ! has_kvm && [[ "$AVD_NAME" == "blacknote_api30" ]]; then
  ensure_api30_avd "$AVD_NAME"
else
  run_in_nix "
    set -euo pipefail
    export ANDROID_AVD_HOME=\"$AVD_HOME\"
    if ! avdmanager list avd 2>/dev/null | grep -q 'Name: $AVD_NAME'; then
      echo 'no' | avdmanager create avd \
        --force \
        --name '$AVD_NAME' \
        --package '$SYS_PKG' \
        --device '$DEVICE_PROFILE'
    fi
  "
fi

tune_avd_config "$AVD_HOME/${AVD_NAME}.avd/config.ini"

# Reuse a healthy already-running emulator when requested.
reuse_running=0
if [[ "${BLACKNOTE_REUSE_EMULATOR:-}" == "1" ]] || [[ "${BLACKNOTE_KEEP_EMULATOR:-}" == "1" ]]; then
  if [[ -f "$PID_FILE" ]]; then
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$old_pid" ]] && kill -0 "$old_pid" 2>/dev/null; then
      if run_in_nix "adb devices 2>/dev/null | grep -qE 'emulator-[0-9]+[[:space:]]+device'"; then
        EMU_PID="$old_pid"
        reuse_running=1
        echo "==> Reusing running emulator pid=$EMU_PID"
      fi
    fi
  fi
fi

if [[ "$reuse_running" -eq 0 ]]; then
  echo "==> Starting emulator (headless)"
  : > "$EMU_LOG"
  run_in_nix "
    export ANDROID_AVD_HOME=\"$AVD_HOME\"
    nohup emulator -avd '$AVD_NAME' -no-window -no-audio -no-boot-anim -no-metrics \
      $ACCEL_FLAGS \
      > '$EMU_LOG' 2>&1 &
    echo \$! > '$PID_FILE'
  "
  EMU_PID="$(cat "$PID_FILE")"
fi

echo "==> Waiting for device (pid=$EMU_PID, timeout=${BOOT_TIMEOUT}s)"
run_in_nix "
  set -euo pipefail
  for i in \$(seq 1 $BOOT_TIMEOUT); do
    if ! kill -0 '$EMU_PID' 2>/dev/null; then
      echo 'Emulator process exited before boot.' >&2
      tail -40 '$EMU_LOG' >&2 || true
      exit 1
    fi
    if grep -q 'requires hardware acceleration' '$EMU_LOG' 2>/dev/null; then
      echo 'Emulator failed: hardware acceleration required but unavailable.' >&2
      tail -20 '$EMU_LOG' >&2 || true
      exit 1
    fi
    if grep -q 'Unable to connect character device modem' '$EMU_LOG' 2>/dev/null; then
      if ! grep -q \"Feature 'ModemSimulator'\" '$EMU_LOG' 2>/dev/null; then
        echo 'ModemSimulator failed (::1). Re-run with fixed script (-feature -ModemSimulator).' >&2
        tail -20 '$EMU_LOG' >&2 || true
        exit 1
      fi
    fi
    if adb devices 2>/dev/null | grep -qE 'emulator-[0-9]+[[:space:]]+device'; then
      boot=\$(timeout 45 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
      anim=\$(timeout 45 adb shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r' || true)
      sdk=\$(timeout 45 adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)
      if [[ \"\$boot\" == '1' && -n \"\$sdk\" ]]; then
        echo \"Emulator booted (sys.boot_completed=1, sdk=\$sdk).\"
        adb shell input keyevent 82 >/dev/null 2>&1 || true
        exit 0
      fi
      # Under TCG, first-boot may leave boot_completed unset while PackageManager
      # is already usable. Also require settings provider (install needs it).
      if [[ \"\$anim\" == 'stopped' && -n \"\$sdk\" ]] \\
         && timeout 60 adb shell pm path android 2>/dev/null | grep -q package \\
         && timeout 30 adb shell settings get global device_provisioned >/dev/null 2>&1; then
        echo \"Emulator booted (bootanim stopped, pm+settings up, sdk=\$sdk).\"
        adb shell input keyevent 82 >/dev/null 2>&1 || true
        # Brief settle so PackageManagerInternal is published before install
        sleep 20
        exit 0
      fi
    fi
    if (( i % 60 == 0 )); then
      mins=\$((i / 60))
      state=\$(adb devices 2>/dev/null | awk '/emulator-/{print \$2}' || true)
      echo \"  ... still waiting (\${mins}m / $((BOOT_TIMEOUT / 60))m) adb=[\${state:-none}]\"
    fi
    sleep 1
  done
  echo 'Timed out waiting for emulator boot' >&2
  adb devices >&2 || true
  tail -50 '$EMU_LOG' >&2 || true
  exit 1
"

echo "==> Running instrumented tests"
# Prefer adb install + am instrument: AGP connectedDebugAndroidTest uses short
# ddmlib timeouts that report "Unknown API Level" under TCG even when adb works.
# Soft-reboot is intentionally skipped: under TCG it often leaves PackageManager
# half-dead (PackageManagerInternal null / Broken pipe).
run_in_nix "
  set -euo pipefail
  cd app
  gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
  APK=\$(find . -path '*/outputs/apk/debug/app-debug.apk' | head -1)
  TEST_APK=\$(find . -path '*/outputs/apk/androidTest/debug/app-debug-androidTest.apk' | head -1)
  if [[ -z \"\$APK\" || -z \"\$TEST_APK\" ]]; then
    echo 'APKs not found after assemble' >&2
    exit 1
  fi
  APP_SIZE=\$(stat -c%s \"\$APK\")
  TEST_SIZE=\$(stat -c%s \"\$TEST_APK\")
  echo \"Pushing \$APK (\$APP_SIZE bytes)\"
  adb push \"\$APK\" /data/local/tmp/app-debug.apk
  adb push \"\$TEST_APK\" /data/local/tmp/app-debug-androidTest.apk

  install_ok=0
  for attempt in \$(seq 1 40); do
    echo \"Install attempt \$attempt/40\"
    # Stream install avoids some PackageInstallerSession races under TCG
    out1=\$(timeout 300 adb shell \"cat /data/local/tmp/app-debug.apk | pm install -r -t -g -S \$APP_SIZE\" 2>&1) || true
    out2=\$(timeout 300 adb shell \"cat /data/local/tmp/app-debug-androidTest.apk | pm install -r -t -g -S \$TEST_SIZE\" 2>&1) || true
    echo \"  app: \$out1\"
    echo \"  test: \$out2\"
    if echo \"\$out1\" | grep -qi success && echo \"\$out2\" | grep -qi success; then
      install_ok=1
      break
    fi
    # Fall back to adb install
    timeout 300 adb install -r -t -g \"\$APK\" >/tmp/bn-adb-app.out 2>&1 || true
    timeout 300 adb install -r -t -g \"\$TEST_APK\" >/tmp/bn-adb-test.out 2>&1 || true
    if timeout 30 adb shell pm path com.marsglorious.blacknote 2>/dev/null | grep -q package \\
       && timeout 30 adb shell pm path com.marsglorious.blacknote.test 2>/dev/null | grep -q package; then
      install_ok=1
      break
    fi
    sleep 15
  done
  if [[ \"\$install_ok\" -ne 1 ]]; then
    echo 'Failed to install APKs after retries' >&2
    exit 1
  fi
  echo 'Packages installed.'
  adb shell pm list instrumentation || true

  # Retry instrumentation: system_server can crash under pure TCG when tests start
  for attempt in \$(seq 1 12); do
    echo \"Instrument attempt \$attempt/12\"
    set +e
    timeout 3600 adb shell am instrument -w -r -e debug false \\
      com.marsglorious.blacknote.test/androidx.test.runner.AndroidJUnitRunner \\
      | tee /tmp/blacknote-instrument-results.txt
    rc=\${PIPESTATUS[0]}
    set -e
    if grep -qE 'OK \\([0-9]+ tests?\\)' /tmp/blacknote-instrument-results.txt; then
      echo 'Instrumented suite reported OK'
      exit 0
    fi
    if grep -q 'FAILURES!!!' /tmp/blacknote-instrument-results.txt; then
      echo 'Instrumented suite reported failures' >&2
      exit 1
    fi
    if grep -qiE 'INSTRUMENTATION_ABORTED|System has crashed|Can.t connect to activity manager' /tmp/blacknote-instrument-results.txt; then
      echo 'System unstable; waiting 45s before retry'
      sleep 45
      continue
    fi
    exit \$rc
  done
  echo 'Instrumentation did not complete successfully' >&2
  exit 1
"

echo "==> Report: /tmp/blacknote-instrument-results.txt"
