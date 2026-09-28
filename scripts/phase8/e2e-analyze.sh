#!/usr/bin/env bash
# Synthetic end-to-end check for phase8-analyze (PASS, FAIL, INCONCLUSIVE and
# fail-closed). Host tooling only — NOT Phase 8 physical leak acceptance.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FIXTURE="$ROOT/scripts/phase8/write_fixture_pcaps.py"
ANALYZE="$ROOT/scripts/phase8/analyze-uplink.sh"
PROBES="1.1.1.1"
export DEVICE_IP="10.0.0.2" TUNNEL_PEERS="203.0.113.9"

# expect MODE RC MESSAGE: write the MODE fixtures, run the analyzer, and
# require exit status RC and MESSAGE in its output.
expect() {
  local mode="$1" want="$2" msg="$3" out rc
  python3 "$FIXTURE" --mode "$mode" --device "$DEVICE_IP" --peer "$TUNNEL_PEERS" \
    --uplink "$TMP/$mode-up.pcap" --gateway "$TMP/$mode-gw.pcap" --probe "$PROBES"
  set +e
  out="$("$ANALYZE" "$TMP/$mode-up.pcap" "$PROBES" "$TMP/$mode-gw.pcap" 2>&1)"
  rc=$?
  set -e
  [[ $rc -eq $want ]] || { echo "$mode: expected exit $want, got $rc: $out" >&2; exit 1; }
  grep -q "$msg" <<<"$out" || { echo "$mode: missing \"$msg\": $out" >&2; exit 1; }
}

echo "==> e2e: clean uplink with device tunnel traffic + gateway present => PASS"
expect pass 0 "RESULT: PASS"

echo "==> e2e: leaking uplink => FAIL (exit 1)"
expect leak 1 "FAIL uplink leak"

echo "==> e2e: plaintext DNS to a non-gateway resolver => FAIL (exit 1)"
expect dns-leak 1 "FAIL uplink plaintext DNS"

echo "==> e2e: gateway missing probe dest => FAIL"
expect no-gw-probe 1 "FAIL gateway missing"

echo "==> e2e: gateway only has its own DNS to the probe => FAIL"
expect gw-dns-only 1 "FAIL gateway missing"

echo "==> e2e: uplink without device tunnel traffic => INCONCLUSIVE (exit 3)"
expect no-tunnel 3 "RESULT: INCONCLUSIVE"

echo "==> e2e: captures that do not overlap in time => INCONCLUSIVE (exit 3)"
expect stale-gateway 3 "does not cover"

echo "==> e2e: missing gateway capture => fail-closed exit 2"
set +e
out="$("$ANALYZE" "$TMP/pass-up.pcap" "$PROBES" 2>&1)"
rc=$?
set -e
[[ $rc -eq 2 ]] || { echo "expected exit 2, got $rc: $out" >&2; exit 1; }
grep -q "gateway capture is required" <<<"$out" || { echo "missing gw msg: $out" >&2; exit 1; }

echo "==> e2e: missing positive-control inputs => fail-closed exit 2"
set +e
out="$(DEVICE_IP='' "$ANALYZE" "$TMP/pass-up.pcap" "$PROBES" "$TMP/pass-gw.pcap" 2>&1)"
rc=$?
set -e
[[ $rc -eq 2 ]] || { echo "expected exit 2, got $rc: $out" >&2; exit 1; }
grep -q "DEVICE_IP and TUNNEL_PEERS are required" <<<"$out" || { echo "missing control msg: $out" >&2; exit 1; }

echo "==> phase8-analyze e2e passed (synthetic; not physical acceptance)"
