#!/usr/bin/env bash
set -euo pipefail

# Capture the phone's uplink (classic pcap, headers only, the phone's traffic
# only). A Mac's en0 on an ordinary Wi-Fi LAN does not see another device's
# unicast frames, so only these topologies are supported:
#   internet-sharing  the Mac shares its connection to the phone; capture bridge100
#   mirror            a capture host on an AP/switch mirror (SPAN) port
#   rooted-wlan0      tcpdump on a rooted phone's wlan0 over adb
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=capture-common.sh
source "$DIR/capture-common.sh"

TOPOLOGY="${CAPTURE_TOPOLOGY:-internet-sharing}"
OUT="${1:-captures/uplink.pcap}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<EOF
usage: DEVICE_IP=phone-ip [CAPTURE_TOPOLOGY=internet-sharing|mirror|rooted-wlan0] $0 [captures/uplink.pcap]
  CAPTURE_TOPOLOGY  internet-sharing (default, iface bridge100), mirror
                    (set CAPTURE_IFACE to the mirror-port interface), or
                    rooted-wlan0 (tcpdump on the phone; ANDROID_SERIAL optional)
  DEVICE_IP         the phone's address; the filter records only its traffic
                    (required except for rooted-wlan0)
  CAPTURE_IFACE     capture interface (default bridge100 for internet-sharing)
  CAPTURE_SECONDS   stop after N seconds (recommended: 30-60)
  CAPTURE_PACKETS   stop after N packets
  CAPTURE_SNAPLEN   bytes kept per packet (default $SNAPLEN: headers only)
macOS BPF: brew install --cask wireshark-chmodbpf
Classic pcap only — do not use pcapng (phase8-analyze rejects it).
PCAPdroid on the phone is a second VPN and is not valid uplink evidence.
phase8-analyze reports INCONCLUSIVE when the capture never saw the phone's
tunnel traffic during the probes.
EOF
  exit 0
fi

need_device_ip() {
  if [[ -z "${DEVICE_IP:-}" ]]; then
    echo "set DEVICE_IP to the phone's address so only its traffic is recorded" >&2
    exit 2
  fi
}

case "$TOPOLOGY" in
  internet-sharing)
    need_device_ip
    IFACE="${CAPTURE_IFACE:-bridge100}"
    ;;
  mirror)
    need_device_ip
    IFACE="${CAPTURE_IFACE:-}"
    if [[ -z "$IFACE" ]]; then
      echo "set CAPTURE_IFACE to the interface on the AP/switch mirror port" >&2
      exit 2
    fi
    ;;
  rooted-wlan0)
    ADB=(adb)
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
      ADB=(adb -s "$ANDROID_SERIAL")
    fi
    # Everything on the phone's wlan0 is the phone's; leave out adb over Wi-Fi.
    FILTER="${CAPTURE_FILTER:-not tcp port 5555}"
    SECONDS_LIMIT="${CAPTURE_SECONDS:-60}"
    umask 077
    mkdir -p "$(dirname "$OUT")"
    stop_phone_capture() {
      "${ADB[@]}" shell "su -c 'pkill -TERM tcpdump'" >/dev/null 2>&1 || true
      if [[ -n "${PHONE_PID:-}" ]]; then
        wait "$PHONE_PID" 2>/dev/null || true
        PHONE_PID=""
      fi
    }
    trap stop_phone_capture EXIT
    trap 'exit 130' INT TERM
    echo "capture: phone wlan0 -> $OUT snaplen=$SNAPLEN seconds=$SECONDS_LIMIT filter: $FILTER" >&2
    "${ADB[@]}" exec-out "su -c 'tcpdump -i wlan0 -n -U -s $SNAPLEN -w - $FILTER'" >"$OUT" &
    PHONE_PID=$!
    sleep "$SECONDS_LIMIT"
    stop_phone_capture
    exit 0
    ;;
  *)
    echo "unknown CAPTURE_TOPOLOGY=$TOPOLOGY (internet-sharing, mirror, rooted-wlan0)" >&2
    exit 2
    ;;
esac

if [[ "$IFACE" == en0 && "$TOPOLOGY" != mirror ]]; then
  echo "warning: en0 sees the phone's frames only when this Mac is its router or on a mirror port" >&2
fi
run_capture "$IFACE" "$OUT" "host $DEVICE_IP"
