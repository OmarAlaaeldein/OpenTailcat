#!/usr/bin/env bash
set -euo pipefail

IFACE="${CAPTURE_IFACE:-eth0}"
OUT="${1:-captures/gateway.pcap}"
mkdir -p "$(dirname "$OUT")"

if command -v tshark >/dev/null 2>&1; then
  exec tshark -i "$IFACE" -F pcap -w "$OUT"
fi
if command -v tcpdump >/dev/null 2>&1; then
  exec tcpdump -i "$IFACE" -n -w "$OUT"
fi
echo "need tshark or tcpdump" >&2
exit 1
