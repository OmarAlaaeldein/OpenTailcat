#!/usr/bin/env bash
# Pair the live test gateway in the installed app from the OTC_LIVE_TOKEN
# environment variable (a CI secret or a local export) through
# PairGatewayInstrumentedTest, and grant VPN consent so Connect needs no
# dialog. The token is never printed. Needs the debug and androidTest APKs
# (./gradlew assembleDebug assembleDebugAndroidTest).
set -euo pipefail
source "$(dirname "$0")/lib.sh"
: "${OTC_LIVE_TOKEN:?set OTC_LIVE_TOKEN to the gateway token}"

install_test_apk
run_instrumented com.tailcat.vpn.PairGatewayInstrumentedTest -e token "$OTC_LIVE_TOKEN"
adb shell appops set "$PKG" ACTIVATE_VPN allow
echo "paired; VPN consent granted"
