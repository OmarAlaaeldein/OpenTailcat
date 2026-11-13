#!/usr/bin/env bash
# Pair the live test gateway in the installed app from the OTC_LIVE_TOKEN
# environment variable (a CI secret or a local export) through
# PairGatewayInstrumentedTest, and grant VPN consent so Connect needs no
# dialog. The token is never printed. Needs the debug and androidTest APKs
# (./gradlew assembleGithubDebug assembleGithubDebugAndroidTest).
set -euo pipefail
source "$(dirname "$0")/lib.sh"
: "${OTC_LIVE_TOKEN:?set OTC_LIVE_TOKEN to the gateway token}"

install_test_apk
# Stream into the debug app's private sandbox. The credential must never be a
# process argument (including the remote am instrument command).
cleanup_credential() {
  adb shell run-as "$PKG" rm -f files/live-test-token >/dev/null 2>&1 || true
}
trap cleanup_credential EXIT
printf '%s' "$OTC_LIVE_TOKEN" | adb shell run-as "$PKG" sh -c \
  "'umask 077; mkdir -p files; cat > files/live-test-token'"
run_instrumented com.tailcat.vpn.PairGatewayInstrumentedTest -e tokenFile live-test-token
adb shell appops set "$PKG" ACTIVATE_VPN allow
echo "paired; VPN consent granted"
