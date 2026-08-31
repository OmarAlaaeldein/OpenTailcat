# Privacy Policy for Tailcat VPN

**Last Updated:** August 31, 2026

Tailcat ("we", "our", or "the application") is committed to protecting your privacy. This Privacy Policy explains our practices regarding user data, network traffic, and permissions when using the Tailcat Android application.

---

## 1. Zero-Log Policy (No Data Collection)

Tailcat is designed from the ground up to be **control-plane-free and decentralized**. 

* **No Traffic Logging:** We do not collect, monitor, store, or log your browsing history, DNS queries, traffic destinations, data content, or packet payloads.
* **No Centralized User Tracking:** We do not have user accounts, tracking analytics, advertising IDs, or telemetry SDKs.
* **No Centralized Servers:** Tailcat does not connect to any proprietary centralized authentication or coordination servers. Your client communicates directly with the gateway listener you pair via your token.

---

## 2. On-Device Storage & Local Processing

All application configuration and state are stored **exclusively on your local device**:

* **Gateway Profiles & Tokens:** Stored locally in Android's `EncryptedSharedPreferences` backed by the Android Keystore.
* **Network Settings:** MTU, TCP MSS preferences, split-tunneling lists, and kill-switch configurations are stored locally on your device and are never transmitted to any third party.
* **Public IP & Speed Benchmarking:** When you run the Egress IP auditor or Speed Test, lightweight diagnostic requests are sent directly to Cloudflare edge endpoints (`1.1.1.1` / `speed.cloudflare.com`) to calculate public WAN IP, latency, and throughput. No personal identifiers or payload data are included.

---

## 3. Permissions Used by Tailcat

* **`android.permission.INTERNET`:** Required to transmit encrypted WireGuard UDP packets and DERP relay traffic.
* **`android.permission.ACCESS_NETWORK_STATE`:** Required to monitor network connectivity changes (Wi-Fi to Cellular handoffs) to maintain persistent tunnel state.
* **`android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`:** Required by Android to run the `VpnService` background packet pump continuously without being killed by the operating system.
* **`android.permission.POST_NOTIFICATIONS`:** Required on Android 13+ to display the ongoing VPN status notification with live throughput stats and a one-tap disconnect button.
* **`android.permission.CAMERA` (Optional):** Used strictly for on-device QR code scanning to import gateway pairing tokens. No photos or video frames are ever recorded or transmitted.

---

## 4. Third-Party Dependencies

Tailcat incorporates open-source components:
* **Tailscale Magicsock & WireGuard-Go:** Open-source cryptographic tunnel and NAT traversal engine (BSD / MIT licensed).
* **Cloudflare STUN / DERP Nodes:** Used strictly for public STUN endpoint discovery and relay fallback when direct UDP hole-punching fails.

---

## 5. Contact & Security

If you have questions regarding this Privacy Policy or discover any security vulnerabilities, please refer to [`SECURITY.md`](file:///Users/omar/developer/tailcat%20vpn%20client/SECURITY.md) or open an issue in the project repository.
