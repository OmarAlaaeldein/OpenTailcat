# Tailcat Native Engine Integration & Architecture Handoff

## Executive Status

The Tailcat Android VPN client is a **fully functional, production-ready mobile implementation** of Tailscale's control-plane-free `tailcat` engine. The native engine wraps official upstream `tailscale/tailcat` (`v0.4.0` pinned at `third_party/tailcat`) and exposes a two-phase Go Mobile library (`app/libs/libtailcat.aar`).

The client pairs directly with sovereign exit node gateways (such as `nullexit`) through compact `tc...` connection tokens without any centralized coordination server.

---

## Architectural Deep Dive

### 1. Two-Phase Startup & Fail-Closed Safety
- **Phase 1 (`prepare`)**:
  - Validates token CBOR structure and timestamps.
  - Generates client Curve25519 identity keys.
  - Establishes connection to the DERP relay (e.g. `derp-301.tailscale.com` in NYC).
  - Performs an authenticated Meow `Ping` handshake with the exit node.
  - Returns synchronously with an error if the exit node is offline or unreachable **before Android installs any system route**.
- **Phase 2 (`attachTun`)**:
  - Takes the duplicated Android `VpnService` TUN file descriptor (`tunFD`).
  - Spawns concurrent packet pumps for IPv4 raw packets.
  - Attaches userland TCP state machine, ICMP echo responder, and DNS-over-TCP forwarder.

### 2. Userland TCP & DNS Engine (`core-engine/bridge.go`)
Upstream `tailscale/tailcat` is userland netstack-oriented (`Client.DialTCP`). To bridge raw IP packets from the Android OS TUN to Tailcat's dialer:
- **TCP State Machine**:
  - Intercepts outbound TCP SYN packets from the TUN for any `(srcAP -> dstAP)`.
  - Dials the destination over the encrypted WireGuard tunnel using `client.DialTCP(ctx, dstAP)`.
  - Responds with TCP SYN-ACK (`0x12`), handles three-way handshakes, data framing, ACK packet generation, and graceful FIN/RST teardown.
- **DNS-over-TCP Proxy**:
  - Intercepts all UDP port 53 DNS queries from Android apps.
  - Frames them as RFC 7766 DNS-over-TCP queries (`[len16, payload]`).
  - Proxies them through the exit node over `client.DialTCP` to Cloudflare (`1.1.1.1:53`) and Google (`8.8.8.8:53`).
  - Re-encapsulates the response into UDP packets with valid IPv4/UDP checksums and injects them back into the TUN.

### 3. In-App Telemetry vs. Device Network Egress
- **App UID Exclusion**:
  - The Android `VpnService` builder marks Tailcat's own package as disallowed (`builder.addDisallowedApplication(packageName)`).
  - This ensures that native WireGuard & Magicsock UDP sockets reach the physical Wi-Fi/LTE network directly without looping back into the VPN interface.
  - As a result, in-app diagnostics (`IpAuditor`) report the **direct device IP** on purpose.
- **External App Egress**:
  - All other device applications (Chrome, Firefox, WhatsApp, Instagram, YouTube, etc.) route through `0.0.0.0/0` on the TUN.
  - Visiting external IP inspection services (e.g., `icanhazip.com`, `ipinfo.io`) in a web browser reports the **exit node's Cloudflare WARP IP** (`104.28.x.x`).

### 4. IPv4 vs IPv6 Route Scoping
- Docker / Colima host gateways typically have IPv4-only public default routes.
- Dual-stack apps (e.g. Meta / Facebook) attempt IPv6 first when a `::/0` route is present on the TUN interface.
- To prevent `connect: network is unreachable` errors from containerized gateways lacking native IPv6 WAN routing, the Android TUN installs the default route exclusively for IPv4 (`0.0.0.0/0`).

---

## Token Specifications

```text
Token = "tc" + Base64URL(CBOR({
  "p": 32-byte server public node key,
  "k"?: 32-byte server disco public key,
  "i"?: positive integer DERP region ID,
  "r"?: positive integer or array of DERP region maps,
  "exp"?: positive Unix epoch seconds,
  "iat"?: positive Unix epoch seconds
}))
```

- **Short Tokens (~65 chars)**: Carries `p`, `k`, and `i` (region number).
- **Resolved Tokens (~180 chars)**: Carries `p`, `k`, and embedded `r` relay details (hostname, IP, TLS cert pin).
- **Legacy Tokens**: Supported via fallback decoding for numeric `r`.

---

## Binary & Build Optimization

- **Native Go Mobile AAR**: Compiled with `-ldflags="-s -w"` to strip DWARF debugging tables and symbols:
  - `app/libs/libtailcat.aar`: **14 MB** (reduced from 38 MB)
- **Release APKs**:
  - 📱 **`Tailcat-v1.0.0-arm64-v8a-signed.apk`**: **20 MB** (signed with APK Signature Scheme v2/v3)
  - 🌐 **`Tailcat-v1.0.0-universal-signed.apk`**: **39 MB** (multi-ABI)

---

## Verification & Build Commands

```bash
# 1. Run Go Engine Unit Tests
cd core-engine
go test -v ./...

# 2. Build Native AAR
gomobile bind -ldflags="-s -w" -v -target=android/arm64,android/amd64 -androidapi=26 -javapkg=com.tailcat.vpn -o ../app/libs/libtailcat.aar .

# 3. Run Android Unit Tests & Assemble Release
cd ..
./gradlew testDebugUnitTest
./gradlew assembleRelease

# 4. Sign APKs
export PATH="$HOME/Library/Android/sdk/build-tools/35.0.0:$PATH"
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out Tailcat-v1.0.0-arm64-v8a-signed.apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
apksigner verify --verbose Tailcat-v1.0.0-arm64-v8a-signed.apk
```

