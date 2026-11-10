# OpenTailcat engineering guide

Android client for Tailcat exit gateways. Working contract: what the engine
does, which invariants must hold, how to build and test it. This file is the
single source for project status and remaining work; other docs link here.

- Android: min API 26, compile API 37, target API 35
- Toolchain: Kotlin 2.4.21, Gradle 9.8.2, JDK 21, Go 1.27.3 (AGP in
  `gradle/libs.versions.toml`)
- Current version: 1.5.7 (versionCode 46); notes in `docs/releases/`

## Status

Dual-stack test-routing development preview (`testRouting: true` in the
capability payload). It connects with a live token but is not a
release-accepted data plane. Do not rely on it for privacy-sensitive traffic.

## Repository layout

| Path | Contents |
| --- | --- |
| `core-engine/` | Go engine exported through gomobile as `libtailcat.aar` |
| `app/` | Kotlin/Compose Android client and `VpnService` |
| `third_party/tailcat` | Upstream Tailcat submodule (unmodified) |
| `scripts/emulator/`, `scripts/phase8/` | Emulator tests; dual-capture tooling |
| `docs/` | Release notes, signing notes, history |

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

## Lifecycle

`STOPPED -> PREPARING -> PREPARED -> ATTACHING -> RUNNING`, plus `STOPPING`
and `FAILED`.

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

## Build and verify

```bash
cd core-engine && go test ./... && go test -race ./... && go vet ./... \
  && go run honnef.co/go/tools/cmd/staticcheck@v0.8.2 ./... && gofmt -l .
cd .. && ./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest \
  lintGithubDebug lintFdroidDebug
./gradlew assembleGithubDebug assembleGithubDevelopment
./gradlew assembleFdroidRelease -PnoAbiSplits && ./gradlew bundleGithubRelease
```

After native changes, rebuild the checked-in AAR:

```bash
env GOTOOLCHAIN=go1.27.3 core-engine/build-aar.sh && unzip -l app/libs/libtailcat.aar
```

`build-aar.sh` writes `app/libs/libtailcat.aar.sourcehash`; CI fails on
mismatch. AAR requires Go 1.27.3, NDK r29 (29.0.14206865), 16 KB ELF
alignment; cross-host CI compares module list, Java API, and source hash.
Always name the flavor in Gradle tasks (`testDebugUnitTest` is ambiguous).

Emulator suites (then `adb emu kill && ./gradlew --stop`):

```bash
./gradlew assembleGithubDebug assembleGithubDebugAndroidTest
scripts/emulator/ci-smoke.sh && scripts/emulator/ci-live.sh
scripts/phase8/run-host-gates.sh && scripts/phase8/e2e-analyze.sh
```

## Dependencies and provenance

`third_party/tailcat` is pinned to upstream `52fbad32` (untagged/unsigned,
17 commits after signed `v0.7.0`; carries `Client.DERPRegion()` and UDP
forwarding). Re-pin to the next signed tag; do not patch the submodule. See
`third_party/PROVENANCE.md`. Dependency updates are manual (a bump changes
the AAR, so it lands with a rebuild + test output); Dependabot is disabled.

## Remaining work

- Physical dual-capture leak acceptance on ARM64 (split tunnel, forced pump failure).
- Wi-Fi/cellular roaming and captives/doze evidence.
- Gateway with IPv6 WAN, or a documented translation policy.
- Production signing, lineage migration, and artifact verification.
- Upstream status API for WireGuard counters (they stay 0 until then).
- Reconnect TUN gap: recommend Always-on lockdown.
