# Tailcat Android 🐾⚡

> **Control-Plane-Free WireGuard & Magicsock VPN Client for Android**

Tailcat is an ultra-lean, privacy-first Android VPN client engineered for decentralized, control-plane-free WireGuard mesh networking. It connects directly to gateway listeners via compact, permanent or time-limited connection tokens (`tc...`), eliminating centralized coordination servers, login portals, and external account tracking.

---

## ✨ Key Features

* **⚡ Control-Plane-Free P2P Tunneling:** Uses WireGuard + Magicsock + STUN for direct UDP hole-punching and automatic DERP relay fallback.
* **🔑 Connection Tokens (`tc...` with Expiration Support):** Base64URL/CBOR-encoded server public keys, DERP regions, and optional expiration (`exp`) timestamps. Real-time syntax and expiry validation preview.
* **📡 Real-Time Offline Detection & Alarms:** Animated Cyberpunk offline banner and pairing warnings when the device has no active internet connection.
* **🌍 Live Public Egress IP Auditor:** Real-time WAN IP, country, and city geolocation displayed right on the home dashboard with a one-tap refresh.
* **🏎️ Network Benchmark & Speedometer:** Multi-probe RTT Latency, Jitter variance, Download throughput, and Upload throughput with an animated Cyberpunk arc gauge.
* **🛡️ Kill-Switch & Auto-DERP:** Blocks unencrypted network traffic on disconnect, and forces reliable DERP encapsulation when roaming on isolated Enterprise Wi-Fi.
* **🔀 Split Tunneling:** Per-app routing allowing selected applications to bypass the VPN tunnel.
* **🪶 Ultra-Compact 1.1 MB Release Binary:** Fully minified with R8, tree-shaking, resource shrinking, and zero GC allocation overhead during benchmarking.
* **🔒 Strict Zero-Log Privacy:** Complete local cryptographic storage using Android Keystore and `EncryptedSharedPreferences`.
* **📱 Android 15 & 16 Ready:** Fully compliant with Android SDK 35 and 16KB memory page size architectures.

---

## 🛠️ Toolchain & Requirements

| Component | Target Version |
| :--- | :--- |
| **Android OS Support** | Android 8.0 (API 26) through Android 15 & 16 (API 35+) |
| **Gradle** | `9.5.0` (Gradle Wrapper) |
| **Android Gradle Plugin** | `9.3.0` |
| **Kotlin** | `2.2.10` |
| **Java JDK** | OpenJDK 21 (`21.0.11`) |
| **Jetpack Compose BOM** | `2025.02.00` |
| **Go Engine** | Go 1.22+ with `gomobile` (`libtailcat.aar`) |

---

## 🚀 Building & Running

### 1. Build the Ultra-Lean Release APK (1.1 MB)
```bash
./gradlew assembleRelease
```
The optimized APK will be generated at:
`app/build/outputs/apk/release/app-release.apk` (or root `Tailcat-v1.0.0-release.apk`)

### 2. Build the Debug APK
```bash
./gradlew assembleDebug
```

### 3. Run Unit Tests & Lint
```bash
./gradlew testDebugUnitTest
./gradlew check
```

### 4. Install on Connected Device / Emulator
```bash
# Direct install via ADB
adb install -r Tailcat-v1.0.0-release.apk

# Launch App
adb shell am start -n com.tailcat.vpn/.ui.MainActivity
```

---

## 📜 Legal & Compliance

* **[LICENSE](LICENSE):** Apache License, Version 2.0.
* **[PRIVACY_POLICY.md](PRIVACY_POLICY.md):** Strict Zero-Logs commitment, local on-device processing, and VpnService disclosures.
* **[SECURITY.md](SECURITY.md):** Cryptographic specifications, DNS leak prevention, and vulnerability reporting.
* **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md):** Open source attributions for WireGuard-Go, Tailscale Magicsock, and AndroidX.

---

## 📂 Architecture & Handoff

For complete technical details, token encoding specifications, and integration with nullexit, see [`handoff.md`](handoff.md) and [`agents.md`](agents.md).
