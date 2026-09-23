#!/usr/bin/env bash
set -euo pipefail

# Classic pcap only (-F pcap). Optional duration/size bounds for evidence hygiene.
IFACE="${CAPTURE_IFACE:-en0}"
OUT="${1:-captures/uplink.pcap}"
SECONDS_LIMIT="${CAPTURE_SECONDS:-}"
PKTS_LIMIT="${CAPTURE_PACKETS:-}"
mkdir -p "$(dirname "$OUT")"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<EOF
usage: CAPTURE_IFACE=en0 $0 [captures/uplink.pcap]
  CAPTURE_SECONDS   stop after N seconds (recommended: 30-60)
  CAPTURE_PACKETS   stop after N packets
macOS BPF: brew install --cask wireshark-chmodbpf
Classic pcap only — do not use pcapng (phase8-analyze rejects it).
PCAPdroid on the phone is a second VPN and is not valid uplink evidence.
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
  echo "tshark: iface=$IFACE out=$OUT seconds=${SECONDS_LIMIT:-none} packets=${PKTS_LIMIT:-none}" >&2
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
