#!/usr/bin/env bash
# One-shot uplink capture on a Mac that shares its Internet connection to the
# phone (System Settings > General > Sharing > Internet Sharing), so the
# phone's traffic crosses bridge100. The Mac's en0 on an ordinary Wi-Fi LAN
# does not see the phone's frames. Prompts for the Mac admin password.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/captures/uplink.pcap}"
export CAPTURE_TOPOLOGY=internet-sharing
export CAPTURE_SECONDS="${CAPTURE_SECONDS:-60}"
exec "$ROOT/scripts/phase8/capture-uplink.sh" "$OUT"
