#!/usr/bin/env bash
# Open the app, tap Connect if it is disconnected, and wait up to 60 s for
# CONNECTED. The app must already hold a gateway profile (no token is read
# here). Exits 0 once connected, 1 otherwise.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
sleep 2
if ui_nodes | grep -q "TAP TO CONNECT"; then
  # The caption is not clickable; the round button above it is.
  tap_node "clickable .*\| Connect VPN$"
  sleep 2
  # First connect after install: accept the system VPN consent dialog.
  tap_node '\| OK \|' 2>/dev/null || true
fi
for _ in $(seq 1 15); do
  state=$(tunnel_state)
  echo "state: ${state:-?}"
  if [ "$state" = CONNECTED ]; then
    echo "shell uid route: $(route_dev)"
    exit 0
  fi
  sleep 4
done
exit 1
