#!/usr/bin/env bash
# Lock-screen Disconnect check (review P1-12), with OpenTailcat connected.
# Sets a temporary PIN, locks the screen, taps Disconnect on the VPN
# notification, and expects the PIN prompt with the VPN still up. It then
# enters the PIN, expects the VPN to go down, and removes the PIN again.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
PIN=1111

if [[ "$(route_dev)" != tun* ]]; then
  echo "VPN is not up; run scripts/emulator/connect.sh first" >&2
  exit 2
fi

# The VPN notification is low importance; Android hides those on the lock
# screen unless this is on. Restore the previous value afterwards.
old_silent=$(adb shell settings get secure lock_screen_show_silent_notifications | tr -d '\r')
cleanup() {
  adb shell locksettings clear --old "$PIN" >/dev/null 2>&1 || true
  if [ "$old_silent" = null ]; then
    adb shell settings delete secure lock_screen_show_silent_notifications >/dev/null 2>&1 || true
  else
    adb shell settings put secure lock_screen_show_silent_notifications "$old_silent" || true
  fi
}
trap cleanup EXIT
adb shell settings put secure lock_screen_show_silent_notifications 1

adb shell cmd statusbar collapse
adb shell locksettings set-pin "$PIN" >/dev/null
adb shell input keyevent 26   # screen off: locks
sleep 2
adb shell input keyevent 26   # screen on: lock screen
sleep 2
adb shell cmd statusbar expand-notifications
sleep 2
if ! ui_nodes | grep -q "| Disconnect |"; then
  # Expand the OpenTailcat notification to reveal its action.
  tap_node "Expand" || true
  sleep 2
fi
if ! tap_node "\| Disconnect \|"; then
  echo "FAIL: no Disconnect action on the lock screen"
  exit 1
fi
sleep 3
bouncer=$(ui_nodes | grep -i -E "PIN|Enter|password" | head -1 || true)
locked_dev=$(route_dev)
echo "after tap: prompt: ${bouncer:-none} | route while locked: ${locked_dev:-?}"

adb shell input text "$PIN"
adb shell input keyevent 66
sleep 4
after_dev=$(route_dev)
echo "after unlock: route ${after_dev:-?}"

if [ -n "$bouncer" ] && [[ "$locked_dev" == tun* ]] && [[ "$after_dev" != tun* ]]; then
  echo "PASS"
else
  echo "FAIL"
  exit 1
fi
