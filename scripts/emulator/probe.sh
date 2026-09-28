#!/usr/bin/env bash
# Build the data-plane probe (scripts/emulator/probe) for the device, push it,
# and run it from the shell uid, which is routed through the VPN while it is
# connected. Arguments go to the probe, e.g.:
#   probe.sh burst 400            # UDP flow table: every query answered?
#   probe.sh stun 1200 1232 1236  # tunnel payload limit and PMTU errors
#   probe.sh halfclose 1.1.1.1    # TCP half-close still delivers the reply
set -euo pipefail
source "$(dirname "$0")/lib.sh"

case "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')" in
  arm64-v8a) arch=arm64 ;;
  x86_64) arch=amd64 ;;
  *) echo "unsupported device ABI" >&2; exit 2 ;;
esac
mkdir -p "$ROOT/build"
(cd "$ROOT/scripts/emulator/probe" && CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -o "$ROOT/build/otc-probe" .)
adb push "$ROOT/build/otc-probe" /data/local/tmp/otc-probe >/dev/null 2>&1
adb shell chmod 755 /data/local/tmp/otc-probe
adb shell /data/local/tmp/otc-probe "$@"
