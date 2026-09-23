#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "==> go test -race / vet"
(
  cd core-engine
  go test -race ./...
  go vet ./...
)

echo "==> phase8-analyze unit self-check"
(
  cd core-engine
  go test -count=1 -run 'TestProbeIPs' .
)

echo "==> phase8-analyze synthetic e2e (not physical acceptance)"
scripts/phase8/e2e-analyze.sh

echo "==> Android unit + lint"
./gradlew testDebugUnitTest lintDebug

echo "==> AAR hash"
got="$(shasum -a 256 app/libs/libtailcat.aar | awk '{print $1}')"
want="$(tr -d ' \n' < app/libs/libtailcat.aar.sha256)"
if [[ "$got" != "$want" ]]; then
  echo "AAR hash $got != $want" >&2
  exit 1
fi

echo "==> AAR sourcehash tracks native tree"
got_src="$(find core-engine third_party \
  -type f \
  ! -path '*/.git/*' \
  ! -name '*.aar' \
  ! -name '*.aar.sha256' \
  ! -path '*/build/*' \
  -print0 |
  sort -z |
  xargs -0 shasum -a 256 |
  shasum -a 256 |
  awk '{print $1}')"
want_src="$(tr -d ' \n' < app/libs/libtailcat.aar.sourcehash)"
if [[ "$got_src" != "$want_src" ]]; then
  echo "sourcehash $got_src != $want_src (rebuild AAR via core-engine/build-aar.sh)" >&2
  exit 1
fi

if command -v tshark >/dev/null 2>&1; then
  echo "==> tshark $(tshark -v | head -n 1)"
else
  echo "tshark not installed; capture-uplink.sh will use tcpdump"
fi

echo "==> host Phase 8 gates passed"
echo "physical dual capture (still required for acceptance):"
echo "  CAPTURE_IFACE=en0 scripts/phase8/capture-uplink.sh captures/uplink.pcap &"
echo "  CAPTURE_IFACE=eth0 scripts/phase8/capture-gateway.sh captures/gateway.pcap &"
echo "  scripts/phase8/generate-probes.sh"
echo "  scripts/phase8/analyze-uplink.sh captures/uplink.pcap 1.1.1.1,8.8.8.8 captures/gateway.pcap"
echo "production signing still requires OPENTAILCAT_RELEASE_*"
