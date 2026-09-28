#!/usr/bin/env bash
# Boot the test AVD headless when no device is attached, wait for boot, and
# install the debug APK if one is built. AVD defaults to OpenTailcat_API35;
# the emulator log goes to build/emulator.log.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
AVD=${AVD:-OpenTailcat_API35}

if ! adb devices | sed -n '2,$p' | grep -qw device; then
  mkdir -p "$ROOT/build"
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim >"$ROOT/build/emulator.log" 2>&1 &
  echo "starting $AVD (log: build/emulator.log)"
fi
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
echo "booted: API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $ABI"

# The debug build is split per ABI.
APK="$ROOT/app/build/outputs/apk/debug/app-$ABI-debug.apk"
if [ -f "$APK" ]; then
  adb install -r "$APK" >/dev/null
  echo "installed $(basename "$APK")"
else
  echo "no $(basename "$APK"); run ./gradlew assembleDebug to install one"
fi
