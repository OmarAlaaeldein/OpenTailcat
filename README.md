# OpenTailcat

OpenTailcat is an independent Android client for the control-plane-free
`tailscale/tailcat` protocol. It pairs directly with a user-controlled gateway
from a compact `tc...` token.

> OpenTailcat is an independent community project. It is not affiliated with,
> sponsored by, or endorsed by Tailscale Inc.

## Safety status

**The current `main` branch is a development build and must not be distributed
or relied on as a privacy VPN.** The Android shell, Go Mobile AAR, Tailcat
handshake, and TCP proxy are integrated. The native capability contract now
fails closed, so Android will not establish a default-route VPN until the
remaining data-plane phases and live release gates pass.

Known release blockers:

- Non-DNS UDP is opened with the process' ordinary `net.DialUDP`. OpenTailcat's
  UID bypasses the Android VPN so these packets use the device network instead
  of the Tailcat/WireGuard path.
- Android installs only an IPv4 default route. IPv6 is not assigned or routed
  to the TUN and may continue over the underlying network.
- Transport mode and RTT are sampled once during startup, jitter is always
  zero, and byte counters describe TUN traffic rather than authoritative
  WireGuard counters.
- The existing tests do not establish a live Android tunnel or prove TCP, UDP,
  DNS, IPv4, IPv6, DERP fallback, roaming, or leak behavior.
- Full data-plane release behavior has not been implemented or live-tested;
  the bundled AAR therefore remains intentionally fail-closed.
- Release signing and end-to-end testing with a production key and live gateway
  have not passed.

`getCapabilitiesJSON()` returns API v2 with `dataPlane: false` and all unproven
capabilities set to false. Until the release gates in [handoff.md](handoff.md)
pass, capability negotiation fails closed and Android refuses to install a
default route.

## Current implementation

The repository currently contains:

- A Jetpack Compose Android application with encrypted profile storage,
  validated underlying-network monitoring, per-app exclusions, and an Android
  `VpnService` lifecycle.
- `app/libs/libtailcat.aar`, containing ARM64 and x86-64 Go Mobile bindings for
  `com.tailcat.vpn.engine.Engine`.
- A Tailcat adapter that validates a token, creates an upstream client, and
  completes a Meow/Meowed reachability handshake before Android creates the
  TUN.
- A gVisor TCP terminator that sends application TCP streams through upstream
  `Client.DialTCP`.
- A DNS-over-TCP bridge that currently sends intercepted UDP/53 requests through
  Tailcat to `1.1.1.1` with `8.8.8.8` as fallback. It does not honor the
  packet's original resolver destination.
- A locally generated ICMP echo responder. Its replies prove only that the
  local adapter responded; they do not prove gateway or Internet reachability.
- A TLS exit-IP audit performed through Tailcat `DialTCP`.

The embedded `third_party/tailcat` tree is derived from upstream signed tag
`v0.4.0` (`ce6fedcabc220bab3b94d470ab330219111eeae8`) plus local commit
`49c65dace2d79b41d89f536289002816d13e5274`. The local patch adds an Android
network-monitor fallback, an embedded DERP-map fallback, and region aliases.
It must be described as a local fork/patch, not as the untouched signed tag.

## Current packet paths

| Traffic | Current path | Status |
| --- | --- | --- |
| IPv4 TCP | Android TUN -> gVisor -> Tailcat `DialTCP` -> gateway | Implemented, not live release-tested |
| UDP port 53 | Android TUN -> DNS-over-TCP -> fixed public resolver through Tailcat | Partial; profile DNS is ignored |
| Other IPv4 UDP | Android TUN -> ordinary process UDP socket -> device network | **Unsafe bypass** |
| IPv6 | No Android VPN address/default route | **Unsafe bypass or unavailable under lockdown** |
| ICMP echo | Locally fabricated echo reply | Diagnostic only |
| App speed test | Ordinary app HTTP connection; app UID bypasses VPN | Device-network benchmark, not tunnel benchmark |

## Native engine API

The bundled AAR exposes:

```text
getCapabilitiesJSON() -> String
prepare(token: String)
attachTun(tunFd: Long)
getStatsJSON() -> String
stop()
```

The intended lifecycle remains:

1. `prepare` strictly validates the token and completes an authenticated
   gateway handshake before any default route exists.
2. Android creates the TUN only when a tested native engine advertises every
   required capability.
3. `attachTun` duplicates the descriptor and returns only after both packet
   directions are live.
4. Android sets `CONNECTED` only after attachment and after authoritative
   engine state confirms a live data plane.
5. `stop` cancels preparation and pumps, closes descriptors/sessions exactly
   once, and is safe to call repeatedly.

The current implementation does not yet satisfy every item above. Exact fixes,
ownership rules, and acceptance tests are in [handoff.md](handoff.md).

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
go test ./...
go vet ./...

cd ..
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

Rebuild the AAR whenever `core-engine`, `third_party/tailcat`, Go dependencies,
or native build flags change:

```bash
cd core-engine
./build-aar.sh
```

The current Gradle configuration produces one multi-ABI unsigned release APK at
`app/build/outputs/apk/release/app-release-unsigned.apk` and an unsigned AAB at
`app/build/outputs/bundle/release/app-release.aab`. Do not sign either with the
Android debug key. Production signing happens only after the live release gate
in `handoff.md` passes.

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
