# OpenTailcat engineering guide

Android client for Tailcat exit gateways. This file is the working contract for
the repository: what the engine does, which invariants must hold, and how to
build and test it.

- Root: repository checkout
- Android: min API 26, compile API 37, target API 35
- Toolchain: Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.2, JDK 21, Go 1.27.3
- Current version: 1.5.6 (versionCode 45); notes in `docs/releases/`

## Status

The client is a dual-stack test-routing build. Every capability in the native
contract is enabled so a live token can connect, and the payload carries
`testRouting: true` to mark that it is not a release-accepted data plane.
Physical-device leak capture, roaming evidence, and production signing are
still open (see [Remaining work](#remaining-work)). Treat the build as a
development preview, not a privacy product.

## Repository layout

| Path | Contents |
| --- | --- |
| `core-engine/` | Go engine exported through gomobile as `libtailcat.aar` |
| `app/` | Kotlin/Compose Android client and `VpnService` |
| `third_party/tailcat` | Upstream Tailcat submodule (unmodified) |
| `scripts/emulator/` | Emulator smoke and live-gateway tests |
| `scripts/phase8/` | Dual-capture tooling and pcap analyzer |
| `docs/` | Release notes, signing notes, history |

## Data plane

The native engine terminates packets from the TUN in a gVisor userspace stack
and carries them to the gateway with Tailcat's `Client`. No application-flow
`net.DialUDP` exists in `core-engine`; the only OS sockets are upstream
Magicsock/DERP transport and the gateway's own egress.

| Input | Native handling | Egress |
| --- | --- | --- |
| IPv4 TCP | gVisor terminates the stream, proxies with `Client.DialTCP`; half-close is forwarded and both directions finish; at most 512 connections | Tailcat WireGuard/Magicsock |
| UDP/53 | Proxied to the TUN destination (`PROFILE_RESOLVER`) or `ForcedDNS` (`FORCED_RESOLVER`). With `tcpOnly`, carried as DNS-over-TCP; answers above the client's EDNS size (or 512 B) return a TC header and question. The engine does not inspect TC bits, so a libc retry over TCP is an ordinary proxied connection | Gateway |
| Other IPv4 UDP | Proxied with `Client.DialUDP` through one device-wide table of 1024 flows; LRU eviction when full; 30 s idle before a reply, 2 min after | Gateway |
| UDP over 1232 B | Dropped and counted in `mtuExceeded`. Unfragmented datagrams get local ICMP Fragmentation Needed (1260) or Packet Too Big (1280); reassembled datagrams are dropped after reassembly. Local ICMP is capped at 100/s | No request |
| IPv6 TCP/UDP | Proxied with the IPv4 dial budget (15 s TCP / 10 s UDP). Public IPv6 is rejected before dial when `ipv6Egress` is false, after the DNS redirect; NAT64 `64:ff9b::/96` is exempt | Gateway, if it has IPv6 WAN; otherwise RST/drop so apps fall back to tunneled IPv4 |
| ICMPv4/v6 echo | Dropped and counted in `policyRejections` | No request |
| IPv6 over MTU | Local ICMPv6 Packet Too Big | No request |
| IPv4 over MTU | Local Fragmentation Needed when DF is set, otherwise dropped | No request |
| In-app speed test | `Client.DialTCP` when CONNECTED (`speed.cloudflare.com` resolved over DNS-over-TCP to `1.1.1.1:53`); otherwise ordinary app routes | Gateway when CONNECTED |

Other behavior worth knowing:

- The local stack uses 128 KiB default and 1 MiB maximum TCP buffers.
- `GATEWAY_RESOLVER` is legacy and maps to `PROFILE_RESOLVER`. Kotlin refuses
  Connect when the profile DNS server is IPv6 and prepare measured no gateway
  IPv6 egress.
- After attach, the engine performs an exit-IP audit through `Client.DialTCP`.
  The in-app public-IP card is a separate request on device routes.
- `updateNetworkState` forwards LinkProperties and the underlying interface;
  the engine hands them to upstream netmon and injects a network event so
  Magicsock rebinds. The netmon handle is reached by type-checked reflection in
  `netmon_hook.go`; a test fails if the upstream layout changes.
- Telemetry is schema 2. RTT comes from `DiscoPing` roughly every 5 s while a
  bridge runs; jitter is null until three samples. DERP region comes from
  `Client.DERPRegion()`. WireGuard peer counters stay 0 because upstream has no
  status API; `tunTxBytes`/`tunRxBytes` count packets at the TUN.
- Tokens are validated twice: strict Kotlin for early UX, strict Go as the
  authority, with shared fixtures. See [Token contract](#token-contract).
- Release builds are R8-shrunk; the GitHub flavor carries the in-app updater and
  the F-Droid flavor compiles it out.

## Safety invariants

1. Never install `0.0.0.0/0` or `::/0` for an engine that has not proven live
   pumps.
2. Never use ordinary client-side OS sockets for traffic claimed to be
   tunneled.
3. Never report `CONNECTED` until authenticated transport and all pumps are
   live and native health is fresh.
4. Never synthesize telemetry, reachability, handshake, speed, or egress
   values.
5. A local ICMP reply or public Internet probe is not a gateway test.
6. Close the TUN immediately after startup, pump, or health failure.
7. Reject invalid or expired tokens in both Android and native code.
8. Do not sign a release with the debug key.
9. The app does not exclude its own UID. Only Tailcat transport sockets are
   protected with `VpnService.protect`; other in-process traffic follows device
   routes. Gateway measurements use `Client.DialTCP`/`DialUDP`.
10. Keep secrets, live tokens, signing keys, and captures out of the repository
    and public logs.

## Lifecycle

`STOPPED -> PREPARING -> PREPARED -> ATTACHING -> RUNNING`, with `STOPPING` and
`FAILED` as terminal transitions.

- `prepare` validates the token, completes the Meow/Meowed handshake, and may
  latch `tcpOnly` when UDP probing fails. The session context lets `stop`
  cancel a blocked prepare. A second prepare closes the previous client. The
  engine mutex is short-lived; no network I/O or pump wait holds it.
- After `prepare`, Android builds a host-only TUN (no VPN DNS), attaches pumps,
  detaches, then installs `0.0.0.0/0` and `::/0` with VPN DNS and attaches
  again. `attachTun` returns only after the TUN reader, gVisor writer, UDP
  loop, and health loop have entered.
- An unexpected pump exit sets `FAILED` and clears `healthUnixSec`. Kotlin
  requires native `RUNNING` plus fresh health before showing CONNECTED.
- `stop` is idempotent and bounded; the TUN is closed before native stop.
  `detachTun` returns the engine to `PREPARED`; `disarmPumps` clears a pump
  failure without stopping.
- On mid-session failure the service closes the TUN, stops the engine, and
  reconnects with backoff while `vpnWanted` is set. Between attempts there is
  no TUN; Always-on VPN with Block connections without VPN closes that gap.

## Token contract

Official connectable tokens:

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte server node public key,
  "k": 32-byte server disco public key,
  "q"?: 32-byte WireGuard pre-shared key,
  "i"?: positive DERP region ID,
  "r"?: non-empty array of DERP region metadata
}))
```

Optional `exp`/`iat` timestamps are accepted with identical Kotlin and Go
semantics. Both parsers reject whitespace and padding, duplicate CBOR keys,
aliases and unknown fields, trailing objects, oversized or indefinite
structures, fractional or overflowing timestamps, `exp < iat`, and expired
tokens. Legacy numeric-`r` tokens return `LEGACY_REISSUE_REQUIRED` and are
never dialed.

The disco key is never derived from the node key. Embedded DERP maps allow
region fields `i`/`c`/`m`/`N` and node fields `n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`;
node `x` (`InsecureForTests`) is rejected, as are loopback, unspecified,
link-local, and multicast addresses.

`core-engine/testdata/token_fixtures.json` is the canonical corpus (90 cases,
generated by `go run ./cmd/generate-fixtures`). Both test suites read it.

## Capabilities

The native contract is API v2. `GetCapabilitiesJSON` builds `dataPlane` from
the individual flags via `routeReady()`, and the payload is pinned by
`core-engine/testdata/capabilities.json`, which the Android test suite also
parses. Changing the contract requires updating the fixture and both sides.

Kotlin fails closed on unknown fields, a missing or older API version, or any
required flag being false. `testRouting` is display-only.

| Flag | Covers |
| --- | --- |
| `ipv4` | Default route, public IPv4 egress, failure teardown |
| `ipv6` | `::/0`, proxied IPv6 TCP/UDP, fail-closed public IPv6 without gateway WAN |
| `tcp` | IPv4/IPv6 TCP, large transfers |
| `udp` | Generic UDP, QUIC, flow bounds, MTU behavior |
| `dns` | Resolver policy, UDP/TCP fallback, large responses |
| `wireGuard` | Encrypted peer transport |
| `magicsock` | Direct and DERP paths, roaming |
| `twoPhaseStart` | Warm attach before default routes |
| `liveStats` | Live counters, path, health, staleness |
| `cancelSafeLifecycle` | Cancellable prepare and bounded stop |
| `dataPlane` | Derived AND of the flags above |

## Native API

The AAR exports `com.tailcat.golib.engine.Engine`:

```text
getCapabilitiesJSON() -> String
prepare(token)
attachTun(tunFd)
detachTun()
disarmPumps()
getStatsJSON() -> String
stop()
updateNetworkState(json)
parseToken(token)
measureTunnelPingMS()
measureTunnelDownloadMbps(SpeedProgress)
measureTunnelUploadMbps(SpeedProgress)
setSocketProtector(SocketProtector)
ensureTransportProtect()
```

Every exported function recovers panics and returns them as errors. Go
duplicates the supplied TUN fd and sets it non-blocking; Android owns the
original descriptor.

## Build and verify

```bash
cd core-engine
go test ./...
go test -race ./...
go vet ./...
go run honnef.co/go/tools/cmd/staticcheck@v0.8.2 ./...
gofmt -l .

cd ..
./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug lintFdroidDebug
./gradlew assembleGithubDebug assembleGithubDevelopment
./gradlew assembleFdroidRelease -PnoAbiSplits
./gradlew bundleGithubRelease
```

After native changes, rebuild the checked-in AAR and inspect it:

```bash
env GOTOOLCHAIN=go1.27.3 core-engine/build-aar.sh
unzip -l app/libs/libtailcat.aar
# extract classes.jar, then:
javap -classpath classes.jar com.tailcat.golib.engine.Engine
```

`build-aar.sh` writes `app/libs/libtailcat.aar.sourcehash` over the
`core-engine` and `third_party` trees; CI fails when it does not match. The AAR
must be built with Go 1.27.3 and NDK r29 (29.0.14206865) with 16 KB ELF load
alignment. A single host reproduces the archive byte for byte; across hosts CI
compares the module list, exported Java API, and source hash instead.

The `github` and `fdroid` flavors make bare `testDebugUnitTest` and `lintDebug`
task names ambiguous; always name the variant.

Emulator suites (after which run `adb emu kill` and `./gradlew --stop`):

```bash
./gradlew assembleGithubDebug assembleGithubDebugAndroidTest
scripts/emulator/ci-smoke.sh
scripts/emulator/ci-live.sh
```

Host checks:

```bash
scripts/phase8/run-host-gates.sh
scripts/phase8/e2e-analyze.sh
```

## Dependencies and provenance

`third_party/tailcat` is a submodule pinned to upstream commit `52fbad32`
(`v0.7.0-17-g52fbad323`, 2026-10-07). That commit is untagged and unsigned;
upstream's newest signed tag is `v0.7.0`. The pin exists because it carries
`Client.DERPRegion()` and UDP forwarding. Re-pin to the next signed tag when
one exists, and record it in `third_party/PROVENANCE.md`. Do not patch the
submodule.

Dependency updates are manual: a module or submodule bump changes the compiled
AAR, so it lands together with a rebuild and the test output above. Dependabot
version and security updates are disabled.

## Remaining work

- Physical dual-capture leak acceptance on an ARM64 device, including split
  tunnel and forced pump failure.
- Wi-Fi/cellular roaming and captives/doze evidence.
- Gateway with IPv6 WAN, or a documented translation policy.
- Production signing, lineage migration, and artifact verification.
- Upstream status API for WireGuard counters; until then they stay 0.
- Reconnect has no TUN between attempts; recommend Always-on lockdown.

## Documentation rule

README, privacy policy, security notes, UI copy, and release notes describe
only verified behavior. Unfinished work belongs in this file and must be
labeled as such.
