# Tailcat VPN Client

Tailcat is an Android client shell for a control-plane-free WireGuard and Magicsock VPN. Gateway connection parameters are carried in compact, URL-safe `tc...` tokens.

> [!IMPORTANT]
> This repository does **not** currently contain a working WireGuard/Magicsock packet engine or `libtailcat.aar`. The Android app fails closed: it will not create a VPN route or report a connection until a compatible native engine advertises a working data plane. The Go package under `core-engine/` is an integration scaffold, not a VPN implementation.

This is the product's central missing capability, not an optional feature: entering a token and establishing the corresponding encrypted gateway tunnel is Tailcat's primary purpose. The current APK is therefore a safe integration/debug build, **not a functional VPN client**. Its UI, token storage, validation, diagnostics, and Android service boundary are implemented, but it cannot connect until the native data plane described in [`handoff.md`](handoff.md) is implemented, packaged, and tested with the matching gateway listener.

## Current status

Implemented and usable as application scaffolding:

- Strict CBOR/Base64URL token parsing with exact 32-byte public-key validation and expiry enforcement.
- Encrypted local profile storage backed by Android Keystore.
- Offline detection using validated Android network capabilities.
- Android VPN consent and service scaffolding with fail-closed engine startup.
- Per-app exclusions applied when a real tunnel starts.
- Real, user-initiated Cloudflare latency/download/upload measurements; failures are never replaced with synthetic results.
- Direct device public-IP lookup using Cloudflare with an ipify fallback.
- Compose UI for profiles, connection state, diagnostics, and settings.

Not implemented in this repository:

- WireGuard encryption or packet pumping.
- Magicsock, STUN hole punching, or DERP relay transport.
- Gateway handshake or egress verification through the tunnel.
- TCP MSS clamping.
- An app-managed kill switch. Use Android's Always-on VPN and **Block connections without VPN** controls after a real engine is integrated.
- QR token scanning.
- A functional Linux/macOS VPN CLI.

## Fail-closed engine contract

Place a compatible Go Mobile AAR in `app/libs/`. The generated Java API must be available as `engine.Engine` or `com.tailcat.vpn.engine.Engine` and expose these static methods:

```text
getCapabilitiesJSON() -> String
prepare(token)
attachTun(tunFd)
getStatsJSON() -> String
stop()
```

Before Android creates a full-device route, `getCapabilitiesJSON()` must return at least:

```json
{
  "apiVersion": 1,
  "dataPlane": true,
  "wireGuard": true,
  "magicsock": true,
  "twoPhaseStart": true
}
```

`prepare` completes the authenticated gateway/transport handshake before Android creates a route. After that succeeds, Android establishes the TUN and calls `attachTun`, which must start packet pumps immediately. `getStatsJSON` must report real transport and byte counters. The service closes the TUN immediately if attachment fails and disconnects after repeated telemetry failures.

## Token format

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte WireGuard server public key,
  "r": positive DERP region ID,
  "exp"?: Unix epoch seconds,
  "iat"?: Unix epoch seconds
}))
```

Aliases `pub`, `nodekey`, and `region` are accepted for compatibility. Duplicate aliases, invalid timestamps, missing regions, malformed CBOR, trailing CBOR objects, and non-32-byte keys are rejected. Expired tokens cannot be saved or started.

## Build and verification

Requirements: OpenJDK 21 and Android SDK 35.

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease

cd core-engine
go test ./...
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`.

Release output is intentionally unsigned. Configure a private release signing key outside the repository before distribution; production releases must never use the debug key.

## Repository layout

```text
app/                         Android application
  src/main/java/.../core/    token, network, IP, and speed-test logic
  src/main/java/.../data/    encrypted preferences and profiles
  src/main/java/.../service/ fail-closed VPN and native-engine boundary
  src/main/java/.../ui/      Jetpack Compose UI
core-engine/                 Go Mobile integration scaffold
handoff.md                   native-engine integration requirements
PRIVACY_POLICY.md            local data and external request disclosure
SECURITY.md                  current security posture
THIRD_PARTY_NOTICES.md       dependency notices
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
