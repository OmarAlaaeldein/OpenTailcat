#!/usr/bin/env bash
# One-shot Mac host capture (prompts for Mac admin password via sudo).
# Uplink view: what arrives/leaves en0 while the phone probes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/captures/mac-en0.pcap}"
SECONDS_LIMIT="${CAPTURE_SECONDS:-60}"
mkdir -p "$(dirname "$OUT")"
echo "Capturing en0 for ${SECONDS_LIMIT}s -> $OUT (enter Mac password if prompted)"
sudo tcpdump -i en0 -s 0 -w "$OUT" "not port 22" &
PID=$!
sleep "$SECONDS_LIMIT"
sudo kill "$PID" 2>/dev/null || kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true
ls -la "$OUT"
echo "done"
