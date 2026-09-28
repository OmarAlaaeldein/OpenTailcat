#!/usr/bin/env bash
set -euo pipefail

# Run on the gateway where decrypted phone traffic leaves (classic pcap,
# headers only). The filter keeps only traffic to or from the probe IPs.
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=capture-common.sh
source "$DIR/capture-common.sh"

IFACE="${CAPTURE_IFACE:-eth0}"
OUT="${1:-captures/gateway.pcap}"
PROBES="${PROBE_IPS:-1.1.1.1 8.8.8.8 9.9.9.9}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<EOF
usage: CAPTURE_IFACE=eth0 [PROBE_IPS="1.1.1.1 8.8.8.8"] $0 [captures/gateway.pcap]
  CAPTURE_SECONDS   stop after N seconds
  CAPTURE_PACKETS   stop after N packets
  CAPTURE_SNAPLEN   bytes kept per packet (default $SNAPLEN: headers only)
  CAPTURE_FILTER    BPF filter (default: host <each probe IP>)
Start this before generate-probes.sh so gateway and uplink overlap.
Classic pcap only — not pcapng.
EOF
  exit 0
fi

FILTER="${CAPTURE_FILTER:-}"
if [[ -z "$FILTER" ]]; then
  for ip in $PROBES; do
    FILTER="${FILTER:+$FILTER or }host $ip"
  done
fi
run_capture "$IFACE" "$OUT" "$FILTER"
