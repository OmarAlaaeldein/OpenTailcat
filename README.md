# Tailcat VPN Client 🐾⚡

> **Control-Plane-Free WireGuard & Magicsock VPN Client for Android, Linux & macOS**

Tailcat is an ultra-lean, privacy-first VPN client engineered for decentralized, control-plane-free WireGuard mesh networking. It connects directly to gateway listeners via compact, permanent or time-limited connection tokens (`tc...`), eliminating centralized coordination servers, login portals, and external account tracking.

---

## 🌟 Multiplatform Support

| Platform | Interface | Highlights |
| :--- | :--- | :--- |
| **📱 Android** | Jetpack Compose Cyberpunk UI | 1.1 MB APK · Live Egress IP Auditor · Speedometer Benchmark · Split-Tunneling · Offline Alarms |
| **🐧 Linux** | Lightweight CLI (`tailcat-cli`) | WireGuard-Go + Magicsock daemon · Headless server & desktop support · Zero daemon dependencies |
| **🍎 macOS** | CLI Daemon (`tailcat-cli`) | Native Darwin TUN routing · Apple Silicon & Intel support · One-command token connect |

---

## ✨ Key Features

* **⚡ Control-Plane-Free P2P Tunneling:** Uses WireGuard + Magicsock + STUN for direct UDP hole-punching and automatic DERP relay fallback.
* **🔑 Connection Tokens (`tc...` with Expiration):** Base64URL/CBOR-encoded server public keys, DERP regions, and optional expiration (`exp`) timestamps. Real-time syntax and expiry validation preview.
* **📡 Real-Time Offline Detection & Alarms:** Animated Cyberpunk offline banner and pairing warnings when the device has no active internet connection.
* **🌍 Live Public Egress IP Auditor:** Real-time WAN IP, country, and city geolocation displayed right on the home dashboard with a one-tap refresh.
* **🏎️ Network Benchmark & Speedometer:** Multi-probe RTT Latency, Jitter variance, Download throughput, and Upload throughput with an animated Cyberpunk arc gauge.
* **🛡️ Kill-Switch & Auto-DERP:** Blocks unencrypted network traffic on disconnect, and forces reliable DERP encapsulation when roaming on isolated Enterprise Wi-Fi.
* **🔀 Split Tunneling:** Per-app routing allowing selected applications to bypass the VPN tunnel.
* **🪶 Ultra-Compact 1.1 MB Android Release:** Fully minified with R8, tree-shaking, resource shrinking, and zero GC allocation overhead during benchmarking.
* **🔒 Strict Zero-Log Privacy:** Complete local cryptographic storage using Android Keystore and `EncryptedSharedPreferences`.
* **📱 Android 15 & 16 Ready:** Fully compliant with Android SDK 35 and 16KB memory page size architectures.

---

## 🚀 Getting Started

### 📱 Android Application

#### 1. Build the Ultra-Lean Release APK (1.1 MB)
```bash
./gradlew assembleRelease
```
The optimized APK will be generated at:
`app/build/outputs/apk/release/app-release.apk` (or root `Tailcat-v1.0.0-release.apk`)

#### 2. Run Unit Tests & Lint
```bash
./gradlew testDebugUnitTest
./gradlew check
```

#### 3. Install on Connected Device / Emulator
```bash
# Direct install via ADB
adb install -r Tailcat-v1.0.0-release.apk

# Launch App
adb shell am start -n com.tailcat.vpn/.ui.MainActivity
```

---

### 🖥️ Linux & macOS CLI (`tailcat-cli`)

The core Go engine includes a standalone CLI client for Linux and macOS.

#### 1. Build the CLI Binary
```bash
cd core-engine
go build -o tailcat-cli ./cmd/tailcat-cli
```

#### 2. Connect to a Gateway Token
```bash
# Connect with a Tailcat token
./tailcat-cli up tcXYZ...

# Query active telemetry
./tailcat-cli status

# Disconnect
./tailcat-cli down
```

---

## 📂 Repository Structure

```
tailcat vpn client/
├── core-engine/                   # Multiplatform Go Engine (WireGuard + Magicsock)
│   ├── cmd/tailcat-cli/           # Standalone CLI for Linux & macOS
│   ├── go.mod
│   └── main.go                    # WireGuard JNI bridge & packet pump
│
├── app/                           # Android Application Module
│   └── src/main/java/com/tailcat/vpn/
│       ├── core/                  # Token parser, IP auditor, speed engine
│       ├── service/               # Android VpnService & Foreground Service
│       ├── data/                  # Encrypted preferences & profile store
│       └── ui/                    # Jetpack Compose Cyberpunk UI
│
├── README.md                      # Project overview & quickstart
├── handoff.md                     # Integration handoff guide for nullexit
├── agents.md                      # Architecture & engineering specification
├── LICENSE                        # Apache 2.0 Open Source License
├── PRIVACY_POLICY.md              # Zero-logs privacy disclosure
├── SECURITY.md                    # Vulnerability reporting & crypto standards
└── THIRD_PARTY_NOTICES.md         # Open source attributions
```

---

## 📜 Legal & Compliance

* **[LICENSE](LICENSE):** Apache License, Version 2.0.
* **[PRIVACY_POLICY.md](PRIVACY_POLICY.md):** Strict Zero-Logs commitment, local on-device processing, and VpnService disclosures.
* **[SECURITY.md](SECURITY.md):** Cryptographic specifications, DNS leak prevention, and vulnerability reporting.
* **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md):** Open source attributions for WireGuard-Go, Tailscale Magicsock, and AndroidX.
