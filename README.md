# OpenTailcat

OpenTailcat is an independent, control-plane-free Android VPN client powered by the open-source WireGuard and Magicsock protocols from `tailscale/tailcat`. Gateway connection parameters are carried in compact, URL-safe `tc...` tokens without any centralized coordination server.

> **Note**: OpenTailcat is an independent open-source community client and is not affiliated with, sponsored by, or endorsed by Tailscale Inc.

## How to Connect (Quickstart)

OpenTailcat requires no accounts, email addresses, or central servers. Everything is bootstrapped using a self-contained connection token:

1. **Start an Exit Gateway** on your server, VM, or local computer:
   - Using official upstream Tailcat:
     ```bash
     tailcat serve exit-node
     ```
   - Or using a containerized gateway (e.g. `nullexit`).
2. **Copy the printed token** (e.g. `tco2FwWCBYO5fzDYwh...`).
3. **Open OpenTailcat on Android**, tap the Power ring or "+", paste your token, and tap Connect.

## Architecture & status

The repository contains a fully integrated Android client and Go Mobile native VPN engine:

- **Android Client**: Jetpack Compose UI, encrypted Keystore-backed profile storage, validated underlying network monitoring, Android `VpnService` lifecycle management, and per-app exclusions.
- **Native Engine Adapter (`libtailcat.aar`)**: Powered by official `tailscale/tailcat` (pinned at `third_party/tailcat` v0.4.0) with Go Mobile bindings. Provides two-phase startup (`prepare` Meow ping handshake -> `attachTun` raw packet pump), WireGuard encryption, Magicsock direct UDP with DERP relay fallback, ICMP echo handling, and UDP/DNS datagram forwarding.
- **Fail-Closed Guarantees**: Full-device routes (`0.0.0.0/0`) are only installed after the native engine completes the authenticated reachability handshake. The app UID is excluded from VPN routing to avoid recursive loops.

## Implemented features

- Reconciled CBOR/Base64URL token parsing supporting:
  - Official short tokens (`p`: 32-byte node key, `k`: 32-byte disco key, `i`: integer DERP region ID).
  - Resolved tokens with embedded DERP region metadata (`r`: array of regions).
  - Legacy tokens (`p`: 32-byte key, `r`: integer DERP region, optional `exp` and `iat`).
- Encrypted local profile storage backed by Android Keystore.
- Two-phase native engine lifecycle (`prepare`, `attachTun`, `getStatsJSON`, `stop`).
- Bidirectional TUN packet pump for IPv4 and IPv6 traffic.
- Production gVisor netstack TCP proxy engine with SACK, window scaling, and MTU segmentation over Tailcat `DialTCP`.
- ICMP echo responder, UDP forwarding with checksum computation, and DNS-over-TCP proxy.
- Live measured telemetry (transport mode, DERP region, RTT latency, TX/RX throughput rates, and TLS-audited exit IP).
- User-initiated HTTP speed tests (no synthetic metrics).

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
gomobile bind -ldflags="-s -w" -v -target=android/arm64,android/amd64 -androidapi=26 -javapkg=com.tailcat.vpn -o ../app/libs/libtailcat.aar .
go test -v ./...
```

### Build and test the Android app

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

### Sign Release APKs

```bash
export PATH="$HOME/Library/Android/sdk/build-tools/35.0.0:$PATH"
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out OpenTailcat-v1.0.0-arm64-v8a-signed.apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out OpenTailcat-v1.0.0-universal-signed.apk app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

Signed Artifacts:
- Arm64 Release APK (~20 MB): `OpenTailcat-v1.0.0-arm64-v8a-signed.apk`
- Universal Release APK (~41 MB): `OpenTailcat-v1.0.0-universal-signed.apk`

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

## Author & Maintainer

Developed and maintained by **Omar Alaaeldein** ([@OmarAlaaeldein](https://github.com/OmarAlaaeldein)).

## License

Copyright (c) 2026 Omar Alaaeldein.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. See [LICENSE](LICENSE).

Pinned upstream Tailcat components in `third_party/tailcat` are licensed by Tailscale Inc. under the BSD-3-Clause License.
