#!/usr/bin/env bash
set -euo pipefail

# Run on the gateway (or next hop). Classic pcap only. Same bounds as capture-uplink.
IFACE="${CAPTURE_IFACE:-eth0}"
OUT="${1:-captures/gateway.pcap}"
SECONDS_LIMIT="${CAPTURE_SECONDS:-}"
PKTS_LIMIT="${CAPTURE_PACKETS:-}"
mkdir -p "$(dirname "$OUT")"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<EOF
usage: CAPTURE_IFACE=eth0 $0 [captures/gateway.pcap]
  CAPTURE_SECONDS   stop after N seconds
  CAPTURE_PACKETS   stop after N packets
Start this before generate-probes.sh so gateway and uplink overlap.
Classic pcap only — not pcapng.
EOF
  exit 0
fi

if [[ -n "$SECONDS_LIMIT" && -n "$PKTS_LIMIT" ]]; then
  echo "set only CAPTURE_SECONDS or CAPTURE_PACKETS, not both" >&2
  exit 2
fi

if command -v tshark >/dev/null 2>&1; then
  args=(-i "$IFACE" -F pcap -w "$OUT")
  if [[ -n "$SECONDS_LIMIT" ]]; then
    args+=(-a "duration:$SECONDS_LIMIT")
  fi
  if [[ -n "$PKTS_LIMIT" ]]; then
    args+=(-c "$PKTS_LIMIT")
  fi
  echo "tshark: iface=$IFACE out=$OUT" >&2
  exec tshark "${args[@]}"
fi
if command -v tcpdump >/dev/null 2>&1; then
  if [[ -n "$SECONDS_LIMIT" ]]; then
    exec timeout "$SECONDS_LIMIT" tcpdump -i "$IFACE" -n -w "$OUT"
  fi
  args=(-i "$IFACE" -n -w "$OUT")
  if [[ -n "$PKTS_LIMIT" ]]; then
    args+=(-c "$PKTS_LIMIT")
  fi
  exec tcpdump "${args[@]}"
fi
echo "need tshark or tcpdump" >&2
exit 1
