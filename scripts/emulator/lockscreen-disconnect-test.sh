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
  # Expand the OpenTailcat notification to reveal its action. Other
  # notifications ("Set a screen lock", ...) have Expand buttons too, so pick
  # the one whose row holds the OpenTailcat title.
  title_y=$(ui_nodes | sed -n -E 's/^\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\] \| OpenTailcat: .*/\1 \2/p' | head -1)
  if [ -n "$title_y" ]; then
    read -r t1 t2 <<<"$title_y"
    mid=$(( (t1 + t2) / 2 ))
    expand=$(ui_nodes | sed -n -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] clickable \|  \| Expand$/\1 \2 \3 \4/p' |
      awk -v y="$mid" '$2 <= y && y <= $4 { print int(($1 + $3) / 2), int(($2 + $4) / 2); exit }')
    [ -n "$expand" ] && adb shell input tap $expand
  fi
  sleep 2
fi
if ! tap_node "\| Disconnect \|"; then
  echo "FAIL: no Disconnect action on the lock screen"
  exit 1
fi
sleep 3
# The bouncer's own title; the "Set a screen lock" notification also says PIN.
bouncer=$(ui_nodes | grep -E "\| Enter (your )?PIN \|" | head -1 || true)
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
