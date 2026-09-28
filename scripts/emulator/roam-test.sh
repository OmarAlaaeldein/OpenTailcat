#!/usr/bin/env bash
# Wi-Fi <-> cellular roaming check (review P1-9), with OpenTailcat connected.
# Turns Wi-Fi off, then on again. After each switch it checks that
#  - Android told the engine the new default interface and the engine's
#    netmon saw a major link change (Magicsock rebind), and
#  - the shell uid still routes through the VPN and TCP through it works,
#    without the 60 s gateway-loss reconnect.
# Needs a device with both Wi-Fi and mobile data. Leaves Wi-Fi on.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
SETTLE=${SETTLE:-12}

if [[ "$(route_dev)" != tun* ]]; then
  echo "VPN is not up; run scripts/emulator/connect.sh first" >&2
  exit 2
fi
echo "before: route $(route_dev), tcp: $(tcp_probe)"

fail=0
switch() {
  local label=$1 action=$2
  adb logcat -c
  adb shell svc wifi "$action"
  sleep "$SETTLE"
  local log rebinds reason defif dev tcp state
  log=$(adb logcat -d -s GoLog | tr -d '\r')
  rebinds=$(grep -c "LinkChange: major, rebinding" <<<"$log" || true)
  reason=$(grep -o "rebind-reason=\[[^]]*\]" <<<"$log" | tail -1 || true)
  defif=$(grep -o "defaultroute: update from Android, ifName = [^ ]*" <<<"$log" | tail -1 | sed 's/.*ifName = //' || true)
  dev=$(route_dev)
  tcp=$(tcp_probe)
  state=$(tunnel_state)
  echo "[$label] default if: ${defif:-none} | major rebinds: $rebinds ${reason} | route: ${dev:-?} | tcp: ${tcp:-none} | app: ${state:-?}"
  if [ "$rebinds" -lt 1 ] || [[ "$dev" != tun* ]] || [[ "$tcp" != HTTP/* ]]; then
    fail=1
  fi
}

switch "wifi off" disable
switch "wifi on" enable

if [ "$fail" = 0 ]; then echo "PASS"; else echo "FAIL"; fi
exit "$fail"
