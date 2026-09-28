#!/usr/bin/env bash
# Gateway-loss check (review P1-13a/13b), with OpenTailcat connected. Drops
# all traffic from the app uid with iptables for BLOCK_SECS (default 150),
# then restores it and watches for RECOVER_SECS (default 110). Expected:
# DEGRADED within ~10 s, a RECONNECTING teardown after 60 s without a gateway
# reply, backoff retries, and CONNECTED again after the block is lifted.
# Needs a rootable emulator (adb root); the rules are removed on exit.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
BLOCK_SECS=${BLOCK_SECS:-150}
RECOVER_SECS=${RECOVER_SECS:-110}

if [ "$(adb shell id -u | tr -d '\r')" != 0 ]; then
  adb root >/dev/null
  adb wait-for-device
fi
APP_UID=$(app_uid)
[ -n "$APP_UID" ] || { echo "cannot find uid of $PKG" >&2; exit 2; }

unblock() {
  adb shell iptables -D OUTPUT -m owner --uid-owner "$APP_UID" -j DROP 2>/dev/null || true
  adb shell ip6tables -D OUTPUT -m owner --uid-owner "$APP_UID" -j DROP 2>/dev/null || true
}
trap 'unblock; adb unroot >/dev/null 2>&1 || true' EXIT

T0=$(date +%s)
SEEN=""
log() { echo "t+$(( $(date +%s) - T0 ))s $*"; }
watch_until() {
  local state
  while [ $(( $(date +%s) - T0 )) -lt "$1" ]; do
    state=$(tunnel_state)
    SEEN="$SEEN|$state"
    log "$state"
    sleep 5
  done
}

log "before: $(tunnel_state)"
adb shell iptables -I OUTPUT -m owner --uid-owner "$APP_UID" -j DROP
adb shell ip6tables -I OUTPUT -m owner --uid-owner "$APP_UID" -j DROP
log "blocked app uid $APP_UID"
watch_until "$BLOCK_SECS"
blocked_seen=$SEEN
unblock
log "unblocked"
SEEN=""
# Stop watching once the tunnel is back, but give it RECOVER_SECS at most.
deadline=$(( BLOCK_SECS + RECOVER_SECS ))
final=""
while [ $(( $(date +%s) - T0 )) -lt "$deadline" ]; do
  final=$(tunnel_state)
  log "$final"
  [ "$final" = CONNECTED ] && break
  sleep 5
done

# Expected: DEGRADED while the gateway is silent, a RECONNECTING teardown
# after 60 s without a reply, and CONNECTED again after the block is lifted.
if [[ "$blocked_seen" == *DEGRADED* ]] && [[ "$blocked_seen" == *RECONNECTING* ]] && [ "$final" = CONNECTED ]; then
  echo "PASS"
else
  echo "FAIL: saw [${blocked_seen#|}] while blocked, ended in ${final:-?}"
  exit 1
fi
