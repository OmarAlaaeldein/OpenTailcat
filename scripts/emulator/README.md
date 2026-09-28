# Emulator test scripts

Live checks against a connected OpenTailcat on an emulator or test device.
They need `adb`, `python3` and (for the probe) Go on PATH. None of them reads
or prints a gateway token: add the profile in the app once, and the scripts
reuse it. Build output and logs go to the git-ignored `build/`.

| Script | What it does |
| --- | --- |
| `start.sh` | Boots the `OpenTailcat_API35` AVD headless (override with `AVD=`), waits for boot, installs the debug APK if built. |
| `connect.sh` | Taps Connect and waits for CONNECTED. |
| `ui.sh` | Prints the visible UI nodes with bounds (tokens redacted). |
| `roam-test.sh` | Wi-Fi off/on while connected: expects a Magicsock rebind in the engine log and TCP still through the VPN (P1-9). |
| `gateway-loss-test.sh` | Blocks the app uid with iptables, then unblocks: DEGRADED, reconnect with backoff, recovery (P1-13a/13b). Needs `adb root`. |
| `lockscreen-disconnect-test.sh` | Disconnect from the lock-screen notification must ask for the PIN first (P1-12). |
| `log-privacy-check.sh` | Reconnects with Debug failure reports off and fails if the engine's logcat lines hold an IP address other than the probed Cloudflare services or tunnel-internal ones. |
| `probe.sh ARGS` | Builds `probe/` for the device and runs it from the shell uid: `burst N`, `stun SIZE...`, `halfclose HOST`, `http HOST`. |

| `pair.sh` | Pairs the gateway from `OTC_LIVE_TOKEN` through `PairGatewayInstrumentedTest` (token never printed) and grants VPN consent. |
| `ci-smoke.sh` | CI, no secrets: `VpnStartupInstrumentedTest` plus a home-screen check. |
| `ci-live.sh` | CI, live gateway: pair, connect, then every check above; fails if any check fails. |

## CI

`.github/workflows/emulator.yml` runs `ci-smoke.sh` on an API 35 x86_64
emulator for every push and pull request. The `live` job runs `ci-live.sh` on
pushes to `main`, nightly, and on manual dispatch (never on pull requests),
and only when the repository secret `OTC_LIVE_TOKEN` holds a gateway token.
Set it with `gh secret set OTC_LIVE_TOKEN < <file holding the token>`.
`roam-test.sh` reports SKIP when the emulator has no mobile data network.

The exit IP of the test gateway may equal the host's IP, so these checks rely
on routes (`ip route get ... uid 2000`), engine logs and counters rather than
the exit address. A passing run is emulator evidence, not the Phase 8
physical-device acceptance in `handoff.md`.
