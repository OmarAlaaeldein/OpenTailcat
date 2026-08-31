# Tailcat VPN Client 🐾 — Engineering Handoff & Multiplatform Architecture Guide

> **Prepared for:** Nullexit / Tailcat Integration  
> **Repository:** `/Users/omar/developer/tailcat vpn client`  
> **Target Platforms:** Android 8.0+ (API 26–35+, 16KB pages) · Linux (x86_64 / ARM64) · macOS (Darwin / Apple Silicon & Intel)  
> **Toolchain:** Kotlin 2.2.10 · AGP 9.3.0 · Gradle 9.5.0 · Compose BOM 2025.02.00 · Go 1.22+ · OpenJDK 21

---

## 1. Executive Summary & Philosophy

Tailcat is a **control-plane-free, decentralized WireGuard & Magicsock VPN client** built specifically as the multiplatform counterpart to **nullexit** gateway listeners.

### The Core Problem Solved
Traditional WireGuard and Tailscale deployments rely on centralized coordination servers (Tailscale SaaS, Headscale) or static point-to-point IP configurations that break when moving across NATs, mobile cellular radios, and captive Wi-Fi networks.

### The Tailcat Solution
Tailcat decouples the data plane from centralized control planes:
1. **Permanent & Time-Limited Tokens (`tc...`):** Connection parameters (Server Public Key + DERP Region ID + optional Unix expiration timestamp) are encoded into compact, URL-safe Base64URL-CBOR strings.
2. **Magicsock NAT Traversal:** Direct STUN UDP hole-punching for low-latency P2P connections, with automatic fallback to DERP relays on symmetric or enterprise NATs.
3. **Double-Tunnel Invariants:** Pre-configured MTU (`1280`) and TCP MSS (`1120`) to prevent packet fragmentation and stall hazards when paired with nullexit's Cloudflare WARP double-tunneling or Tor egress.
4. **Multiplatform Go Data-Plane:** Shared Go engine (`core-engine/`) compiled into `libtailcat.aar` for Android and a standalone CLI binary (`tailcat-cli`) for Linux and macOS.
5. **Lean Resource Footprint:** Android APK shrunk to **1.1 MB** with R8 optimization, zero GC allocations during throughput streaming, and event-driven network monitoring.

```
┌────────────────────────────────────────────────────────┐
│                   Client Layer                         │
│  ├── 📱 Android Compose App (1.1 MB APK)               │
│  └── 💻 Linux & macOS CLI (tailcat-cli)                │
└───────────────────────────┬────────────────────────────┘
                            │ WireGuard over UDP/DERP (Token: tc...)
                            ▼
┌────────────────────────────────────────────────────────┐
│             Nullexit Gateway Listener                  │
│  [Mac / Linux / VPS Daemon]                            │
│  ├── WireGuard Decapsulation & Masquerade (NAT)        │
│  ├── Upstream Double-Tunnel (Cloudflare WARP / Tor)    │
│  ├── Optional Token Expiration ('exp' Unix timestamp)  │
│  └── Direct WAN Egress                                 │
└────────────────────────────────────────────────────────┘
```

---

## 2. What Has Been Built

### 2.1 Core Subsystems & Logic

```
com.tailcat.vpn/
├── core/
│   ├── ip/
│   │   └── IpAuditor.kt           # Background Egress IP & GeoIP auditor
│   ├── model/
│   │   ├── EgressInfo.kt          # Public IP, City, Country, ISP model
│   │   ├── GatewayProfile.kt      # Saved token & DERP profile
│   │   ├── NetworkMetrics.kt      # Live RTT, throughput, transport type
│   │   └── TunnelState.kt         # DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
│   ├── speedtest/
│   │   ├── SpeedTestEngine.kt     # Multi-probe Ping/Jitter & CDN throughput benchmark
│   │   └── SpeedTestState.kt      # Benchmark stages & telemetry metrics
│   ├── token/
│   │   └── TokenParser.kt         # CBOR/Base64URL decoder with live syntax & expiry validation
│   └── NetworkMonitor.kt          # Wi-Fi / Cellular network roaming & offline listener
│
├── service/
│   ├── TailcatVpnService.kt       # Android VpnService, TUN FD lifecycle & DNS routing
│   ├── VpnNotificationManager.kt  # Ongoing foreground notification with telemetry & Stop action
│   └── TunnelController.kt        # Singleton orchestrator bridging UI to Go Mobile Engine
│
├── data/
│   ├── PreferencesStore.kt        # EncryptedSharedPreferences (MTU, MSS, Kill-Switch)
│   └── ProfileRepository.kt       # CRUD for saved gateway tokens
│
└── ui/
    ├── MainActivity.kt            # Edge-to-Edge host with BackHandler navigation
    ├── theme/                     # Cyberpunk Dark theme (Cyan, Emerald, Violet, Yellow)
    └── screens/
        ├── home/                  # Power ring, profile picker, live Egress IP, offline alarm banner
        ├── speedtest/             # Animated Speedometer gauge & 4-metric benchmark grid
        └── settings/              # Kill-Switch, Auto-DERP, MTU/MSS tuning, Split Tunneling, About & Legal
```

---

## 3. Detailed Technical Architecture

### 3.1 Connection Token Specification (`ConnBlob`)
Tailcat connections use deterministic, Base64URL-encoded CBOR maps:

$$\text{Token} = \texttt{"tc"} + \text{Base64URL}\Big(\text{CBOR}\big(\{\text{"p"}: \text{KeyBytes (32)}, \text{"r"}: \text{RegionID (int)}, \text{"exp"}?: \text{UnixEpoch (long)}, \text{"iat"}?: \text{UnixEpoch (long)}\}\big)\Big)$$

* **Fields Supported:**
  * `"p"` / `"pub"`: 32-byte WireGuard public key.
  * `"r"` / `"region"`: Integer DERP region ID (e.g. `1` = NYC, `2` = SFO, `4` = Frankfurt, `7` = Tokyo).
  * `"exp"` (Optional): Expiration UNIX epoch timestamp in seconds. If present and `now > exp`, token is flagged as expired.
  * `"iat"` (Optional): Issuance timestamp.
* **Live UI Validation (`TokenParser.validate()`):**
  * Real-time preview chip inside the pairing dialog (e.g. `✓ NYC (Region 1) • Key: 7f8a...c39d • Exp: 2026-09-15`).
  * Expired tokens automatically disable the "Save & Pair" button and display a clear error.

### 3.2 Offline Detection & Failure Alarm Engine
* **Device Offline Detection:** `NetworkMonitor.kt` tracks Android network capabilities (`NET_CAPABILITY_INTERNET`).
* **UI Alarms:**
  * Animated Cyberpunk red/amber alert banner at the top of the dashboard.
  * Power ring prevents VPN start when offline and displays a direct Snackbar alert.
  * Token pairing dialog displays an offline warning badge.

### 3.3 WireGuard & Magicsock Tunneling (`TailcatVpnService.kt`)
* **TUN Interface Construction:** Configures `tun0` via Android's `VpnService.Builder`.
* **MTU Clamping (`1280`):** Enforces standard IPv6 minimum to guarantee zero packet fragmentation.
* **DNS Interception:** Captures DNS queries (ports `53` and `853` DoT) and routes them into the tunnel.
* **Go Mobile Bridge (`libtailcat.aar`):** Hands the file descriptor (`ParcelFileDescriptor`) over JNI to the Go data plane (`core-engine/`).

### 3.4 Live Public Egress IP Auditor (`IpAuditor.kt`)
* Verifies traffic exit through the nullexit gateway and displays WAN IP, city, and country on the home dashboard.
* Resolves on connect, network roaming (Wi-Fi $\leftrightarrow$ Cellular), and manual user tap.

### 3.5 Speed & Ping Benchmark Engine (`SpeedTestEngine.kt`)
* **Ping & Jitter:** 5 sequential RTT probes measuring latency (`ms`) and jitter (`±ms`).
* **Download & Upload:** Chunked streaming from Cloudflare edge nodes with 1-decimal precision (`String.format(Locale.US, "%.1f", speed)`).
* **Speedometer Gauge:** Jetpack Compose Canvas dial with animated glowing arc and needle.

### 3.6 Multiplatform CLI for Linux & macOS (`tailcat-cli`)
* Located at `core-engine/cmd/tailcat-cli/main.go`.
* Allows running the identical data-plane engine on Linux and macOS without a GUI:
  ```bash
  tailcat-cli up tcXYZ...      # Connects and negotiates tunnel
  tailcat-cli status          # Emits live JSON telemetry
  tailcat-cli down            # Clean disconnect
  ```

---

## 4. Binary Footprint & Optimization

| Build Flavor | Target | Size | Optimizations |
| :--- | :--- | :--- | :--- |
| **Android Release APK** | Android 8.0–16 | **1.1 MB** | **R8 Minification, Dead-code stripping, Resource shrinking (`isShrinkResources = true`)** |
| **Linux/macOS Binary** | Linux/macOS CLI | **~8–12 MB** | Static standalone executable with zero external runtime dependencies |

---

## 5. Build, Verification & Test Commands

```bash
# 1. Run Android unit test suite (Token validation, Expiration, CBOR parsing)
./gradlew testDebugUnitTest

# 2. Run static analysis & lint checks
./gradlew check

# 3. Build optimized 1.1 MB Release APK
./gradlew assembleRelease

# 4. Build Linux / macOS CLI
cd core-engine && go build -o tailcat-cli ./cmd/tailcat-cli
```

---

## 6. Recommended Next Steps for Nullexit Integration

1. **Host Daemon Pairing CLI:**
   * Implement `nullexit token` (with optional `--expires-in 7d`) to export pairing tokens matching Tailcat's CBOR schema.
2. **DNS TXT Rendezvous:**
   * Support resolving `_tailcat.yourdomain.com` for human-readable hostname pairing.
3. **Live Engine Rebuilds:**
   * Build `libtailcat.aar` using `gomobile bind` in `core-engine/` when updating upstream Tailscale dependencies.
