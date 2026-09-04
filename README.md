# OpenTailcat

OpenTailcat is an independent Android client for the control-plane-free
`tailscale/tailcat` protocol. It pairs directly with a user-controlled gateway
from a compact `tc...` token.

> OpenTailcat is an independent community project. It is not affiliated with,
> sponsored by, or endorsed by Tailscale Inc.

## Safety status

**OpenTailcat 1.2.2 is a development build and must not be distributed or relied
on as a production privacy VPN.** The Android shell, Go Mobile AAR, Tailcat
handshake, official token parser, TCP proxy, and userspace netstack UDP proxy are
integrated. IPv4 test-routing capabilities are true so Connect can run with a
live token. `ipv6` is false. Android installs `0.0.0.0/0` and `::/0` after pumps
are live. This is not a production privacy VPN.

### Audited status

- **Phase 0 (Fail-Closed Negotiation)**: Implemented. API v2 capability contract
  fails closed when native capabilities are incomplete.
- **Phase 1 (Reproducible Builds)**: Implemented. Deterministic AAR builds with Go
  1.27.1, NDK r29 (29.0.14206865), and 16 KB ELF load alignment.
- **Phase 2 (Token Alignment)**: Implemented. Canonical CBOR token parsing with
  duplicate key rejection, timestamp validation, and legacy token migration handling.
- **Phase 3 (Tunneled UDP Data Plane)**: Implementation complete in code. Unified
  gVisor netstack proxy routes UDP datagrams through `Client.DialUDP` without
  direct OS UDP sockets; upstream `Client.DialUDP` / `OnUDPForward` exist.
  IPv4 `udp` is test-enabled; physical leak acceptance is still pending.
- **Phase 4 (DNS routing)**: PROFILE/FORCED resolver routing, pending-config, and
  omit-means-preserve exist. The engine does not inspect DNS TC bits. IPv4 `dns`
  is test-enabled.
- **Phase 5 (IPv6)**: Android installs `::/0` after pumps are live. Native
  proxies IPv6 TCP/UDP with a 250ms dial timeout; ICMPv6 echo is dropped;
  oversized IPv6 gets a local Packet Too Big. `ipv6` remains false until live
  dual-stack evidence.
- **Phase 6 (Lifecycle)**: Cancellable prepare, readiness barriers, pump-failure
  `FAILED`, bounded stop, `detachTun`, and `disarmPumps` exist. After `prepare`,
  Android establishes a host-only TUN, attaches pumps, then installs
  `0.0.0.0/0`/`::/0` and reattaches. The VPN service is sticky and is not stopped
  when the UI task is dismissed. A wanted-session flag restarts the tunnel after
  process death. `twoPhaseStart` and `cancelSafeLifecycle` are test-enabled.
- **Phase 7 (Telemetry)**: Schema version 2. Kotlin rejects v1 and requires live
  `RUNNING` health. RTT is sampled from `DiscoPing` while a bridge is running;
  jitter is null until three samples. WireGuard peer counters stay 0 (upstream
  `Client` has no Status API). `liveStats` is test-enabled.
- **Remaining**: live IPv6 egress evidence, Phase 8 physical leak capture, production signing.

## Native engine API

The bundled AAR exposes:

```text
getCapabilitiesJSON() -> String
prepare(token: String)
attachTun(tunFd: Long)
detachTun()
disarmPumps()
getStatsJSON() -> String
stop()
updateNetworkState(json: String)
parseToken(token: String)
measureTunnelPingMS()
measureTunnelDownloadMbps()
measureTunnelUploadMbps()
```

Current behavior:

1. `getCapabilitiesJSON` reports API v2 with IPv4 test-routing capabilities true
   (`ipv6` false). Kotlin may install `0.0.0.0/0` and `::/0` after pumps are live.
   This is not leak-free.
2. `prepare` validates an official token, completes a Meow/Meowed handshake, and
   allows TCP-only gateways (DNS over TCP; other UDP dropped). `stop` cancels an
   in-flight `prepare`.
3. `attachTun` duplicates the descriptor and returns after required pumps have
   entered their loops. Pump death reports `FAILED`. `detachTun` stops pumps and
   keeps the prepared client. `disarmPumps` clears pump-failure so default
   routes can be installed and the TUN reattached without a `FAILED` race.
4. `updateNetworkState` accepts Android LinkProperties JSON. Absent `dnsPolicy`
   preserves the pending resolver policy.
5. `stop` is bounded and idempotent. `ipv6` stays false.

## Token compatibility

Current official tokens use:

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte server node public key,
  "k": 32-byte server disco public key,
  "q"?: 32-byte WireGuard pre-shared key,
  "i"?: positive DERP region ID,
  "r"?: non-empty array of embedded DERP region metadata
}))
```

The Kotlin and Go parsers share one deterministic 43-vector corpus generated
from the pinned upstream version. Official tokens are passed to upstream
unchanged. Historical numeric-`r` tokens are classified as
`LEGACY_REISSUE_REQUIRED` and cannot connect; no disco key is invented. Both
parsers reject aliases, unknown or duplicate fields, padded/non-URL Base64,
surrounding whitespace, malformed/oversized CBOR, invalid key lengths, invalid
timestamps, and expired tokens. Embedded DERP nodes cannot set `x`
(`InsecureForTests`); unknown nested region/node fields and loopback,
link-local, unspecified, or multicast `h`/`4`/`6` values are rejected.

## Build and verification

Requirements for the current tree are JDK 21, Android SDK 35, Android NDK
29.0.14206865 (r29), and Go 1.27.1.

```bash
cd core-engine
go test -race ./...
go vet ./...

cd ..
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

Rebuild the AAR whenever `core-engine`, `third_party/tailcat`, Go dependencies,
or native build flags change:

```bash
./core-engine/build-aar.sh
```

## Repository layout

```text
app/                         Android application and bundled AAR
core-engine/                 Go Mobile adapter and TUN proxy
third_party/tailcat/         git submodule of github.com/tailscale/tailcat
handoff.md                   audited remediation plan and release gates
PRIVACY_POLICY.md            current network and data disclosure
SECURITY.md                  current controls and known limitations
THIRD_PARTY_NOTICES.md       dependency and provenance notices
```

## License

Copyright (c) 2026 Omar Alaaeldein.

OpenTailcat is licensed under the Apache License 2.0. The Tailcat submodule
retains its BSD 3-Clause license and attribution. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
