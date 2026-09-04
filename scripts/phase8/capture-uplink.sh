#!/usr/bin/env bash
set -euo pipefail

IFACE="${CAPTURE_IFACE:-en0}"
OUT="${1:-captures/uplink.pcap}"
mkdir -p "$(dirname "$OUT")"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "usage: CAPTURE_IFACE=en0 $0 [captures/uplink.pcap]"
  echo "macOS BPF: brew install --cask wireshark-chmodbpf"
  exit 0
fi

if command -v tshark >/dev/null 2>&1; then
  exec tshark -i "$IFACE" -F pcap -w "$OUT"
fi
if command -v tcpdump >/dev/null 2>&1; then
  exec tcpdump -i "$IFACE" -n -w "$OUT"
fi
echo "need tshark or tcpdump" >&2
exit 1
