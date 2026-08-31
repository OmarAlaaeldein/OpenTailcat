# Tailcat native-engine integration handoff

## Executive status

The Android application layer is present, but the data plane is not. `core-engine/main.go` is deliberately fail-closed and `libtailcat.aar` is absent. Earlier builds established `0.0.0.0/0` and `::/0` routes without reading the TUN, then displayed generated telemetry. That behavior has been removed.

The missing data plane is the product's essential function. Tailcat's intended primary flow—enter a `tc...` token, authenticate the named gateway, and carry device traffic through an encrypted WireGuard/Magicsock tunnel—does not work in the current source tree. The remaining native work is an implementation project, not an AAR packaging step: binding the existing Go scaffold without adding the components below would only produce a nonfunctional library.

Do not distribute Tailcat as a working VPN until every item in the release gate at the end of this document passes on physical Android devices.

## Responsibility boundary

The Android app owns:

- VPN consent and foreground-service lifecycle.
- TUN construction, MTU, DNS route, and per-app exclusions.
- Strict token validation and encrypted profile storage.
- Fail-closed native-engine capability and startup checks.
- Presentation of engine-reported telemetry.

The native engine must own:

- WireGuard key generation, session state, encryption, and replay protection.
- Magicsock discovery, endpoint updates, STUN, direct UDP, and DERP fallback.
- Reading every packet from the Android TUN and writing decrypted packets back.
- Protecting or otherwise bypassing transport sockets so they do not loop into the VPN.
- Network-roaming recovery and a verifiable gateway handshake.
- Real byte counters, RTT, jitter, transport type, and DERP region telemetry.
- Clean cancellation and file-descriptor shutdown.

The paired gateway owns decapsulation, forwarding, DNS resolution, NAT, and any upstream WARP/Tor/direct-egress policy. Those upstream choices must not leak into the APK's protocol contract.

## Android/native API contract

Build the Go implementation with Go Mobile and place the result at `app/libs/libtailcat.aar`. The generated class must be resolvable as `engine.Engine` or `com.tailcat.vpn.engine.Engine`.

### Capability handshake

```text
getCapabilitiesJSON() -> String
```

Required successful response:

```json
{
  "apiVersion": 1,
  "dataPlane": true,
  "wireGuard": true,
  "magicsock": true,
  "twoPhaseStart": true
}
```

The Android app checks this before requesting VPN consent or starting the foreground service. Missing, malformed, false, or incompatible capabilities keep Connect disabled.

### Prepare transport

```text
prepare(token)
```

This call runs before Android creates a TUN route. It must validate the token again and return successfully only when:

1. Transport sockets bypass the TUN.
2. WireGuard state is initialized.
3. The gateway identity matches the token public key.
4. Direct or DERP transport is usable.

Any error must be returned synchronously with a user-safe message. On prepare failure, Android never establishes the TUN.

### Attach TUN

```text
attachTun(tunFd)
```

After preparation succeeds, Android installs the TUN routes and calls `attachTun`. This call must start read/write packet pumps immediately and return only when they are live. Any error closes the TUN immediately.

### Telemetry

```text
getStatsJSON() -> String
```

```json
{
  "transport": "DIRECT_P2P",
  "derpRegionId": null,
  "derpRegionName": null,
  "rttMs": 24,
  "jitterMs": 4,
  "txBytes": 1024,
  "rxBytes": 2048,
  "txRateKbps": 12,
  "rxRateKbps": 48
}
```

Never estimate or randomize these values. Three consecutive telemetry failures cause Android to tear down the tunnel.

### Stop

```text
stop()
```

Stop packet pumps, close native sockets, release secrets, and return. The Android service then closes its `ParcelFileDescriptor`.

## Token schema

```text
tc + Base64URL(CBOR map)
```

| Key | Required | Type | Rule |
| --- | --- | --- | --- |
| `p` | yes | bytes or hex text | exactly 32 bytes |
| `r` | yes | integer | positive, within signed 32-bit range |
| `exp` | no | integer | positive Unix seconds; token invalid at or after this time |
| `iat` | no | integer | positive Unix seconds; cannot be later than `exp` |

The Android parser accepts documented aliases but rejects duplicate aliases and multiple/trailing CBOR objects. The engine and gateway token generator must match these rules exactly. Add shared golden vectors before integration.

## Networking notes

- Current TUN addresses are `100.64.0.2/32` and `fd7a:115c:a1e0::2/128`.
- Current default MTU is 1280 and the valid UI range is 1280–1500.
- Android adds IPv4 and IPv6 default routes plus the profile DNS server.
- The application UID is excluded from the TUN so native sockets do not loop. Consequently, the in-app public-IP card is explicitly labeled **Device IP** and is not egress verification.
- If true in-tunnel egress auditing is required, move the audit into the native engine or expose a protected/bound HTTP path. Do not relabel the current lookup.
- TCP MSS clamping is not implemented. It must happen in a real packet path and needs IPv4/IPv6 TCP checksum tests.

## Android platform behavior

The service declares the `systemExempted` foreground-service type used by active VPN apps on Android 14+. Always-on and “Block connections without VPN” are Android system settings; the app no longer presents a switch that pretends to configure them.

Release APKs are unsigned by default. A production signing configuration must be supplied outside source control.

## Required tests

Add at minimum:

- Shared gateway/client token golden vectors, including expiry and malformed inputs.
- TUN packet round trips for IPv4, IPv6, TCP, UDP, ICMP, DNS, MTU edges, and malformed packets.
- Gateway identity mismatch and expired-token rejection in the native engine.
- Direct-to-DERP and DERP-to-direct transitions without traffic leaks.
- Wi-Fi/cellular handoff, captive portal, airplane mode, process death, service revoke, and device sleep.
- Socket-loop prevention and confirmation that all non-excluded app traffic enters the tunnel.
- DNS and IPv6 leak tests.
- Always-on/lockdown behavior on Android 8, 13, 15, and 16.
- 16 KB page-size physical or official emulator validation for every bundled native ABI.

## Production release gate

- [ ] Working native AAR is included for every supported ABI.
- [ ] Capability handshake advertises the implemented two-phase API.
- [ ] No generated or placeholder telemetry exists.
- [ ] A cryptographic gateway handshake precedes CONNECTED state.
- [ ] Direct and DERP paths pass packet and roaming tests.
- [ ] DNS, IPv6, MTU, and leak tests pass.
- [ ] Privacy policy lists every external endpoint and relay operator actually used.
- [ ] Third-party notices are generated from resolved Android and Go dependencies.
- [ ] Release is signed with a protected production key, not the debug key.
- [ ] `./gradlew testDebugUnitTest lintDebug assembleRelease` and `go test ./...` pass.
