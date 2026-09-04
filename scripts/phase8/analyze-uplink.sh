#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UPLINK="${1:-}"
PROBES="${2:-1.1.1.1,8.8.8.8,9.9.9.9}"
GATEWAY="${3:-}"

if [[ -z "$UPLINK" ]]; then
  echo "usage: analyze-uplink.sh uplink.pcap [probe,ips] [gateway.pcap]" >&2
  exit 2
fi

ARGS=(--uplink "$UPLINK" --probe "$PROBES")
if [[ -n "$GATEWAY" ]]; then
  ARGS+=(--gateway "$GATEWAY")
fi
exec go run -C "$ROOT/core-engine" ./cmd/phase8-analyze "${ARGS[@]}"
