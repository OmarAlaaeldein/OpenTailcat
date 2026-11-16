#!/usr/bin/env bash
# Offline Android-native regression: synthetic keys/loopback only, no token.
# Uses adb only to install/run the project's native test binary, never for UI.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the pinned Android NDK}"
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
case "$abi" in
  arm64-v8a) arch=arm64; triple=aarch64-linux-android ;;
  x86_64) arch=amd64; triple=x86_64-linux-android ;;
  *) echo "Unsupported Android test ABI: $abi" >&2; exit 1 ;;
esac
case "$(uname -s)" in
  Darwin) host=darwin-x86_64 ;;
  Linux) host=linux-x86_64 ;;
  *) echo "Unsupported NDK host" >&2; exit 1 ;;
esac
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin/${triple}26-clang"
mkdir -p "$ROOT/build/native-tests"
binary="$ROOT/build/native-tests/engine-$arch.test"
(
  cd "$ROOT/core-engine"
  env GOOS=android GOARCH="$arch" CGO_ENABLED=1 CC="$compiler" \
    go test -c -buildmode=pie -o "$binary" .
)
adb push "$binary" /data/local/tmp/opentailcat-engine.test >/dev/null
adb shell chmod 700 /data/local/tmp/opentailcat-engine.test
trap 'adb shell rm -f /data/local/tmp/opentailcat-engine.test >/dev/null 2>&1 || true' EXIT
adb shell "/data/local/tmp/opentailcat-engine.test -test.v '-test.run=^Test(AndroidTransportCreationAndFailure|NativeMetadataHTTP.*|ProtectFD.*)$'"
