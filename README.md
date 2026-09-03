# OpenTailcat

OpenTailcat is an independent Android client for the control-plane-free
`tailscale/tailcat` protocol. It pairs directly with a user-controlled gateway
from a compact `tc...` token.

> OpenTailcat is an independent community project. It is not affiliated with,
> sponsored by, or endorsed by Tailscale Inc.

## Safety status

**OpenTailcat 1.1.2 is a development build and must not be distributed or relied
on as a production privacy VPN.** The Android shell, Go Mobile AAR, Tailcat
handshake, official token parser, TCP proxy, and userspace netstack UDP proxy are
integrated. IPv4 test-routing capabilities are true so Connect can run with a
live token. `ipv6` is false (no `::/0`). This is not a production privacy VPN.

### Audited status

- **Phase 0 (Fail-Closed Negotiation)**: Implemented. API v2 capability contract
  fails closed when native capabilities are incomplete.
- **Phase 1 (Reproducible Builds)**: Implemented. Deterministic AAR builds with Go
  1.27.0, NDK r29 (29.0.14206865), and 16 KB ELF load alignment.
- **Phase 2 (Token Alignment)**: Implemented. Canonical CBOR token parsing with
  duplicate key rejection, timestamp validation, and legacy token migration handling.
- **Phase 3 (Tunneled UDP Data Plane)**: Implementation complete in code. Unified
  gVisor netstack proxy routes UDP datagrams through `Client.DialUDP` without
  direct OS UDP sockets; gateway `CapExitUDP` check and `AllowProxy` filtering
  exist. IPv4 `udp` is test-enabled; physical leak acceptance is still pending.
- **Phase 4 (DNS routing)**: PROFILE/FORCED resolver routing, pending-config, and
  omit-means-preserve exist. The engine does not inspect DNS TC bits. IPv4 `dns`
  is test-enabled.
- **Phase 5 (IPv6)**: Native TUN IPv6 is dropped. Android has no `::/0`. `ipv6`
  remains false.
- **Phase 6 (Lifecycle)**: Cancellable prepare, readiness barriers, pump-failure
  `FAILED`, and bounded stop exist. `twoPhaseStart` and `cancelSafeLifecycle`
  are test-enabled.
- **Phase 7 (Telemetry)**: Schema version 2 and live WireGuard peer counters exist
  while a bridge is running. Kotlin rejects v1 and requires live `RUNNING` health.
  RTT is a prepare-time snapshot; production jitter is always null. `liveStats`
  is test-enabled.
- **Remaining**: IPv6 dual-stack, Phase 8 physical leak capture, production signing.

## Native engine API

The bundled AAR exposes:

```text
getCapabilitiesJSON() -> String
prepare(token: String)
attachTun(tunFd: Long)
getStatsJSON() -> String
stop()
updateNetworkState(json: String)
parseToken(token: String)
```

Current behavior:

1. `getCapabilitiesJSON` reports API v2 with IPv4 test-routing capabilities true
   (`ipv6` false). Kotlin may install IPv4 `0.0.0.0/0`. This is not leak-free.
2. `prepare` validates an official token, completes a Meow/Meowed handshake, and
   rejects TCP-only gateways. `stop` cancels an in-flight `prepare`.
3. `attachTun` duplicates the descriptor and returns after required pumps have
   entered their loops. Pump death reports `FAILED`.
4. `updateNetworkState` accepts Android LinkProperties JSON. Absent `dnsPolicy`
   preserves the pending resolver policy.
5. `stop` is bounded and idempotent. `ipv6` stays false.

## Token compatibility

Current official tokens use:

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte server node public key,
  "k": 32-byte server disco public key,
  "i"?: positive DERP region ID,
  "r"?: array of embedded DERP region metadata
}))
```

The Kotlin and Go parsers share one deterministic 43-vector corpus generated
from the pinned upstream version. Official tokens are passed to upstream
unchanged. Historical numeric-`r` tokens are classified as
`LEGACY_REISSUE_REQUIRED` and cannot connect; no disco key is invented. Both
parsers reject aliases, unknown or duplicate fields, padded/non-URL Base64,
surrounding whitespace, malformed/oversized CBOR, invalid key lengths, invalid
timestamps, and expired tokens.

## Build and verification

Requirements for the current tree are JDK 21, Android SDK 35, Android NDK
29.0.14206865 (r29), and Go 1.27.0.

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
third_party/tailcat/         embedded v0.4.0-derived Tailcat source plus local patch
handoff.md                   audited remediation plan and release gates
PRIVACY_POLICY.md            current network and data disclosure
SECURITY.md                  current controls and known limitations
THIRD_PARTY_NOTICES.md       dependency and provenance notices
```

## License

Copyright (c) 2026 Omar Alaaeldein.

OpenTailcat is licensed under the Apache License 2.0. The embedded Tailcat code
retains its BSD 3-Clause license and attribution. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
