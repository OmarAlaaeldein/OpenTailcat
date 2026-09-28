#!/usr/bin/env bash
# CI live gateway suite. Pairs the gateway from OTC_LIVE_TOKEN (never printed),
# connects, and runs every emulator check; fails if any check failed. Needs
# the debug and androidTest APKs and Go (for the probe). The exit IP may equal
# the runner's IP, so the checks use routes, logs and counters instead.
set -uo pipefail
DIR="$(dirname "$0")"
source "$DIR/lib.sh"

failed=()
check() {
  local name=$1
  shift
  echo "::group::$name"
  if "$@"; then
    echo "PASS $name"
  else
    echo "FAIL $name"
    failed+=("$name")
  fi
  echo "::endgroup::"
}

probe_payload_limit() {
  local out
  out=$("$DIR/probe.sh" stun 1232 1236)
  echo "$out"
  grep -q 'payload=1232 B: reply' <<<"$out" && grep -q 'payload=1236 B: .*message too long' <<<"$out"
}

probe_half_close() {
  local out
  out=$("$DIR/probe.sh" halfclose 1.1.1.1)
  echo "$out"
  grep -q 'status "HTTP/1' <<<"$out"
}

probe_dns_burst() {
  local out answered
  out=$("$DIR/probe.sh" burst 20)
  echo "$out"
  answered=$(sed -n 's/.*answered=\([0-9]*\).*/\1/p' <<<"$out")
  [ "${answered:-0}" -ge 15 ]
}

reconnect() {
  "$DIR/connect.sh" >/dev/null
}

"$DIR/start.sh" || exit 1
if [ -n "${OTC_LIVE_TOKEN:-}" ]; then
  "$DIR/pair.sh" || { echo "FAIL pairing"; exit 1; }
else
  # Local runs: use the gateway already paired in the app.
  echo "OTC_LIVE_TOKEN is not set; using the gateway already paired in the app"
  adb shell appops set "$PKG" ACTIVATE_VPN allow
fi
"$DIR/connect.sh" || { echo "FAIL connect"; exit 1; }

check "log privacy" "$DIR/log-privacy-check.sh"
check "tunnel UDP payload limit" probe_payload_limit
check "TCP half-close" probe_half_close
check "UDP DNS burst" probe_dns_burst
check "Wi-Fi/cellular roaming" "$DIR/roam-test.sh"
check "gateway loss and reconnect" "$DIR/gateway-loss-test.sh"
adb wait-for-device
check "reconnect after gateway loss" reconnect
# Last: it ends with the VPN disconnected.
check "lock-screen Disconnect" "$DIR/lockscreen-disconnect-test.sh"

if [ "${#failed[@]}" -gt 0 ]; then
  printf 'failed: %s\n' "${failed[@]}"
  exit 1
fi
echo "all live checks passed"
