#!/usr/bin/env bash
# Open the app, tap Connect if it is disconnected, and wait up to 60 s for
# CONNECTED. The app must already hold a gateway profile (no token is read
# here). Exits 0 once connected, 1 otherwise.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
# Wait for the actual connect control, not just any visible UI. During startup
# the telemetry card can report DISCONNECTED before the connect label appears.
# A failed or incomplete dump must not cause us to skip the tap.
ready=""
for _ in $(seq 1 10); do
  nodes=$(ui_nodes || true)
  # The launcher can ANR on slow CI emulators and obscure our activity.
  # Only dismiss this specific system dialog, then reopen the app.
  if printf '%s\n' "$nodes" | grep -q "Pixel Launcher isn't responding"; then
    echo "Closing stalled emulator launcher"
    tap_node '\| Close app \|' || true
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
    sleep 2
    continue
  fi
  if printf '%s\n' "$nodes" | grep -q "TAP TO CONNECT"; then
    ready=disconnected
    break
  fi
  if printf '%s\n' "$nodes" | grep -Eq '\| (CONNECTED|ESTABLISHING TUNNEL|RECONNECTING)'; then
    ready=active
    break
  fi
  sleep 2
done
if [ "$ready" = disconnected ]; then
  # Tap the round button above the caption. Compose 1.12 puts its label on a
  # child of the clickable node (same bounds), so match the label itself.
  echo "Tapping Connect VPN"
  tap_node "\| Connect VPN$"
  sleep 2
  # First connect after install: answer the notification permission prompt (the
  # app asks for it before the VPN consent screen), then accept the system VPN
  # consent dialog. Both are absent when the permission is already granted.
  tap_node '\| Allow \|' 2>/dev/null || true
  sleep 1
  tap_node '\| OK \|' 2>/dev/null || true
elif [ -z "$ready" ]; then
  echo "Connect control did not become ready" >&2
  # Report only fixed, allowlisted UI facts; do not print arbitrary screen text.
  if [ -z "$nodes" ]; then echo "UI diagnostic: no visible nodes"; fi
  if printf '%s\n' "$nodes" | grep -qi "isn't responding"; then echo "UI diagnostic: application not responding dialog"; fi
  if printf '%s\n' "$nodes" | grep -qi 'keeps stopping'; then echo "UI diagnostic: application stopped dialog"; fi
  if printf '%s\n' "$nodes" | grep -q 'OpenTailcat'; then echo "UI diagnostic: app visible"; fi
  if printf '%s\n' "$nodes" | grep -qi 'OFFLINE\|NO INTERNET'; then echo "UI diagnostic: device offline"; fi
  if printf '%s\n' "$nodes" | grep -qi 'ENGINE REQUIRED\|VPN DISABLED'; then echo "UI diagnostic: engine unavailable"; fi
  if printf '%s\n' "$nodes" | grep -q 'Connect VPN'; then echo "UI diagnostic: connect control visible"; fi
  if printf '%s\n' "$nodes" | grep -qi 'permission\|allow'; then echo "UI diagnostic: permission prompt or message visible"; fi
fi
# Print only fixed diagnostic categories, never arbitrary UI/error text: a
# gateway error may contain a token, endpoint, or other private information.
reported_diagnostic=""
for _ in $(seq 1 15); do
  nodes=$(ui_nodes || true)
  state=$(printf '%s\n' "$nodes" | sed -n -E 's/^[^|]*\| ((CONNECTED|CONNECTING|DISCONNECTED|RECONNECTING|DEGRADED|TAP TO CONNECT)[^|]*) \|.*/\1/p' | head -1 || true)
  diagnostic=""
  if printf '%s\n' "$nodes" | grep -qi 'no validated internet connection\|no internet connection\|OFFLINE.*NO INTERNET'; then
    diagnostic="no validated internet"
  elif printf '%s\n' "$nodes" | grep -qi 'expired'; then
    diagnostic="token expired"
  elif printf '%s\n' "$nodes" | grep -qi 'gateway handshake failed'; then
    diagnostic="gateway handshake failed"
  elif printf '%s\n' "$nodes" | grep -qi 'timed out\|timeout\|deadline exceeded'; then
    diagnostic="network timeout"
  elif printf '%s\n' "$nodes" | grep -qi 'VPN permission'; then
    diagnostic="VPN permission problem"
  elif printf '%s\n' "$nodes" | grep -qi 'engine unavailable\|engine is unavailable\|VPN engine failed\|engine did not become live\|ENGINE REQUIRED'; then
    diagnostic="VPN engine failure"
  fi
  if [ -n "$diagnostic" ] && [ "$diagnostic" != "$reported_diagnostic" ]; then
    echo "diagnostic: $diagnostic"
    reported_diagnostic=$diagnostic
  fi
  echo "state: ${state:-?}"
  if [ "$state" = CONNECTED ]; then
    echo "shell uid route: $(route_dev)"
    exit 0
  fi
  sleep 4
done
exit 1
