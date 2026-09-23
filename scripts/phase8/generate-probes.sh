#!/usr/bin/env bash
# Generate second-UID (adb shell, uid 2000) probe traffic while dual captures run.
# Shell UID is not the VPN app UID; traffic must take the path under test.
# Not a leak pass by itself — pair with capture-uplink/gateway + analyze-uplink.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
PROBES="${PROBE_IPS:-1.1.1.1 8.8.8.8 9.9.9.9}"
PORT="${PROBE_PORT:-443}"
ROUNDS="${PROBE_ROUNDS:-3}"

usage() {
  cat <<EOF
usage: PROBE_IPS="1.1.1.1 8.8.8.8" $0
  ANDROID_SERIAL  optional adb device
  PROBE_ROUNDS    connect attempts per IP (default $ROUNDS)
  PROBE_PORT      TCP port (default $PORT)

Run while both captures are already recording:
  CAPTURE_IFACE=en0 scripts/phase8/capture-uplink.sh captures/uplink.pcap &
  CAPTURE_IFACE=eth0 scripts/phase8/capture-gateway.sh captures/gateway.pcap &
  scripts/phase8/generate-probes.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "no device (set ANDROID_SERIAL)" >&2
  exit 1
fi

echo "==> second-UID probes from adb shell (not com.tailcat.vpn)"
echo "    probes: $PROBES port $PORT rounds $ROUNDS"
fail=0
for ip in $PROBES; do
  ok=0
  for _ in $(seq "$ROUNDS"); do
    # toybox nc; -w bounds connect. Redirect keeps stdin clean.
    if "${ADB[@]}" shell "nc -w 3 $ip $PORT </dev/null" >/dev/null 2>&1; then
      ok=$((ok + 1))
    fi
    sleep 0.2
  done
  echo "    $ip: $ok/$ROUNDS connects"
  if [[ $ok -eq 0 ]]; then
    fail=1
    echo "    warning: no successful connect to $ip (gateway/path may be down)" >&2
  fi
done

echo "==> probes finished; stop captures, then run:"
echo "    scripts/phase8/analyze-uplink.sh captures/uplink.pcap \"${PROBES// /,}\" captures/gateway.pcap"
# Zero connects is a warning for the capture operator, not an analyzer result.
exit 0
