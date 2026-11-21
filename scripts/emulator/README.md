# Emulator test scripts

Contributor scripts for testing OpenTailcat on an Android emulator or connected test device. Requires `adb`, `python3`, and Go (for `probe/`). None of the scripts print tokens; `pair.sh` reads from `OTC_LIVE_TOKEN`, or you can pair a profile once manually in the app and let the scripts reuse it. Logs and build output go into `build/`.

| Script | Purpose |
| --- | --- |
| `start.sh` | Boots the `OpenTailcat_API35` AVD headless (override with `AVD=`), waits for boot, and installs the debug APK if built. |
| `connect.sh` | Taps Connect and waits until the app reaches `CONNECTED`. |
| `ui.sh` | Dumps visible UI nodes and bounds (token strings redacted). |
| `roam-test.sh` | Toggles Wi-Fi off and on while connected; checks for a Magicsock rebind in engine logs and verifies TCP still routes through the VPN. |
| `background-test.sh` | Backgrounds the app and then turns the screen off, probing real TCP forwarding over the TUN every 15 s and checking that the screen stays asleep. |
| `gateway-loss-test.sh` | Blocks the app UID with `iptables`, then unblocks it; checks `DEGRADED`, reconnect backoff, and recovery. Requires `adb root`. |
| `lockscreen-disconnect-test.sh` | Verifies that tapping Disconnect from the lock-screen notification prompts to unlock first. |
| `log-privacy-check.sh` | Reconnects with Debug failure reports off and fails if engine logcat lines leak unexpected IP addresses. |
| `probe.sh ARGS` | Cross-compiles `probe/` for the device and runs it from the shell UID (`burst N`, `stun SIZE...`, `halfclose HOST`, `http HOST`). |
| `pair.sh` | Pairs a gateway from `OTC_LIVE_TOKEN` via `PairGatewayInstrumentedTest` and grants VPN consent. |
| `ci-smoke.sh` | No-secret CI suite: VPN startup, `stopSelf(startId)` ordering, QR image import, Keystore preference migration, and home-screen render check. |
| `native-protect-test.sh` | Offline Android Go tests for creation-time socket protection, ordinary-socket isolation, and protection-failure rejection (synthetic keys and loopback only; needs pinned `ANDROID_NDK_HOME`). |
| `ci-live.sh` | Full live-gateway CI suite: provisions emulator mobile data, pairs (if `OTC_LIVE_TOKEN` is set), connects, and runs all live checks above. |

## CI

`.github/workflows/emulator.yml` runs `ci-smoke.sh` and `native-protect-test.sh` on every push and PR. The `live` job runs `ci-live.sh` on `main`, nightly, and manual dispatch when the `OTC_LIVE_TOKEN` repository secret is set (`gh secret set OTC_LIVE_TOKEN < <token-file>`).

`roam-test.sh` exits 77 if mobile data is unavailable; `ci-live.sh` provisions the emulator's cellular network first and fails if it cannot come up so roaming is never silently skipped. Because the emulator's exit IP may match the CI runner's IP, checks verify routes, logs, and counters. These runs validate emulator behavior only and do not replace physical-device leak testing (see [AGENTS.md](../../AGENTS.md)).
