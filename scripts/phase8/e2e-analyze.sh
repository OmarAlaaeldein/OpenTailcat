#!/usr/bin/env bash
# Synthetic end-to-end check for phase8-analyze (PASS + FAIL + fail-closed).
# Host tooling only — NOT Phase 8 physical leak acceptance.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FIXTURE="$ROOT/scripts/phase8/write_fixture_pcaps.py"
ANALYZE="$ROOT/scripts/phase8/analyze-uplink.sh"
PROBES="1.1.1.1"

echo "==> e2e: clean uplink + gateway present => PASS"
python3 "$FIXTURE" --mode pass \
  --uplink "$TMP/clean.pcap" --gateway "$TMP/gw-pass.pcap" --probe "$PROBES"
"$ANALYZE" "$TMP/clean.pcap" "$PROBES" "$TMP/gw-pass.pcap"

echo "==> e2e: leaking uplink => FAIL (exit 1)"
python3 "$FIXTURE" --mode leak \
  --uplink "$TMP/leak.pcap" --gateway "$TMP/gw-leak.pcap" --probe "$PROBES"
set +e
out="$("$ANALYZE" "$TMP/leak.pcap" "$PROBES" "$TMP/gw-leak.pcap" 2>&1)"
rc=$?
set -e
[[ $rc -eq 1 ]] || { echo "expected exit 1, got $rc: $out" >&2; exit 1; }
grep -q "FAIL uplink leak" <<<"$out" || { echo "missing leak msg: $out" >&2; exit 1; }

echo "==> e2e: missing gateway capture => fail-closed exit 2"
set +e
out="$("$ANALYZE" "$TMP/clean.pcap" "$PROBES" 2>&1)"
rc=$?
set -e
[[ $rc -eq 2 ]] || { echo "expected exit 2, got $rc: $out" >&2; exit 1; }
grep -q "gateway capture is required" <<<"$out" || { echo "missing gw msg: $out" >&2; exit 1; }

echo "==> e2e: gateway missing probe dest => FAIL"
python3 "$FIXTURE" --mode no-gw-probe \
  --uplink "$TMP/clean3.pcap" --gateway "$TMP/gw-miss.pcap" --probe "$PROBES"
set +e
out="$("$ANALYZE" "$TMP/clean3.pcap" "$PROBES" "$TMP/gw-miss.pcap" 2>&1)"
rc=$?
set -e
[[ $rc -eq 1 ]] || { echo "expected exit 1, got $rc: $out" >&2; exit 1; }
grep -q "FAIL gateway missing" <<<"$out" || { echo "missing gw fail: $out" >&2; exit 1; }

echo "==> phase8-analyze e2e passed (synthetic; not physical acceptance)"
