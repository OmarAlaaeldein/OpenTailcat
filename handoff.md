# Tailcat native-engine integration handoff

## Executive status

The Android client adapter and Go Mobile data plane engine (`libtailcat.aar`) are implemented and integrated into the repository. The native engine wraps official upstream `tailscale/tailcat` (pinned at `third_party/tailcat` v0.4.0) and provides two-phase startup, raw TUN packet pumping, UDP datagram forwarding with checksum computation, ICMP echo handling, and real telemetry reporting.

The remaining task before production store distribution is live exit-node fleet validation and release key signing.

## Responsibility boundary

The Android app owns:

- VPN consent and foreground-service lifecycle.
- TUN construction, MTU, DNS route, and per-app exclusions.
- Strict token validation and encrypted profile storage.
- Fail-closed native-engine capability and startup checks.
- Presentation of engine-reported telemetry.

The native engine owns:

- WireGuard key generation, session state, encryption, and replay protection.
- Magicsock discovery, endpoint updates, STUN, direct UDP, and DERP fallback.
- Bidirectional TUN packet pump for IPv4, IPv6, ICMP, and UDP traffic.
- Protecting native UDP/DERP transport sockets (via Android app UID exclusion).
- Network-roaming recovery and Meow `Ping` gateway reachability handshake.
- Real byte counters, RTT, jitter, transport type, and DERP region telemetry.
- Clean cancellation and file-descriptor shutdown.

The paired gateway owns decapsulation, forwarding, DNS resolution, NAT, and any upstream WARP/Tor/direct-egress policy.

## Android/native API contract

The Go Mobile library is located at `app/libs/libtailcat.aar` and exposes `com.tailcat.vpn.engine.Engine`.

### Capability handshake

```text
getCapabilitiesJSON() -> String
```

Successful response:

```json
{
  "apiVersion": 1,
  "dataPlane": true,
  "wireGuard": true,
  "magicsock": true,
  "twoPhaseStart": true
}
```

The Android app checks this before requesting VPN consent or starting the foreground service.

### Prepare transport

```text
prepare(token: String)
```

Runs before Android creates a TUN route:
1. Validates token syntax and timestamps.
2. Initializes the upstream `tailcat.Client`.
3. Completes an authenticated Meow `Ping` reachability handshake with the exit node.
4. Returns synchronously with an error if unreachable.

### Attach TUN

```text
attachTun(tunFd: Long)
```

Takes the duplicated Android TUN descriptor, spawns packet pumps for IPv4 and IPv6 traffic, ICMP echo responder, and UDP session handlers, and returns once live.

### Telemetry

```text
getStatsJSON() -> String
```

```json
{
  "transport": "DIRECT_P2P",
  "derpRegionId": 302,
  "derpRegionName": "San Francisco",
  "rttMs": 24,
  "jitterMs": 4,
  "txBytes": 1024,
  "rxBytes": 2048,
  "txRateKbps": 12,
  "rxRateKbps": 48
}
```

Reports measured counters and latency; never synthesizes values.

### Stop

```text
stop()
```

Stops packet pumps, closes native sockets and duplicated descriptors, and releases secrets.

## Token schema

```text
tc + Base64URL(CBOR map)
```

| Key | Required | Type | Rule |
| --- | --- | --- | --- |
| `p` | yes | bytes (32) | Server node public key |
| `k` | no | bytes (32) | Server disco public key |
| `i` | no | integer | Positive DERP region ID |
| `r` | no | integer or array | DERP region ID or embedded region metadata |
| `exp` | no | integer | Positive Unix epoch seconds; expires at or after |
| `iat` | no | integer | Positive Unix epoch seconds; cannot exceed `exp` |

## Production release gate

- [x] Working native AAR is included for every supported ABI (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- [x] Capability handshake advertises the implemented two-phase API (`apiVersion: 1`).
- [x] No generated or placeholder telemetry exists; all metrics are measured.
- [x] Cryptographic Meow reachability handshake precedes route creation.
- [x] TUN packet pump implemented for IPv4, IPv6, ICMP echo, and UDP forwarding.
- [x] Android unit tests and Go engine tests pass (`go test ./...` and `testDebugUnitTest`).
- [x] Static analysis passes (`lintDebug` 0 errors).
- [x] Debug and release APKs build successfully (`assembleDebug`, `assembleRelease`).
- [ ] Live test against a running exit node for direct P2P and DERP fallback.
- [ ] Release signed with private production key before distribution.
