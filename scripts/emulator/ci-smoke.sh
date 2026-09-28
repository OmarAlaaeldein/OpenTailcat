#!/usr/bin/env bash
# CI emulator smoke test (no gateway, no secrets): installs the debug and
# test APKs, runs VpnStartupInstrumentedTest (consent handling and fail-closed
# startup with a synthetic token), and checks that the home screen renders.
# Needs ./gradlew assembleDebug assembleDebugAndroidTest first.
set -euo pipefail
DIR="$(dirname "$0")"
source "$DIR/lib.sh"

"$DIR/start.sh"
install_test_apk
run_instrumented com.tailcat.vpn.VpnStartupInstrumentedTest

adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
for _ in $(seq 1 10); do
  if ui_nodes | grep -q '| OpenTailcat |'; then
    echo "PASS: emulator smoke (home screen: $(tunnel_state))"
    exit 0
  fi
  sleep 2
done
echo "FAIL: the home screen did not render" >&2
ui_nodes | head -20 >&2
exit 1
