# Tailcat VPN Client

Tailcat is an Android client for a control-plane-free WireGuard and Magicsock VPN. Gateway connection parameters are carried in compact, URL-safe `tc...` tokens.

## Architecture & status

The repository contains a fully integrated Android client and Go Mobile native VPN engine:

- **Android Client**: Jetpack Compose UI, encrypted Keystore-backed profile storage, validated underlying network monitoring, Android `VpnService` lifecycle management, and per-app exclusions.
- **Native Engine Adapter (`libtailcat.aar`)**: Powered by official `tailscale/tailcat` (pinned at `third_party/tailcat` v0.4.0) with Go Mobile bindings. Provides two-phase startup (`prepare` Meow ping handshake -> `attachTun` raw packet pump), WireGuard encryption, Magicsock direct UDP with DERP relay fallback, ICMP echo handling, and UDP/DNS datagram forwarding.
- **Fail-Closed Guarantees**: Full-device routes (`0.0.0.0/0`, `::/0`) are only installed after the native engine completes the authenticated reachability handshake. The app UID is excluded from VPN routing to avoid recursive loops.

## Implemented features

- Reconciled CBOR/Base64URL token parsing supporting:
  - Official short tokens (`p`: 32-byte node key, `k`: 32-byte disco key, `i`: integer DERP region ID).
  - Resolved tokens with embedded DERP region metadata (`r`: array of regions).
  - Legacy tokens (`p`: 32-byte key, `r`: integer DERP region, optional `exp` and `iat`).
- Encrypted local profile storage backed by Android Keystore.
- Two-phase native engine lifecycle (`prepare`, `attachTun`, `getStatsJSON`, `stop`).
- Bidirectional TUN packet pump for IPv4 and IPv6 traffic.
- ICMP echo responder and UDP forwarding with full IP/UDP checksum computation.
- Live measured telemetry (transport mode, DERP region, RTT latency, jitter, TX/RX throughput rates).
- User-initiated HTTP speed tests (no synthetic metrics).
- Direct device public-IP lookup using Cloudflare with ipify fallback.

## Native engine API contract

The Go Mobile library (`app/libs/libtailcat.aar`) exposes `com.tailcat.vpn.engine.Engine`:

```text
getCapabilitiesJSON() -> String
prepare(token: String)
attachTun(tunFd: Long)
getStatsJSON() -> String
stop()
```

### Capabilities contract

`getCapabilitiesJSON()` returns:

```json
{
  "apiVersion": 1,
  "dataPlane": true,
  "wireGuard": true,
  "magicsock": true,
  "twoPhaseStart": true
}
```

### Lifecycle

1. `prepare(token)` validates token syntax and completes an authenticated `Ping` reachability handshake to the exit node before Android creates any route.
2. `attachTun(tunFd)` takes the duplicated Android TUN descriptor and starts packet pumps.
3. `getStatsJSON()` returns live measured metrics.
4. `stop()` terminates packet pumps, closes descriptors, and releases secrets.

## Token format

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte WireGuard server public key,
  "k"?: 32-byte disco public key,
  "i"?: positive DERP region ID,
  "r"?: positive DERP region ID or array of region metadata,
  "exp"?: positive Unix epoch seconds,
  "iat"?: positive Unix epoch seconds
}))
```

Tokens are validated strictly: malformed CBOR, invalid keys, mismatching timestamps, or expired tokens are rejected.

## Build and verification

Requirements: OpenJDK 21, Android SDK 35, Android NDK (r26+ / r29), and Go 1.24+.

### Build the native engine AAR

```bash
cd core-engine
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_NDK_HOME=/opt/homebrew/share/android-ndk
gomobile bind -v -target=android -androidapi=26 -javapkg=com.tailcat.vpn -o ../app/libs/libtailcat.aar .
go test -v ./...
```

### Build and test the Android app

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
```

Artifacts generated:
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Repository layout

```text
app/                         Android application
  libs/                      libtailcat.aar Go Mobile library
  src/main/java/.../core/    token, network, IP, and speed-test logic
  src/main/java/.../data/    encrypted preferences and profiles
  src/main/java/.../service/ VPN service and native-engine bridge
  src/main/java/.../ui/      Jetpack Compose UI
core-engine/                 Go Mobile native adapter and packet bridge
third_party/tailcat/         Official upstream tailscale/tailcat submodule (v0.4.0)
handoff.md                   Native engine integration details & test gates
PRIVACY_POLICY.md            Privacy policy and network disclosures
SECURITY.md                  Security policy and controls
THIRD_PARTY_NOTICES.md       Third-party open source notices
```

## License

Apache License 2.0. See [LICENSE](LICENSE). Pinned upstream Tailcat components in `third_party/tailcat` are licensed under the BSD-3-Clause License.
