#!/usr/bin/env bash
# Generate probe traffic from an app UID while both captures run: each probe
# IP is opened as https://IP/ with a VIEW intent, so a browser app (not adb
# shell, uid 2000, and not the VPN app) makes the connections. The browser
# must not be excluded from the VPN.
# Not a leak pass by itself — pair with capture-uplink/gateway + analyze-uplink.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
PROBES="${PROBE_IPS:-1.1.1.1 8.8.8.8 9.9.9.9}"
ROUNDS="${PROBE_ROUNDS:-3}"
PACKAGE="${PROBE_PACKAGE:-}"
DWELL="${PROBE_DWELL:-3}"

usage() {
  cat <<EOF
usage: PROBE_IPS="1.1.1.1 8.8.8.8" $0
  ANDROID_SERIAL  optional adb device
  PROBE_ROUNDS    page loads per IP (default $ROUNDS)
  PROBE_PACKAGE   browser package that opens the URLs (default: the phone's default browser)
  PROBE_DWELL     seconds to wait after each load (default $DWELL)

Run while both captures are already recording. Supported uplink captures:
Mac Internet Sharing (bridge100), an AP/switch mirror port, or a rooted
phone's wlan0 (see capture-uplink.sh -h); never a Mac's en0 on a shared LAN.
  DEVICE_IP=<phone IP> CAPTURE_SECONDS=60 scripts/phase8/capture-uplink.sh captures/uplink.pcap &
  # on the gateway:
  CAPTURE_IFACE=eth0 CAPTURE_SECONDS=60 scripts/phase8/capture-gateway.sh captures/gateway.pcap &
  scripts/phase8/generate-probes.sh
  wait
  DEVICE_IP=<phone IP> TUNNEL_PEERS=<gateway/DERP IPs> \\
    scripts/phase8/analyze-uplink.sh captures/uplink.pcap 1.1.1.1,8.8.8.8,9.9.9.9 captures/gateway.pcap
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

for ip in $PROBES; do
  if [[ ! "$ip" =~ ^[0-9A-Fa-f.:]+$ ]]; then
    echo "invalid probe IP: $ip" >&2
    exit 2
  fi
done
if [[ -n "$PACKAGE" && ! "$PACKAGE" =~ ^[A-Za-z0-9_.]+$ ]]; then
  echo "invalid PROBE_PACKAGE: $PACKAGE" >&2
  exit 2
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "no device (set ANDROID_SERIAL)" >&2
  exit 1
fi

pkg_arg=""
if [[ -n "$PACKAGE" ]]; then
  pkg_arg="-p $PACKAGE"
fi

echo "==> app-UID probes via VIEW intents (${PACKAGE:-default browser})"
echo "    probes: $PROBES rounds $ROUNDS"
for _ in $(seq "$ROUNDS"); do
  for ip in $PROBES; do
    url="https://$ip/"
    if [[ "$ip" == *:* ]]; then
      url="https://[$ip]/"
    fi
    if ! "${ADB[@]}" shell "am start -W -a android.intent.action.VIEW -d '$url' $pkg_arg" >/dev/null; then
      echo "    warning: could not open $url" >&2
    fi
    sleep "$DWELL"
  done
done

echo "==> probes finished; stop captures, then run:"
echo "    DEVICE_IP=<phone IP> TUNNEL_PEERS=<gateway/DERP IPs> scripts/phase8/analyze-uplink.sh captures/uplink.pcap \"${PROBES// /,}\" captures/gateway.pcap"
# A page that fails to load is a warning for the capture operator, not an
# analyzer result.
exit 0
