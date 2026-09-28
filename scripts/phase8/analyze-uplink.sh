#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UPLINK="${1:-}"
PROBES="${2:-1.1.1.1,8.8.8.8,9.9.9.9}"
GATEWAY="${3:-}"

usage() {
  cat >&2 <<EOF
usage: DEVICE_IP=phone-ip TUNNEL_PEERS=gateway-or-derp-ip[,...] \\
       analyze-uplink.sh uplink.pcap [probe,ips] gateway.pcap [phase8-analyze flags]
  DEVICE_IP      the phone's address(es) on the uplink capture (comma list)
  TUNNEL_PEERS   gateway and DERP endpoint IPs the tunnel uses (comma list)
  GATEWAY_SRC    optional: gateway tunnel-side source IP(s) of probe traffic
Exit: 0 PASS, 1 FAIL, 2 usage/capture error, 3 INCONCLUSIVE (not a pass).
EOF
}

if [[ "$UPLINK" == "-h" || "$UPLINK" == "--help" ]]; then
  usage
  exit 0
fi
if [[ -z "$UPLINK" || -z "$GATEWAY" ]]; then
  usage
  echo "gateway capture is required (AUDIT H7)" >&2
  exit 2
fi
if [[ -z "${DEVICE_IP:-}" || -z "${TUNNEL_PEERS:-}" ]]; then
  usage
  echo "DEVICE_IP and TUNNEL_PEERS are required for the positive control" >&2
  exit 2
fi
shift 3

args=(--uplink "$UPLINK" --probe "$PROBES" --gateway "$GATEWAY"
  --device-ip "$DEVICE_IP" --tunnel-peer "$TUNNEL_PEERS")
if [[ -n "${GATEWAY_SRC:-}" ]]; then
  args+=(--gateway-src "$GATEWAY_SRC")
fi
# Build instead of `go run`, which reports every non-zero exit as 1.
BIN_DIR="$(mktemp -d)"
trap 'rm -rf "$BIN_DIR"' EXIT
go build -C "$ROOT/core-engine" -o "$BIN_DIR/phase8-analyze" ./cmd/phase8-analyze || exit 2
"$BIN_DIR/phase8-analyze" "${args[@]}" "$@"
