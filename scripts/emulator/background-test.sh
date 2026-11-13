#!/usr/bin/env bash
# Keep the UI out of the foreground, then turn the screen off. Verify the
# foreground VPN service still routes and forwards real traffic in each phase.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
BACKGROUND_SECS=${BACKGROUND_SECS:-120}
SCREEN_OFF_SECS=${SCREEN_OFF_SECS:-120}

restore_ui() {
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
}
trap restore_ui EXIT

verify_forwarding() {
  local phase=$1 route response state
  route=$(route_dev)
  response=$(tcp_probe)
  # Read notification health without resuming the app. Do not print profile
  # names or credential-bearing UI data.
  state=$(adb shell dumpsys notification --noredact | awk '
    /android.title=String \(OpenTailcat: Connected/ { state = "CONNECTED" }
    /android.title=String \(OpenTailcat: Tunnel Degraded/ { state = "DEGRADED" }
    END { print state }
  ')
  echo "$phase: state=${state:-inactive}, route=${route:-none}, TCP=${response:-none}"
  [[ "$route" == tun* && "$response" == HTTP/* && -n "$state" ]]
}

verify_forwarding before
adb shell input keyevent KEYCODE_HOME
sleep "$BACKGROUND_SECS"
verify_forwarding background
adb shell input keyevent KEYCODE_SLEEP
sleep "$SCREEN_OFF_SECS"
verify_forwarding screen-off
echo "PASS: background and screen-off forwarding"
