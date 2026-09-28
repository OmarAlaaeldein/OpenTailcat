# shellcheck shell=bash
# Shared by the Phase 8 capture scripts (sourced, not run). Classic pcap with
# headers only (snaplen), a BPF filter so unrelated traffic is not recorded,
# a portable time bound (no GNU timeout), and a trap that stops tcpdump when
# the script exits.

SNAPLEN="${CAPTURE_SNAPLEN:-128}"
SUDO=""
CAPTURE_PID=""

stop_capture() {
  if [[ -n "$CAPTURE_PID" ]]; then
    # A background job ignores SIGINT; tcpdump exits cleanly on SIGTERM,
    # which sudo relays.
    $SUDO kill -TERM "$CAPTURE_PID" 2>/dev/null || true
    wait "$CAPTURE_PID" 2>/dev/null || true
    CAPTURE_PID=""
  fi
}

# run_capture IFACE OUT FILTER
# CAPTURE_SECONDS or CAPTURE_PACKETS bounds the capture; neither runs until
# Ctrl-C.
run_capture() {
  local iface="$1" out="$2" filter="$3"
  local seconds="${CAPTURE_SECONDS:-}" packets="${CAPTURE_PACKETS:-}"
  if [[ -n "$seconds" && -n "$packets" ]]; then
    echo "set only CAPTURE_SECONDS or CAPTURE_PACKETS, not both" >&2
    exit 2
  fi
  umask 077
  mkdir -p "$(dirname "$out")"
  echo "capture: iface=$iface out=$out snaplen=$SNAPLEN seconds=${seconds:-none} packets=${packets:-none}" >&2
  echo "filter:  $filter" >&2

  if command -v tshark >/dev/null 2>&1; then
    local targs=(-i "$iface" -F pcap -s "$SNAPLEN" -f "$filter" -w "$out")
    if [[ -n "$seconds" ]]; then
      targs+=(-a "duration:$seconds")
    fi
    if [[ -n "$packets" ]]; then
      targs+=(-c "$packets")
    fi
    exec tshark "${targs[@]}"
  fi
  if ! command -v tcpdump >/dev/null 2>&1; then
    echo "need tshark or tcpdump" >&2
    exit 1
  fi
  if [[ $EUID -ne 0 ]]; then
    SUDO=sudo
    sudo -v # ask for the password before the capture window starts
  fi
  local args=(-i "$iface" -n -U -s "$SNAPLEN" -w "$out")
  if [[ -n "$packets" ]]; then
    args+=(-c "$packets")
  fi
  trap stop_capture EXIT
  trap 'exit 130' INT TERM
  $SUDO tcpdump "${args[@]}" "$filter" &
  CAPTURE_PID=$!
  if [[ -n "$seconds" ]]; then
    sleep "$seconds"
    stop_capture
  else
    wait "$CAPTURE_PID" || true
    CAPTURE_PID=""
  fi
}
