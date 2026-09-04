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

echo "==> phase8-analyze self-check"
(
  cd core-engine
  go test -count=1 -run 'TestProbeIPs' .
)

echo "==> Android unit + lint"
./gradlew testDebugUnitTest lintDebug

echo "==> AAR hash"
got="$(shasum -a 256 app/libs/libtailcat.aar | awk '{print $1}')"
want="$(tr -d ' \n' < app/libs/libtailcat.aar.sha256)"
if [[ "$got" != "$want" ]]; then
  echo "AAR hash $got != $want" >&2
  exit 1
fi

if command -v tshark >/dev/null 2>&1; then
  echo "==> tshark $(tshark -v | head -n 1)"
else
  echo "tshark not installed; capture-uplink.sh will use tcpdump"
fi

echo "==> host Phase 8 gates passed"
echo "physical uplink: scripts/phase8/capture-uplink.sh then analyze-uplink.sh"
echo "production signing still requires OPENTAILCAT_RELEASE_* "
