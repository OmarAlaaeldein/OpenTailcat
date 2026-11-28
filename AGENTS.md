# OpenTailcat engineering guide

OpenTailcat is an Android app for self-hosted Tailcat exit gateways. It runs in one of two mutually exclusive roles:

- **Client VPN**: takes a `tc...` token, captures device traffic on an Android TUN interface, terminates packets in gVisor, and proxies IPv4/IPv6 TCP, UDP, and DNS over WireGuard/Magicsock/DERP to the gateway.
- **Exit node ("Share this phone")**: runs `tailcat.Server` in an ordinary `specialUse` foreground service (no `VpnService` or TUN), mints a one-session `tc...` code, and forwards guests' TCP/UDP out over the phone's own network.

Project metadata:

- Android: `com.tailcat.vpn`, min API 26, target API 35, compile API 37
- Toolchain: Kotlin 2.4.21, Gradle 9.9.1, JDK 21, Go 1.27.4, NDK `29.0.14206865`
- Dependency versions: `gradle/libs.versions.toml`, `core-engine/go.mod`
- Current version: 1.8.0 (`docs/releases/`)

## Status

The client tunnel is a dual-stack test-routing development preview (`testRouting: true` in the capability payload). It connects with a live token, but physical leak acceptance is still pending. Do not treat it as a hardened privacy VPN.

Exit-node mode ("Share this phone") shipped in 1.7.0 and is separate from the client data plane; see [Exit node](#exit-node) and [Remaining work](#remaining-work).

## Architecture

Client mode:

```text
 Compose UI (MainActivity, screens, ViewModels)
        │  StateFlow + view calls
 TunnelController ───────────────┐
        │ NativeEngine            │ VpnService.Builder + TUN fd
        ▼                         ▼
 libtailcat.aar (gomobile)     TailcatVpnService (Android VpnService)
        │
 Go engine: lifecycle, gVisor netstack, DNS policy, telemetry
        │ Tailcat Client: WireGuard over Magicsock/DERP
        ▼
 self-hosted Tailcat exit gateway ──► Internet
```

Packet flow: app -> TUN fd -> Go `TunBridge` reader -> gVisor TCP/IP stack -> `Client.DialTCP`/`DialUDP` -> WireGuard/Magicsock -> gateway -> Internet. Application flows never use an OS socket, and OpenTailcat does not exclude its own UID from the VPN.

Exit-node mode (only while the client tunnel is stopped):

```text
 ShareScreen / ShareViewModel
        │
 ExitNodeController ─── ExitNodeService (foreground service, specialUse)
        │ NativeExitNode (TunnelExitNode → Engine.*ExitNode*)
        ▼
 Go exitnode package: tailcat.Server, admission, destination policy, forwarders
        ▲                                   │ OS sockets (dial.go only)
 guest ─┘ WireGuard/Magicsock/DERP          ▼ device routes
                                         Internet
```

Kotlin owns the UI, encrypted profile storage, `VpnService` and `ExitNodeService` lifecycles, notifications, split-tunnel exclusions, the GitHub self-updater, and speed-test UI/device-route measurement. Go owns the packet engine, token authority, gVisor stack, DNS/IPv6/MTU policy, telemetry, in-tunnel speed test, and exit-node forwarding. gomobile binds the Go package as `com.tailcat.golib.engine.Engine`, checked in at `app/libs/libtailcat.aar`.

## Repository map

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/tailcat/vpn/ui/` | Compose UI (`MainActivity`, `DisconnectActivity`, `screens/{home,settings,share,speedtest}/`, `theme/`) |
| `app/src/main/java/com/tailcat/vpn/service/` | `TailcatVpnService`, `TunnelController`, `TunnelEngine`/`NativeEngine`, health/split-tunnel/retry/lockdown helpers; exit node: `ExitNodeService`, `ExitNodeController`, `TunnelExitNode`/`NativeExitNode`, `ExitNodeNotifications` |
| `app/src/main/java/com/tailcat/vpn/core/` | `token/TokenParser.kt`, `dns/DnsValidator.kt`, `ip/IpAuditor.kt`, `geo/CountryRanges.kt`, `speedtest/`, `update/`, `tls/` (SPKI pins), `net/AppHttp.kt`, `qr/`, `metrics/`, `exitnode/ExitNodeStats.kt`, `NetworkMonitor.kt` |
| `app/src/main/java/com/tailcat/vpn/data/` | `SealedPreferences.kt` (AES-256-GCM via Android Keystore), `PreferencesStore.kt`, `ProfileRepository.kt` |
| `core-engine/` | Go client engine: `main.go` (init, network state, capabilities), `lifecycle.go` (state machine and lifecycle exports), `bridge.go` (`TunBridge` pumps, ICMP errors, RTT/stats), `netstack_proxy.go` (gVisor TCP/UDP proxy), `tunnel_dns.go`, `token.go`, `cbor_strict.go`, `derp_filter.go`, `egress.go`, `ipv6_egress.go`, `udp_probe.go`, `speedtest.go`, `protect*.go`, `exitnode_bridge.go` |
| `core-engine/exitnode/` | Exit-node package (`node.go`, `policy.go`, `dial.go`, `stats.go`) |
| `core-engine/testdata/` | Shared fixtures (`token_fixtures.json`, `token_differential.json`, `capabilities.json`, `exitnode_stats.json`, fuzz seeds) read by both Go and Kotlin tests |
| `core-engine/build-aar.sh` | Reproducible AAR builder; updates `app/libs/libtailcat.aar`, `.sha256`, and `.sourcehash` |
| `third_party/tailcat` | Unmodified pinned upstream Tailcat submodule |
| `core-engine/integration/`, `core-engine/tailcat-integration/` | Android integration patch (`tailcat.patch`), sync script (`sync_tailcat.py`), and generated local replacement module |
| `app/src/test/`, `app/src/androidTest/` | JVM unit tests and Android instrumented tests |
| `scripts/emulator/`, `scripts/phase8/`, `scripts/geoip/` | adb emulator test scripts, host dual-capture leak analyzer, and the country-range asset generator |
| `formal/` | TLA+ lifecycle/shutdown/network-callback models, counterexample replays, and `check.sh` runner |
| `.github/workflows/` | `ci.yml`, `emulator.yml`, `formal.yml` |
| `docs/` | Docs index, release notes, signing notes, screenshots |

### Where to look first

| Area | Files |
| --- | --- |
| Pairing, QR, profiles | `ui/screens/home/ProfileSelector.kt`, `AddProfileDialog.kt`, `core/qr/`, `data/ProfileRepository.kt` |
| Connect, disconnect, reconnect | `service/TunnelController.kt`, `service/TailcatVpnService.kt`, `core-engine/lifecycle.go` |
| TUN config, DNS, routes | `service/VpnInterfaceSpec.kt`, `AttachTun` in `core-engine/lifecycle.go` |
| Packets, UDP table, MTU | `core-engine/bridge.go`, `core-engine/netstack_proxy.go`, `core-engine/tunnel_dns.go` |
| Country filter | `core-engine/country_block.go`, `service/CountryBlockInstaller.kt`, `core/geo/CountryRanges.kt`, `ui/screens/settings/CountryBlockTab.kt`, `scripts/geoip/generate_country_ranges.py` |
| Token parsing | `core-engine/token.go`, `core-engine/cbor_strict.go`, `core/token/TokenParser.kt`, `testdata/token_fixtures.json` |
| Capabilities | `routeReady()` in `core-engine/main.go`, `testdata/capabilities.json`, `service/TunnelEngine.kt` |
| Telemetry, speed test | `core-engine/speedtest.go`, `core/speedtest/`, `ui/screens/home/components/TelemetryCard.kt` |
| In-app updater | `core/update/`, `UpdatePolicy.kt`, `docs/signing.md` |
| Settings | `ui/screens/settings/`, `data/PreferencesStore.kt` |
| Exit node (sharing) | `core-engine/exitnode/`, `core-engine/exitnode_bridge.go`, `service/ExitNodeController.kt`, `service/ExitNodeService.kt`, `ui/screens/share/` |

## Safety invariants

1. Never install `0.0.0.0/0` or `::/0` for an engine that has not proven live pumps.
2. Never use ordinary OS sockets for traffic claimed to be tunneled (`core-engine/no_os_socket_test.go` enforces this for `package engine`).
3. Never report `CONNECTED` until authenticated transport and all required pumps are live and native health is fresh.
4. Never synthesize telemetry, reachability, handshake, speed, or egress values.
5. A local ICMP reply or public Internet probe is not a gateway test.
6. Close the TUN immediately after startup, pump, or health failure.
7. Reject invalid or expired tokens in both Android and native code.
8. Development preview releases use the existing development key for upgrade compatibility; production signing and migration are separate future work.
9. The app does not exclude its own UID. Only native transport sockets are protected with `VpnService.protect`, at creation before bind/connect. The local Tailcat integration keeps Android `netns` hooks enabled during engine initialization and region selection, and native metadata HTTP and transport DNS also protect sockets at creation. Missing or failed Android protection fails the socket operation. Never enumerate process FDs or protect Kotlin app HTTP sockets. App HTTP goes through `core/net/AppHttp` with connection reuse disabled.
10. Keep secrets, live tokens, signing keys, and traffic captures out of the repo and public logs.
11. The country filter fails closed. When the user has selected any country, if the bundled data cannot be loaded or parsed, or the engine rejects the list (including a DNS resolver inside a blocked country), abandon the start; never connect without the filter the user selected.

## Build and verify

Prerequisites: JDK 21 and Android SDK (platform 37, matching build-tools, NDK `29.0.14206865`). Go 1.27.4 is only needed for `core-engine/`. On a fresh clone run `git submodule update --init` and point Gradle at the SDK via `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. Kotlin-only changes build against the checked-in AAR.

Quick loop (one flavor, no device):

```bash
cd core-engine && go test ./...
cd .. && ./gradlew testGithubDebugUnitTest lintGithubDebug
./gradlew assembleGithubDebug
```

Full gate (matches CI + release builds):

```bash
cd core-engine && go test -race ./... && go vet ./... \
  && go run honnef.co/go/tools/cmd/staticcheck@v0.8.2 ./... \
  && go run golang.org/x/vuln/cmd/govulncheck@v1.8.0 ./... && gofmt -l .
cd .. && ./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest \
  lintGithubDebug lintFdroidDebug
./gradlew assembleGithubDebug assembleGithubDevelopment
./gradlew assembleFdroidRelease -PnoAbiSplits && ./gradlew bundleGithubRelease
```

`scripts/phase8/run-host-gates.sh` runs Go race/vet, the Phase 8 analyzer self-check, GitHub unit tests and lint, and AAR hash/sourcehash verification in one script.

Build gotchas:

- Always include the flavor name (`github` or `fdroid`). Bare tasks like `testDebugUnitTest`, `assembleRelease`, or `lint` are ambiguous.
- Pass `-PnoAbiSplits` to build a single universal APK for F-Droid. ABI splits cannot be combined with `bundleRelease` (AGP issue 402800800), so `app/build.gradle.kts` disables splits automatically when a task name contains `bundle`.
- `app/libs/libtailcat.aar` is checked in. After any change under `core-engine/` or `third_party/`, rebuild it and commit `app/libs/libtailcat.aar`, `app/libs/libtailcat.aar.sha256`, and `app/libs/libtailcat.aar.sourcehash`. CI fails if either hash does not match.
- Do not add a Gradle toolchain auto-provisioning plugin such as `org.gradle.toolchains.foojay-resolver-convention`. F-Droid's scanner rejects plugins that download JDK binaries from third parties. `gradle/gradle-daemon-jvm.properties` pins the daemon JVM instead.

Rebuilding the AAR:

```bash
env GOTOOLCHAIN=go1.27.4 core-engine/build-aar.sh && unzip -l app/libs/libtailcat.aar
```

`build-aar.sh` enforces Go 1.27.4, NDK `29.0.14206865`, 16 KB ELF LOAD alignment, `-javapkg=com.tailcat.golib`, `-androidapi=26`, `arm64` + `amd64` targets, expected Java API methods, and consumer ProGuard rules. Cross-host CI compares module lists, Java API, and source hash.

## Release process

Development previews are signed with the build machine's `~/.android/debug.keystore`, whose SHA-256 certificate pin (`dd767011...afa8d6`) is checked by `UpdatePolicy.kt` and documented in `docs/signing.md`. `assembleGithubDevelopment` uses that keystore automatically, so publishable builds must come from a machine with that key or Android will reject upgrades.

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts`. Identify releases by `versionName` only; keep `versionCode` out of release titles, notes, and docs (except F-Droid's changelog filename).
2. Add `docs/releases/<version>.md` (changes, verification, artifact hashes) and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Update the latest-release link in `docs/README.md`, and verify that `PRIVACY_POLICY.md` and `fastlane/metadata/android/en-US/full_description.txt` still match the app.
3. Build publishable APKs with the real repository slug (the tracked default `opentailcat/OpenTailcat` is a placeholder):

   ```bash
   ./gradlew assembleGithubDevelopment -PopentailcatRepoSlug=<owner>/<repo>
   ```

4. Check the built APKs before uploading:

   ```bash
   BT=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
   "$ANDROID_HOME/build-tools/$BT/aapt2" dump badging <apk> | head -1   # versionName
   "$ANDROID_HOME/build-tools/$BT/apksigner" verify --print-certs <apk>  # dd7670... pin
   unzip -p <apk> 'classes*.dex' | strings | rg -o '[A-Za-z0-9_.-]+/OpenTailcat'
   ```

   Confirm the last line prints the real slug, not `opentailcat/OpenTailcat`.

5. Rename APKs to `OpenTailcat-<version>-<abi>.apk`, generate `OpenTailcat-<version>-SHA256SUMS.txt`, and record the hashes in `docs/releases/<version>.md`.
6. Commit, create an annotated tag `v<version>`, push `main` and the tag, and publish:

   ```bash
   gh release create v<version> --title "OpenTailcat <version>" \
     --notes-file docs/releases/<version>.md <apks> <sums>
   ```

7. Stop all JVM daemons and shut down any emulators/simulators used during the release, and confirm they have exited.

The `github` flavor enables the self-updater (`UPDATER_ENABLED = true`) and adds `REQUEST_INSTALL_PACKAGES`; `fdroid` compiles it out. Never put personal account names or tokens in tracked files.

## Conventions and pitfalls

### UI and theme

- Dark Material3 Compose UI using tokens in `ui/theme/` (`AccentCyan`, `BgDark`, `SurfaceDark`, `SurfaceElevated`, etc.), with one package per screen under `ui/screens/`.
- Never use black or near-black text. Every `on*` role in `Theme.kt` uses `TextPrimary` or `TextSecondary`, and `BgDark` is background-only (including in `themes.xml`). Filled buttons use `TailcatButtons.filled()` (`TextPrimary` on `AccentFill`), checkboxes use `TailcatButtons.checkbox()`. `AccentCyan` is for text, outlines, radios, and focus indicators, not as a fill behind light text. Map Material3 container roles explicitly in `Theme.kt` before using them. `ThemeContrastTest` enforces WCAG AA contrast and light content roles. QR code rendering uses black modules on white so scanners can read it.
- Dialogs showing or accepting credentials must set `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` and token inputs must use `KeyboardType.Password`.
- No camera permission. QR generation and image import use ZXing `core` in memory via the system image picker URI grant, capped at 16 MiB encoded size and 2048 px per side.

### Tokens, storage, and native boundaries

- Treat every `tc...` string as a secret: keep it out of logs, notifications, automatic clipboard writes, crash reports, and tests.
- Profiles live in AES-256-GCM Keystore-backed preferences (`SealedPreferences.kt`). Never add a plaintext fallback; if sealing fails, skip the write.
- Every gomobile-exported Go function must recover panics as errors (`recoverExported`).
- Never fake counters or freshness. WireGuard peer counters (`txBytes`/`rxBytes`) stay 0 until upstream exposes a status API.
- Token or capability schema changes must update `core-engine/testdata/*.json`, both Go and Kotlin parsers, and both test suites.
- Do not edit `third_party/tailcat` directly; update the submodule pin and regenerate `tailcat-integration` with `core-engine/integration/sync_tailcat.py`.
- Dependency updates are manual (Dependabot is off). Any Go/native bump requires rebuilding the AAR.
- Country data is a committed asset (`app/src/main/assets/country_ranges.txt`), never fetched at runtime. Regenerate it with `python3 scripts/geoip/generate_country_ranges.py` (or `--from-dir` to reuse downloads), review the new serials and diff, and run both `core-engine` and Kotlin asset tests. Registry data records where a range was first allocated, not where it is used, so the UI and docs call the filter best effort. Do not add a country code or prefix by hand.
- Screenshots in `docs/screenshots/` must not contain live tokens or codes. Because the share screen sets `FLAG_SECURE`, `adb screencap` returns a blank frame; use `adb emu screenrecord screenshot <dir>`, blur the QR completely, remove any emulator cutout, and stop the share afterward to discard its keys.

## Data plane

| Traffic | Handling | Egress |
| --- | --- | --- |
| IPv4 TCP | Terminated by gVisor, proxied via `Client.DialTCP`, half-close forwarded, max 512 active connections | Gateway |
| UDP/53 & TCP/53 | Forwarded to TUN destination (`PROFILE_RESOLVER`) or `ForcedDNS` (`FORCED_RESOLVER`). When `tcpOnly` is set, UDP/53 switches to pipelined DNS-over-TCP with EDNS-aware TC truncation (512 B classic fallback) | Gateway |
| Other IPv4 UDP | `Client.DialUDP`, device-wide 1024-flow LRU table; idle timeouts: 2 s answered DNS / 10 s unanswered DNS / 30 s unreplied UDP / 2 min replied UDP | Gateway |
| UDP payload > 1232 B | Dropped (`mtuExceeded`); unfragmented packets get local ICMP Frag-Needed (1260) or Packet Too Big (1280), rate-limited to 100/s | None |
| IPv6 TCP/UDP | Same 15 s TCP / 10 s UDP dial timeouts; public IPv6 rejected pre-dial when `ipv6Egress` is false (NAT64 `64:ff9b::/96` exempt) | Gateway if IPv6 WAN works, else RST/drop |
| Destination in a user-blocked country | Refused in `resolveFlowDestination` after the DNS redirect and IPv6 gate (TCP RST, UDP drop), counted in `policyRejections`; well-known NAT64 is judged by its embedded IPv4. The engine's own speed-test and exit-IP flows bypass the gate by design | None |
| ICMPv4 / ICMPv6 echo | Dropped (`policyRejections`) | None |
| Over-MTU packets | ICMPv6 Packet Too Big; IPv4 Frag-Needed if DF is set, otherwise dropped | None |
| Speed test | `Client.DialTCP` when `CONNECTED`; normal app HTTP sockets otherwise (follow device routes, which enter the TUN if up) | Gateway when `CONNECTED` |

Other details:

- Local gVisor TCP buffers: 128 KiB default, 1 MiB max. `GATEWAY_RESOLVER` migrates to `PROFILE_RESOLVER`.
- In-tunnel exit-IP audit runs in Go via `Client.DialTCP` (pinned TLS to `1.1.1.1/cdn-cgi/trace`). `IpAuditor` on the home card uses normal unprotected app HTTP sockets (pinned TLS to `1.1.1.1/cdn-cgi/trace`), so it queries the physical network while disconnected and is hidden while the tunnel is live.
- `UpdateNetworkState` feeds Android `LinkProperties` into `Client.NetworkMonitor()` to trigger Magicsock rebinds. Missing monitor support fails `Prepare`.
- Telemetry is schema v2: RTT comes from `DiscoPing` (~5 s interval), jitter is null until 3 samples exist, and DERP region comes from `Client.DERPRegion()` (falling back to the token's embedded map until sampled).
- `tunTxBytes` and `tunRxBytes` count all IP bytes read from / written to the TUN, headers included (matching `/proc/net/dev`), including refused packets and local TCP ACKs / ICMP errors. Rates (`txRateKbps`/`rxRateKbps`) use decimal kilobits/sec, and UI totals/rates use decimal units (`kB`/`MB`/`GB`, `kb/s`/`Mb/s`/`Gb/s`) so rates and totals match (`counters_exact_test.go`).

## Lifecycle

Native engine states: `STOPPED` -> `PREPARING` -> `PREPARED` -> `ATTACHING` -> `RUNNING`, plus `STOPPING` and `FAILED`. UI states: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`, `DEGRADED`.

- `Prepare` validates the token, runs `Ping`/`DiscoPing`, probes gateway UDP via STUN (latching `tcpOnly` on failure, with 30 s re-probe and auto-relatch after 8 unanswered flows), and probes IPv6 WAN egress. Session context lets `Stop` cancel an in-flight `Prepare`. Keep `globalCore.mu` short-lived (no network I/O or pump waits under it).
- Two-phase TUN attach: Android establishes a host-only TUN (no VPN DNS), calls `AttachTun` to prove live pumps, calls `DisarmPumps` + `DetachTun`, then establishes the routed TUN (`0.0.0.0/0` + `::/0` + DNS) and attaches again. `AttachTun` returns only after the TUN reader, gVisor writer, UDP GC, and health/rate loops are running.
- Unexpected pump exit moves native state to `FAILED` and zeros `healthUnixSec`. Kotlin requires native `RUNNING` plus fresh health before showing `CONNECTED`.
- Health-triggered reconnects log only a fixed reason category and heartbeat/silence age (`PUMP_FAILED`, `HEALTH_STALE`, `GATEWAY_LOST`, `STATS_UNAVAILABLE`), never raw stats, addresses, or tokens.
- `Stop` is idempotent and bounded (`stopWaitTimeout` = 3 s). Android closes the TUN before calling native `stop()`.
- On mid-session failure while `vpnWanted` is true, `TailcatVpnService` drops the TUN, stops the engine, and reconnects with `StartRetry` backoff (2 s to 30 s). There is no TUN between attempts; Always-on VPN with lockdown closes that gap.

Concurrency rules (checked by the TLA+ models in `formal/`; preserve these when touching `TailcatVpnService`, `TunnelController`, or `lifecycle.go`):

- Start jobs are serialized across service instances (`launchSerializedStart`) and scoped to `shutdownEpoch`. A superseded start never touches the controller, `vpnWanted`, or a newer session; it only stops any native session it may have created.
- `shutdown()` sets `shuttingDown` and increments `shutdownEpoch` under `interfaceLock`. `establishAndAdopt` and publishing `CONNECTED` run under `interfaceLock` after checking the epoch, so neither a TUN nor `CONNECTED` can outlive a shutdown.
- Failed starts only tear down through `failStart` / `abandonStaleStart`. The staleness check and `onVpnStartFailed` share one `interfaceLock` block, and `shutdown(epoch)` checks the epoch in the CAS step. `onVpnStartFailed(serviceStopping = true)` leaves transitioning to `DISCONNECTED` to `onVpnStopped` so Connect cannot race an active shutdown.
- `ACTION_STOP_VPN` only stops starts accepted by that instance before it (`startGeneration`, verified in `shutdown`'s CAS), is ignored if `vpnWanted` is true again, and calls `stopSelf(startId)` only when idle. `finish()` skips if a newer `START` arrived after the CAS and calls `stopSelf(lastStartId)`, never plain `stopSelf()`.
- `markFailed` acts only for the session's current `TunBridge` so a warm-bridge pump callback cannot fail the routed attach. `DetachTun` returns an error if no session exists.
- `TunnelController` publishes poll and network-restore results only when `sessionGeneration` still matches under `stateLock`. The `NetworkType.NONE` transition uses `StateFlow.compareAndSet(CONNECTED, RECONNECTING)`.

## Token contract

Format: `"tc" + Base64URL(CBOR({p, k, q?, i?, r?, exp?, iat?}))` with 32-byte `p` (node key) and `k` (disco key), optional 32-byte `q` (PSK), DERP region `i` or structured `r`, and optional integer `exp`/`iat`.

Both Go (`core-engine/token.go`, `cbor_strict.go`) and Kotlin (`core/token/TokenParser.kt`) reject whitespace, `=` padding, duplicate CBOR keys, aliases, unknown fields, trailing bytes, oversized or indefinite-length CBOR, floats, invalid UTF-8 text strings, overflowing timestamps, `exp < iat`, expired tokens, and `k == p`. Legacy numeric `r` returns `LEGACY_REISSUE_REQUIRED` and is never dialed. Embedded DERP regions allow region fields `i`/`c`/`m`/`N` and node fields `n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`, rejecting `x` (`InsecureForTests`), loopback/metadata hostnames, hostnames with embedded IPv4 literals, and loopback/unspecified/link-local/multicast IPs. Fields are validated in encoded order on both sides.

Shared corpora in `core-engine/testdata/`:

- `token_fixtures.json` (90 cases, generated by `go run ./cmd/generate-fixtures`)
- `token_differential.json` (2,654 deterministic mutations; `TokenDifferentialTest` checks Go/Kotlin agreement on classification, error code, keys, and region)

After an intentional parser change, regenerate with:

```bash
cd core-engine && OTC_UPDATE_FIXTURES=1 go test -run TestTokenDifferentialCorpus .
```

Then run both Go and Kotlin test suites.

## Capabilities

API v2, pinned by `core-engine/testdata/capabilities.json` (parsed by both Go and Kotlin tests). `dataPlane` is computed by `routeReady()` as the AND of `ipv4`, `ipv6`, `tcp`, `udp`, `dns`, `wireGuard`, `magicsock`, `twoPhaseStart`, `liveStats`, and `cancelSafeLifecycle`. `testRouting` is display-only. Kotlin (`EngineCapabilities.fromJson`) fails closed on unknown fields, `apiVersion < 2`, or any false required flag.

## Native API

gomobile exports on `com.tailcat.golib.engine.Engine` (spread across `main.go`, `lifecycle.go`, `token.go`, `speedtest.go`, `protect.go`, and `exitnode_bridge.go`):

- Client tunnel: `getCapabilitiesJSON()`, `prepare(token)`, `attachTun(tunFd)`, `detachTun()`, `disarmPumps()`, `getStatsJSON()`, `stop()`, `updateNetworkState(json)`, `parseToken(token)`, `measureTunnelPingMS()`, `measureTunnelDownloadMbps(SpeedProgress)`, `measureTunnelUploadMbps(SpeedProgress)`, `setSocketProtector(SocketProtector)`, `ensureTransportProtect()`.
- Exit node: `startExitNode(configJSON)`, `exitNodeStatsJSON()`, `exitNodeApprove(key)`, `exitNodeDeny(key)`, `exitNodeRevoke(key)`, `stopExitNode()`.

Go duplicates the TUN fd and sets it non-blocking; Android keeps ownership of the original fd. Kotlin accesses the engine via `NativeEngine` (`TunnelEngine`) and `NativeExitNode` (`TunnelExitNode`) interfaces so unit tests can use fakes.

## Testing

- **JVM unit tests** (`app/src/test/`): read shared fixtures from `core-engine/testdata/` directly. Cover token parsing & differential corpus, DNS validation, sealed preferences & profiles, health/reconnect/retry, notification throttling, split tunnel, updater policy & lineage, traffic formatting, QR round-trips, theme contrast, exit-node controller/stats/notifications, and the country data loader/installer plus the exact shipped asset.
- **Go unit tests** (`core-engine/` and `core-engine/exitnode/`): data plane, counters, DNS failover/TCP fallback, IPv6/UDP probing, TLS pins, `no_os_socket_test.go`, country-list parsing/merging/NAT64 and packet-level refusal (`country_block_test.go`), the shipped country asset (`country_block_asset_test.go`), exit-node policy/admission/caps/expiry, `TestOSSocketsConfinedToDial`, end-to-end exit-node test over an in-process DERP relay (`exitnode/e2e_test.go`), and bridge mutual exclusion/panic recovery.
- **Fuzzing** (`core-engine/fuzz_test.go`): targets for token parsing, strict CBOR, DNS parsing/truncation, DERP map filtering, IPv6 extension headers, outbound packets, and country-list parsing/lookup against a brute-force scan. `go test ./...` runs the seed corpus under `testdata/fuzz/`. Run longer sessions with `go test -run '^$' -fuzz '^FuzzParseToken$' -fuzztime 5m .` and commit any new corpus file (then rebuild the AAR since `testdata/` is inside the source hash).
- **TLA+ model checking** (`formal/`, JDK 21 + Python 3.10+):
  - `python3 -m unittest discover -s formal -p 'test_*.py'`
  - `formal/check.sh --regressions` (baseline, saved counterexample replays, focused progress/network-loss models)
  - `formal/check.sh --extended` (larger scenarios and sampling; sharded 6 ways in `formal.yml`)
  - See `formal/README.md` for model scope and code mapping. Do not run two TLC processes concurrently in one checkout.
- **Instrumented tests** (`app/src/androidTest/`): VPN startup/consent, pairing, QR image import, Keystore preference migration, and Android `stopSelf(startId)` behavior via debug-only `StartIdProbeService`.
- **Emulator suites** (AVD `OpenTailcat_API35`; `scripts/emulator/start.sh` boots headless if no device is connected):

  ```bash
  ./gradlew assembleGithubDebug assembleGithubDebugAndroidTest
  scripts/emulator/ci-smoke.sh
  OTC_LIVE_TOKEN=... scripts/emulator/ci-live.sh
  adb emu kill && ./gradlew --stop
  ```

  `ci-smoke.sh` runs on PRs; `ci-live.sh` runs on `main` and nightly when `OTC_LIVE_TOKEN` is configured (never on PRs) and requires mobile data for roaming (`roam-test.sh` exits 77 and fails the suite if unavailable). `scripts/emulator/lib.sh` redacts token-like strings from logs. Offline Android native socket-protection tests run via `ANDROID_NDK_HOME=<pinned-ndk> scripts/emulator/native-protect-test.sh`.
- **Phase 8 capture tooling** (`scripts/phase8/`): host-side pcap analyzer and synthetic self-test (`e2e-analyze.sh`). Real ARM64 physical dual-capture testing is still open.

## Dependencies and provenance

`third_party/tailcat` is pinned to the unmodified upstream SSH-signed tag `v0.8.0` (commit `b9491c13`), which includes `Client.DERPRegion()`, UDP forwarding, and the server hooks used by `exitnode`. Re-pin to the next signed tag when available. See `third_party/PROVENANCE.md`, including the upstream features OpenTailcat deliberately does not support (`--hybrid` addresses are rejected as unknown token fields) or patches (static-monitor fallback).

`core-engine/go.mod` replaces `github.com/tailscale/tailcat` with `./tailcat-integration`, generated from `third_party/tailcat` plus `core-engine/integration/tailcat.patch` via `core-engine/integration/sync_tailcat.py`. Both tests and `build-aar.sh` verify the copy with `sync_tailcat.py --check`. See `core-engine/integration/README.md`.

## Exit node

Rules and behavior for "Share this phone":

- **Package boundary**: `core-engine/exitnode/` is separate from `package engine` so `package engine` keeps its no-OS-socket rule (invariant 2). All OS dials in `exitnode` stay in `exitnode/dial.go`, enforced by `TestOSSocketsConfinedToDial`.
- **Mutual exclusion with client VPN**: a running client VPN would capture the exit node's outbound dials and loop them. Blocked in three places: natively under `globalCore.mu` (`Prepare` refuses while `exitNodeClaim` is set; `StartExitNode` refuses unless client state is `STOPPED`/`FAILED` and not stopping), in Android (`TunnelController.startBlocker` blocks every VPN start including Always-on), and as a poll backstop in `ExitNodeController.refresh`.
- **Admission and session lifetime**: default mode is host approval (`AdmitApproval`), parking unknown device keys as pending until approved. Open mode (`AdmitOpen`) always requires a TTL (max 24 h). `NoExpiry` ("No time limit") is only allowed with `AdmitApproval`, checked in both Kotlin and Go. Every session generates fresh node and preshared keys in memory, discarded on stop or process death, and the node enforces expiry itself.
- **Custom relay**: defaults to the nearest public DERP region. Users can optionally enter a self-hosted relay hostname plus DERP/STUN ports (defaults 443 / 3478); the bridge builds a private region (ID 900) so no public DERP map is fetched and embeds it in the minted token. IP literals, URLs, and loopback/metadata names are rejected, and `InsecureForTests` is never enabled. Not persisted across sessions.
- **Destination filtering**: `exitnode/policy.go` blocks loopback, RFC 1918, CGNAT, link-local, multicast, unspecified, documentation, and reserved ranges, unmapping 4-in-6 and decoding well-known (`64:ff9b::/96`) and RFC 8215 (`64:ff9b:1::/48`) NAT64 addresses. Carrier-specific NAT64 prefixes are not yet read from `LinkProperties`. `AllowLocalDestinations` is test-only and unreachable via bridge JSON (`TestBridgeConfigCannotAllowLocalDestinations`).
- **Socket protector swap**: while the exit node runs (no VPN active), `exitnode_bridge.go` installs `allowAllProtector` so `netns` socket creation succeeds, and restores the previous protector on exit.
- **Stats contract and privacy**: never log keys, codes, or guest destinations. Stats JSON schema v1 is pinned by `core-engine/testdata/exitnode_stats.json` (`TestStatsContract`, `ExitNodeStatsTest`); regenerate with `OTC_UPDATE_FIXTURES=1 go test -run TestStatsContract ./exitnode`.
- **Approval UX**: product code never auto-approves guests. Because guests don't show a device ID, the host cannot independently verify who is asking; the UI warns if more than one pending device appears at once.
- **Android service and UI**: `ExitNodeService` is `START_NOT_STICKY` with `foregroundServiceType="specialUse"` (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`), `stopWithTask="false"`, and a `PARTIAL_WAKE_LOCK` (TTL + 60 s for timed shares, or 1 h chunks renewed by the poll loop for no-limit shares). `ShareScreen` sets `FLAG_SECURE` while showing the code, Copy marks clipboard data `EXTRA_IS_SENSITIVE` on API 33+, and notifications show only counts, bytes, and time left.
- **Engine DNS failover**: `net.DefaultResolver` in `core-engine/main.go` rotates past timed-out Android DNS servers so fetching the public DERP map still works when the first configured resolver is dead. One timeout from a flaky server can still bump preference back onto a dead one; hedged queries remain on the backlog.

Current verification state: emulator start/stop, foreground service, notification, and VPN interlock are verified; a physical guest phone connected through an emulator host over mobile data with approval and forwarded real traffic (~170 flows, ~11 MB). Physical phone hosting, long-session battery/doze behavior, and TLA+ modeling of the interlock are still open.

## Remaining work

- Physical ARM64 dual-capture leak acceptance (including split tunnel and forced pump failure).
- Real-device Wi-Fi/cellular roaming, captive portal, and doze testing.
- Country filter: physical-device validation and a scheduled refresh of the bundled RIR snapshot at release time. Only the well-known NAT64 prefix (`64:ff9b::/96`) is unwrapped; RFC 8215 and carrier-specific prefixes are not, and registry data is allocation-based, so the filter is best effort.
- Testing against a gateway with IPv6 WAN, or documenting a translation policy.
- Production release signing, v3 key-rotation lineage, and upgrade migration testing.
- Upstream WireGuard peer counter API (counters remain 0 until available).
- Tailcat hybrid (ML-KEM-768) handshake support, deliberately deferred. Tailcat v0.8.0's `--hybrid` / `Server.Hybrid` is experimental and opt-in, so hybrid tokens are rejected today as unknown field `y` in both parsers (see `third_party/PROVENANCE.md`). Revisit when upstream drops the "experimental" label or we run a `--hybrid` gateway. It is a token schema change (update `core-engine/testdata/*.json`, both parsers, and both test suites): allow `y` (true only), require `q` to be 16 bytes when `y` is set (32 otherwise) in `token.go`/`cbor_strict.go` and `TokenParser.kt`, add `generate-fixtures` cases, and regenerate the differential corpus. The client then needs nothing else, because `lifecycle.go` passes the raw token to upstream. Exit node: a `Server.Hybrid` bridge option plus a share-UI toggle, noting that hybrid and non-hybrid peers cannot interoperate (no fallback).
- Device verification of the TLA+ VPN lifecycle fixes (`stopSelf(startId)` with queued starts, STOP after a failed start followed by a quick Connect; the non-VPN `StartIdProbeService` passed on API 35 ARM64 and x86_64 CI, but full VPN flows still need device tests).
- Exit node:
  - Physical phone as host and battery/doze measurements (especially for "No time limit" sessions).
  - Stable per-profile client key so guest reconnects don't require re-approval.
  - TLA+ coverage of the client/exit-node interlock.
  - TCP idle timeout and per-peer flow caps.
  - Hedged DNS queries in the Go engine resolver.
  - Warning in the share UI when a third-party VPN is active.
  - Reading the active network's NAT64 prefix (`LinkProperties.getNat64Prefix()`) for destination filtering.
  - Per-guest direct vs. relayed indicator (`CurAddr` vs. `Relay`).
  - Optional persistence for custom relay settings across sessions.
