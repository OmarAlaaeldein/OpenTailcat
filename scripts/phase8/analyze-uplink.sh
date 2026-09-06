#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UPLINK="${1:-}"
PROBES="${2:-1.1.1.1,8.8.8.8,9.9.9.9}"
GATEWAY="${3:-}"

if [[ -z "$UPLINK" || -z "$GATEWAY" ]]; then
  echo "usage: analyze-uplink.sh uplink.pcap [probe,ips] gateway.pcap" >&2
  echo "gateway capture is required (AUDIT H7)" >&2
  exit 2
fi

exec go run -C "$ROOT/core-engine" ./cmd/phase8-analyze --uplink "$UPLINK" --probe "$PROBES" --gateway "$GATEWAY"
