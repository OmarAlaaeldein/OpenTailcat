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

observe_forwarding() {
  local phase=$1 remaining=$2 elapsed=0 delay_secs
  [[ "$remaining" =~ ^[0-9]+$ && "$remaining" -gt 0 ]]
  while (( remaining > 0 )); do
    delay_secs=$(( remaining < 15 ? remaining : 15 ))
    sleep "$delay_secs"
    remaining=$(( remaining - delay_secs ))
    elapsed=$(( elapsed + delay_secs ))
    if [[ "$phase" == screen-off ]]; then
      # Traffic probes may wake the CPU; the screen must stay asleep.
      adb shell dumpsys power | awk '
        /mWakefulness=Asleep/ { asleep = 1 }
        END { exit !asleep }
      '
    fi
    verify_forwarding "$phase+${elapsed}s"
  done
}

verify_forwarding before
adb shell input keyevent KEYCODE_HOME
observe_forwarding background "$BACKGROUND_SECS"
adb shell input keyevent KEYCODE_SLEEP
observe_forwarding screen-off "$SCREEN_OFF_SECS"
echo "PASS: background and screen-off forwarding"
