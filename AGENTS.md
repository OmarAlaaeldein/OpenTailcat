# OpenTailcat engineering guide

OpenTailcat is an Android client for **self-hosted Tailcat exit gateways**.
Paste a `tc...` token and the phone sends IPv4/IPv6 TCP, UDP, and DNS through
that gateway. There is no account, control plane, or server component in this
repo: the user runs the gateway.

This file is the working contract for anyone, human or LLM, changing the
project: how the pieces fit, which invariants must hold, and how to build,
test, and release. It is the single source for project status and remaining
work; other docs link here.

- Android: min API 26, compile API 37, target API 35; application id
  `com.tailcat.vpn`
- Toolchain: Kotlin 2.4.21, Gradle 9.9.1, JDK 21, Go 1.27.4
- Versions: `gradle/libs.versions.toml`, `core-engine/go.mod`
- Current version: 1.5.9 (versionCode 48); notes in `docs/releases/`

## Status

Dual-stack test-routing development preview (`testRouting: true` in the
capability payload). It connects with a live token but is not a
release-accepted data plane. Do not rely on it for privacy-sensitive traffic.

## Architecture

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

- **Kotlin** owns the UI, profile storage, `VpnService` lifecycle,
  notifications, split tunnel, updater, and speed-test orchestration
  (including the device-route measurement path).
- **Go** owns the tunnel: it terminates TUN packets in a gVisor userspace
  stack, proxies flows with `Client.DialTCP`/`DialUDP`, applies DNS, MTU, and
  IPv6 policy, and reports stats.
- gomobile exposes the engine as `com.tailcat.golib.engine.Engine`, checked in
  as `app/libs/libtailcat.aar`.

A packet's path: app → TUN fd → engine reader → gVisor TCP/IP stack →
`Client.DialTCP`/`DialUDP` → WireGuard/Magicsock → gateway → Internet. Return
traffic reverses. Application flows never use an OS socket, and the app does
not exclude its own UID from the tunnel.

## Repository map

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/tailcat/vpn/ui/` | Compose UI: `MainActivity`, home (connect ring, profiles, telemetry), settings, speed test, theme |
| `app/src/main/java/com/tailcat/vpn/service/` | `TailcatVpnService` (VpnService, TUN, notifications), `TunnelController` (UI-facing state machine and reconnect), `TunnelEngine` (gomobile impl of `NativeEngine`), health and split-tunnel helpers |
| `app/src/main/java/com/tailcat/vpn/core/` | Token parsing, DNS validation, egress-IP audit, speed test, updater, TLS/SPKI pins |
| `app/src/main/java/com/tailcat/vpn/data/` | AES-256-GCM sealed preferences (Android Keystore) and profile repository |
| `core-engine/` | Go engine. Start at `main.go` (engine + capabilities), `lifecycle.go`, `netstack_proxy.go` (data plane), `tunnel_dns.go`, `token.go`; `bridge.go` holds gomobile exports |
| `core-engine/testdata/` | Token and capability fixtures read by both test suites |
| `core-engine/build-aar.sh` | Reproducible AAR build; writes `app/libs/libtailcat.aar` and `.sourcehash` |
| `third_party/tailcat` | Upstream Tailcat submodule, pinned and unmodified |
| `app/src/test/`, `app/src/androidTest/` | JVM unit tests, instrumented tests |
| `scripts/emulator/`, `scripts/phase8/` | adb emulator suites; dual-capture leak tooling |
| `.github/workflows/` | `ci.yml` gates; `emulator.yml` smoke and live-gateway suites |
| `docs/` | Documentation index, release notes, signing notes, screenshots |

### Start here by task

| Change | Read first |
| --- | --- |
| Pairing, QR, saved profiles | `ui/screens/home/ProfileSelector.kt`, `AddProfileDialog.kt`, `data/ProfileRepository.kt` |
| Connect/disconnect, reconnect, errors | `service/TunnelController.kt`, `service/TailcatVpnService.kt`, `core-engine/lifecycle.go` |
| TUN configuration, DNS, routes | `service/VpnInterfaceSpec.kt` and its test, `attachTun` in `lifecycle.go` |
| Packet handling, UDP table, MTU | `core-engine/netstack_proxy.go`, `tunnel_dns.go`, and the `_test.go` files beside them |
| Token format or validation | `core-engine/token.go`, `core/TokenParser.kt`, `testdata/token_fixtures.json` |
| Capabilities / fail-closed parsing | `routeReady()` in `core-engine/main.go`, `testdata/capabilities.json`, `service/TunnelEngine.kt` |
| Telemetry, stats, speed test | `core-engine/speedtest.go`, `core/speedtest/`, `ui/screens/home/components/TelemetryCard.kt` |
| In-app updater | `core/update/`, `UpdatePolicy.kt`, `docs/signing.md` |
| Settings surfaces | `ui/screens/settings/`, `data/PreferencesStore.kt` |
| Release | "Release process" below, `app/build.gradle.kts`, `docs/releases/` |

## Safety invariants

1. Never install `0.0.0.0/0` or `::/0` for an engine that has not proven live pumps.
2. Never use ordinary OS sockets for traffic claimed to be tunneled.
3. Never report `CONNECTED` until authenticated transport and all pumps are live and native health is fresh.
4. Never synthesize telemetry, reachability, handshake, speed, or egress values.
5. A local ICMP reply or public Internet probe is not a gateway test.
6. Close the TUN immediately after startup, pump, or health failure.
7. Reject invalid or expired tokens in both Android and native code.
8. Development preview releases use the existing development key for upgrade compatibility; production signing and migration remain future work.
9. The app does not exclude its own UID. Only Tailcat transport sockets are protected with `VpnService.protect`; gateway measurements use `Client.DialTCP`/`DialUDP`.
10. Keep secrets, live tokens, signing keys, and captures out of the repo and public logs.

## Build and verify

Prerequisites: JDK 21 and the Android SDK (platform 37, matching build-tools,
NDK `29.0.14206865`); Go 1.27.4 only for `core-engine/`. A fresh clone needs
`git submodule update --init`; point Gradle at the SDK with `local.properties`
(`sdk.dir=...`) or `ANDROID_HOME`. Kotlin-only work builds against the
checked-in AAR and needs neither Go nor the NDK.

Fast inner loop (one flavor, no device):

```bash
cd core-engine && go test ./...
cd .. && ./gradlew testGithubDebugUnitTest lintGithubDebug
./gradlew assembleGithubDebug
```

Full gate (CI plus the release build checks):

```bash
cd core-engine && go test ./... && go test -race ./... && go vet ./... \
  && go run honnef.co/go/tools/cmd/staticcheck@v0.8.2 ./... \
  && go run golang.org/x/vuln/cmd/govulncheck@v1.8.0 ./... && gofmt -l .
cd .. && ./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest \
  lintGithubDebug lintFdroidDebug
./gradlew assembleGithubDebug assembleGithubDevelopment
./gradlew assembleFdroidRelease -PnoAbiSplits && ./gradlew bundleGithubRelease
```

Rules that trip people up:

- **Always name the flavor.** `testDebugUnitTest`, `assembleRelease`, and
  `lint` alone are ambiguous across `github` and `fdroid`.
- `-PnoAbiSplits` builds one APK with both ABIs; ABI splits cannot be combined
  with `bundleRelease` (AGP issue 402800800). F-Droid releases need it.
- The AAR is **checked in**. After any change under `core-engine/`, rebuild it
  and commit `app/libs/libtailcat.aar` with
  `app/libs/libtailcat.aar.sourcehash`; CI fails when the hash does not match
  the source tree.

```bash
env GOTOOLCHAIN=go1.27.4 core-engine/build-aar.sh && unzip -l app/libs/libtailcat.aar
```

The AAR requires Go 1.27.4, NDK r29 (`29.0.14206865`), 16 KB ELF alignment,
`-javapkg=com.tailcat.golib`, androidapi 26, and arm64 + amd64 targets.
Cross-host CI compares the module list, Java API, and source hash.

`scripts/phase8/run-host-gates.sh` bundles most host-side gates in one run:
Go race/vet, the Phase 8 analyzer self-check, GitHub unit tests and lint, and
the AAR hash plus sourcehash checks.

## Release process

Development previews are signed with the build machine's Android debug
keystore (`~/.android/debug.keystore`), whose certificate is pinned as
`dd767011...afa8d6` in `UpdatePolicy.kt` and `docs/signing.md`.
`assembleGithubDevelopment` uses it automatically, so releases must be built on
a machine whose debug key matches that pin; otherwise Android rejects the
upgrade as a different signer.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `docs/releases/<version>.md` and
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Build the publishable APKs with the real repository slug. The slug is
   deployment configuration; the tracked default is a neutral placeholder.

   ```bash
   ./gradlew assembleGithubDevelopment -PopentailcatRepoSlug=<owner>/<repo>
   ```

4. Verify before publishing:

   ```bash
   BT=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)
   "$ANDROID_HOME/build-tools/$BT/aapt2" dump badging <apk> | head -1   # versionCode/Name
   "$ANDROID_HOME/build-tools/$BT/apksigner" verify --print-certs <apk>  # dd7670... pin
   unzip -p <apk> 'classes*.dex' | strings | rg -o '[A-Za-z0-9_.-]+/OpenTailcat'
   ```

   The last check must print the real slug, not `opentailcat/OpenTailcat`;
   otherwise the in-app updater points at a nonexistent repository.

5. Name assets `OpenTailcat-<version>-<abi>.apk`, write
   `OpenTailcat-<version>-SHA256SUMS.txt`, and fill the hashes into the
   release notes.
6. Commit, tag `v<version>` (annotated), push `main` and the tag, then create
   the release:

   ```bash
   gh release create v<version> --title "OpenTailcat <version> (versionCode NN)" \
     --notes-file docs/releases/<version>.md <apks> <sums>
   ```

The `github` flavor self-updates from GitHub Releases and pins accepted signer
certificates in `UpdatePolicy.kt`; `fdroid` compiles the updater out. Never
embed personal account names or tokens in tracked sources.

## Conventions and pitfalls

**UI**

- Compose + Material3 with the dark theme tokens in `ui/theme` (`AccentCyan`,
  `BgDark`, `SurfaceElevated`, ...). One package per screen under
  `ui/screens/`, with a `ViewModel` where state outlives composition.
- Dialogs that show or accept credentials must set
  `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`; token fields
  use `KeyboardType.Password`. The QR dialog relies on the secure flag because
  the code itself contains the token.
- No camera permission. QR display uses ZXing `core` to encode; scanning
  launches a compatible external scanner intent and falls back to paste. The
  scanner receives the token, so the UI says so.

**Tokens and storage**

- Treat every `tc...` string as a secret: no logs, notifications, clipboard
  side effects, crash reports, or tests with live tokens.
- Profiles are stored in sealed preferences (AES-256-GCM, Android Keystore).
  Do not add a plaintext fallback; a value that cannot be sealed is not
  written.

**Engine**

- Every exported bridge function recovers panics as errors; do the same for
  any new export.
- No application-flow `net.DialUDP`/OS sockets. Traffic goes through TUN,
  gVisor, and `Client`.
- Never fake counters or freshness. WireGuard counters stay 0 until upstream
  exposes a status API; health and `CONNECTED` follow invariant 3.

**Contracts**

- Token or capability changes require updating
  `core-engine/testdata/*.json`, both parsers, and both test suites. The
  capability fixture is a pinned contract.
- Do not patch `third_party/tailcat`. Re-pin to a signed tag instead; see
  "Dependencies and provenance".
- Dependency updates are manual (Dependabot is disabled). A bump that changes
  native code lands with a rebuilt AAR and test output.

**Docs**

- This file is the source of truth. When status, commands, versions, or
  remaining work change, update it here; keep `README.md` product-facing,
  `docs/README.md` the reader-facing index, and `docs/releases/` historical.

## Data plane

The engine terminates TUN packets in a gVisor userspace stack and carries
them to the gateway with Tailcat's `Client`. No application-flow
`net.DialUDP` exists in `core-engine`; the only OS sockets are upstream
Magicsock/DERP transport and the gateway's own egress.

| Input | Handling | Egress |
| --- | --- | --- |
| IPv4 TCP | gVisor terminates, proxies via `Client.DialTCP`; half-close forwarded; max 512 connections | WireGuard/Magicsock |
| UDP/53 | To TUN destination (`PROFILE_RESOLVER`) or `ForcedDNS` (`FORCED_RESOLVER`); with `tcpOnly`, DNS-over-TCP, TC header above EDNS size (or 512 B) | Gateway |
| Other IPv4 UDP | `Client.DialUDP`, one device-wide table of 1024 flows (LRU eviction); 30 s idle pre-reply, 2 min after | Gateway |
| UDP > 1232 B | Dropped, counted in `mtuExceeded`; unfragmented gets local ICMP Frag-Needed (1260) / Packet Too Big (1280); local ICMP capped at 100/s | No request |
| IPv6 TCP/UDP | Same dial budget as IPv4 (15 s TCP / 10 s UDP); public IPv6 rejected pre-dial when `ipv6Egress` is false (NAT64 `64:ff9b::/96` exempt) | Gateway if it has IPv6 WAN, else RST/drop |
| ICMPv4/v6 echo | Dropped, counted in `policyRejections` | No request |
| Over-MTU | ICMPv6 Packet Too Big; IPv4 Frag-Needed if DF set, else dropped | No request |
| Speed test | `Client.DialTCP` when CONNECTED; otherwise device routes | Gateway when CONNECTED |

Notes: TCP buffers 128 KiB default / 1 MiB max. `GATEWAY_RESOLVER` is legacy
for `PROFILE_RESOLVER`. Exit-IP audit runs through `Client.DialTCP`; the
in-app public-IP card is a separate device-route request. `updateNetworkState`
forwards LinkProperties to upstream netmon (via type-checked reflection in
`netmon_hook.go`; a test fails if upstream layout changes). Telemetry is
schema 2: RTT from `DiscoPing` ~5 s, jitter null until 3 samples, DERP region
from `Client.DERPRegion()`; WireGuard counters stay 0 (no upstream API),
`tunTxBytes`/`tunRxBytes` count TUN packets. Tokens are validated twice
(Kotlin UX, Go authority, shared fixtures). GitHub flavor has the updater;
F-Droid compiles it out.

## Lifecycle

Native engine states: `STOPPED` -> `PREPARING` -> `PREPARED` -> `ATTACHING` ->
`RUNNING`, plus `STOPPING` and `FAILED`. The UI shows a coarser state machine
(`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`, `DEGRADED`) and
only shows `CONNECTED` after native `RUNNING` plus fresh health.

- `prepare` validates the token, completes the handshake, may latch `tcpOnly`
  when UDP probing fails. Session context lets `stop` cancel a blocked
  prepare; a second prepare closes the previous client. The engine mutex is
  short-lived (no network I/O or pump wait under it).
- Android attaches a host-only TUN (no VPN DNS), detaches, then installs
  `0.0.0.0/0` + `::/0` with VPN DNS and attaches again. `attachTun` returns
  only after TUN reader, gVisor writer, UDP loop, and health loop entered.
- Unexpected pump exit sets `FAILED` and clears `healthUnixSec`. Kotlin
  requires native `RUNNING` + fresh health before showing CONNECTED.
- `stop` is idempotent and bounded; TUN closes before native stop.
  `detachTun` returns to `PREPARED`; `disarmPumps` clears a pump failure
  without stopping.
- On mid-session failure the service closes the TUN, stops the engine, and
  reconnects with backoff while `vpnWanted` is set. Between attempts there is
  no TUN; Always-on VPN with Block connections without VPN closes that gap.

## Token contract

`"tc" + Base64URL(CBOR({p, k, q?, i?, r?}))` — `p`/`k` 32-byte server node/
disco keys, optional `q` 32-byte PSK, optional DERP region `i` or structured
`r`. Optional `exp`/`iat` (identical Kotlin/Go semantics). Both parsers
reject whitespace, padding, duplicate CBOR keys, aliases, unknown fields,
trailing objects, oversized/indefinite structures, fractional/overflowing
timestamps, `exp < iat`, and expired tokens. Legacy numeric-`r` returns
`LEGACY_REISSUE_REQUIRED` and is never dialed. Disco key is never derived
from the node key. Embedded DERP maps allow region `i`/`c`/`m`/`N` and node
`n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`; node `x` (`InsecureForTests`) and
loopback/unspecified/link-local/multicast addresses are rejected.

`core-engine/testdata/token_fixtures.json` (90 cases, `go run
./cmd/generate-fixtures`) is read by both test suites.

## Capabilities

API v2. `dataPlane` is derived via `routeReady()` from the flags below,
pinned by `core-engine/testdata/capabilities.json` (parsed by both suites;
changing the contract requires updating the fixture and both sides). Kotlin
fails closed on unknown fields, missing/older API version, or any required
flag false. `testRouting` is display-only.

Flags: `ipv4` (default route, egress, teardown), `ipv6` (`::/0`, proxied
v6, fail-closed without WAN), `tcp`, `udp` (QUIC, bounds, MTU), `dns`
(policy, UDP/TCP fallback), `wireGuard`, `magicsock` (direct/DERP, roaming),
`twoPhaseStart`, `liveStats`, `cancelSafeLifecycle`, `dataPlane` (AND of all).

## Native API

`com.tailcat.golib.engine.Engine`: `getCapabilitiesJSON()`, `prepare(token)`,
`attachTun(tunFd)`, `detachTun()`, `disarmPumps()`, `getStatsJSON()`,
`stop()`, `updateNetworkState(json)`, `parseToken(token)`,
`measureTunnelPingMS()`, `measureTunnelDownloadMbps(SpeedProgress)`,
`measureTunnelUploadMbps(SpeedProgress)`, `setSocketProtector(SocketProtector)`,
`ensureTransportProtect()`. Every exported function recovers panics as
errors. Go duplicates the TUN fd (non-blocking); Android owns the original.

Kotlin talks to it through the `NativeEngine` interface so tests can fake the
engine (`TunnelEngineTest`); the gomobile implementation is `TunnelEngine`.

## Testing

- **JVM unit tests** (`app/src/test/`) cover token parsing, DNS validation,
  profile storage, reconnect/backoff, notification throttling, split tunnel,
  update policy, telemetry formatting, and QR round-trip.
- **Shared fixtures**: the `test` source set reads `core-engine/testdata/`, so
  a fixture change is a contract change exercised by both languages.
- **Instrumented tests** (`app/src/androidTest/`) cover VPN consent/startup,
  pairing, and preference storage. They need a device or emulator.
- **Emulator suites** (AVD `OpenTailcat_API35`; `start.sh` boots it headless
  when no device is attached):

  ```bash
  ./gradlew assembleGithubDebug assembleGithubDebugAndroidTest
  scripts/emulator/ci-smoke.sh          # no gateway, no secrets
  OTC_LIVE_TOKEN=... scripts/emulator/ci-live.sh   # live gateway; token is never printed
  adb emu kill && ./gradlew --stop
  ```

`scripts/emulator/lib.sh` redacts token-shaped strings from logs; keep it that
way. CI runs the smoke suite on pull requests and the live suite on `main` and
on the nightly schedule when the gateway token secret is set; the live job
never runs on a PR.

`scripts/phase8/` holds host capture and analysis tooling (`e2e-analyze.sh` is
a synthetic harness). It is not physical leak acceptance; that evidence is
still outstanding.

## Dependencies and provenance

`third_party/tailcat` is pinned to upstream `e00cdde6` (untagged/unsigned,
18 commits after signed `v0.7.0`; carries `Client.DERPRegion()` and UDP
forwarding). Re-pin to the next signed tag; do not patch the submodule. See
`third_party/PROVENANCE.md`. Dependency updates are manual (a bump changes
the AAR, so it lands with a rebuild + test output); Dependabot is disabled.

The Go module is `com.tailcat.vpn/engine` and replaces `github.com/tailscale/tailcat`
with `../third_party/tailcat`; the AAR embeds that copy.

## Glossary

- **Exit gateway** — a `tailcat serve exit-node` host that owns the public egress.
- **Token** (`tc...`) — self-contained credential with node/disco keys, optional PSK, DERP region, and expiry; no account or API key.
- **Profile** — saved gateway: token, name, DNS resolver and policy, MTU.
- **TUN** — Android virtual interface handed to the engine; the only source of tunneled packets.
- **gVisor** — userspace TCP/IP stack inside the engine that terminates those packets.
- **DERP / Magicsock / WireGuard** — Tailcat transport: encrypted links, NAT traversal, and relay fallback.
- **Egress IP** — public address the Internet sees; audited through `Client.DialTCP`, not the device-route card.
- **Test routing** — the current data-plane maturity flag (`testRouting`), shown in the UI and not accepted for privacy-sensitive traffic.
- **Phase 8** — the physical dual-capture leak-acceptance stage, still
  outstanding; `scripts/phase8/` has the host capture and analysis tooling.

## Remaining work

- Physical dual-capture leak acceptance on ARM64 (split tunnel, forced pump failure).
- Wi-Fi/cellular roaming and captives/doze evidence.
- Gateway with IPv6 WAN, or a documented translation policy.
- Production signing, lineage migration, and artifact verification.
- Upstream status API for WireGuard counters (they stay 0 until then).
- Reconnect TUN gap: recommend Always-on lockdown.
