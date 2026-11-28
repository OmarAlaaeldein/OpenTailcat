# Privacy Policy for OpenTailcat

**Last updated:** November 27, 2026

OpenTailcat has no accounts, ads, analytics SDKs, remote crash reporting, or hosted control plane. This policy describes the behavior of the current source tree. See [README.md](README.md#project-status) for development-preview limitations.

## Data stored on your device

OpenTailcat stores gateway profile names, `tc…` connection tokens, the active profile selection, default MTU/DNS settings, debug logging toggle, split-tunnel excluded package names, and the country codes you choose to block locally.

All preferences are encrypted value-by-value with AES-256-GCM using a non-exportable key in Android Keystore. Older plaintext or legacy encrypted preference files from previous versions are migrated once and removed. Android cloud backup and device-to-device data transfer are disabled in the app manifest.

Saved profile tokens can be shown as a QR code (`FLAG_SECURE` enabled) and imported from an image picked through Android's system picker. Decoding happens in memory on the device via ZXing; no image or token is sent to an external app or server, and OpenTailcat does not request camera permission.

Treat gateway tokens as credentials: anyone with a token can try to connect to that gateway.

## Network requests

OpenTailcat makes network connections only for the features below:

- **Device public IP readout:** on app launch, disconnect, or manual refresh (while disconnected), the app queries `https://1.1.1.1/cdn-cgi/trace` (TLS-pinned) over normal Android HTTP sockets to display your public IP and country.
- **Gateway tunnel & probes:** connecting to a gateway contacts the gateway peer and its DERP relay(s). When a token does not embed a custom DERP map, the engine fetches `https://tailcat.dev/derpmap.json` and resolves relay hostnames through Android's DNS servers on protected sockets. During connection setup and health monitoring, the engine probes through the gateway to verify UDP forwarding (STUN to `162.159.207.0:3478` and `74.125.250.129:19302`), IPv6 WAN reachability (TLS-pinned to `[2606:4700:4700::1111]:443`), and tunnel exit IP (TLS-pinned `https://1.1.1.1/cdn-cgi/trace` via `Client.DialTCP`).
- **Tunneled traffic and DNS:** while connected, captured device traffic and DNS queries go through the WireGuard tunnel to your gateway under the profile's DNS policy. If you enable the country filter, destinations are matched on the device against a bundled snapshot of the regional internet registries' delegation data; no destination or country lookup is sent anywhere.
- **Speed test (on demand):** when connected, the speed test resolves `speed.cloudflare.com` over TCP to `1.1.1.1:53` and tests against `1.1.1.1` / `speed.cloudflare.com` (TLS-pinned) through `Client.DialTCP`. When not connected, it uses normal Android HTTP sockets (following your device's active routes: your physical network if the VPN is off, or the TUN if a VPN interface is up). The UI labels which path was used.
- **In-app updates (`github` flavor only):** tapping Check for update queries the GitHub Releases API, and tapping Download fetches the APK from GitHub release-asset hosts. The `fdroid` flavor contains no updater code.
- **Share this phone (exit node):** when enabled, the phone connects to the nearest public DERP region (fetching `https://tailcat.dev/derpmap.json`) or to a custom relay hostname you enter. Outbound connections are opened from your phone to whatever Internet destinations approved guests request.

Your gateway operator, relay servers, Cloudflare, and GitHub receive standard network metadata (IP address, timestamps, TLS/HTTP headers) under their respective policies. OpenTailcat adds no account IDs or advertising identifiers.

## Gateway and split-tunnel visibility

Traffic between OpenTailcat and your gateway is WireGuard-encrypted. DERP relays see encrypted transport packets and connection metadata. Your gateway decrypts exit traffic and can observe it like any VPN server.

Apps checked in split-tunnel settings bypass the VPN directly over your device's network. OpenTailcat does not exclude its own UID from the VPN: only native WireGuard/Magicsock/DERP transport sockets are protected via `VpnService.protect`, while ordinary app HTTP requests follow Android's routing table.

## Sharing this phone (exit node)

Sharing is off until you start it. While active, your phone acts as the exit gateway:

- Guest traffic arrives WireGuard-encrypted and leaves unencrypted from your phone over your Wi-Fi or mobile data. Destination sites see your IP address, and your ISP/carrier sees the traffic and counts it toward your plan.
- The app keeps only live in-memory counters (connected devices, active flows, byte totals). It does not store or log guest traffic, destination addresses, or guest keys.
- Each session generates fresh keys in memory only. Stopping sharing, expiry, or Android killing the app discards those keys so the code stops working. The sharing screen sets `FLAG_SECURE` while displaying the code, and only writes to the clipboard when you tap Copy (flagged `EXTRA_IS_SENSITIVE` on Android 13+).
- Default admission requires your manual approval per device; open admission is optional and always time-limited (max 24 hours).
- Guests are blocked from connecting to loopback, private LAN, CGNAT, link-local, and multicast destinations.
- A persistent notification displays the active device count, data transferred, and remaining time.

You are responsible for traffic sent through your connection, so share only with people you trust.

## Android permissions

- `INTERNET`: tunnel transport, public IP lookup, speed test, exit-IP audit, updates, exit-node forwarding.
- `ACCESS_NETWORK_STATE`: detect online status and Wi-Fi/cellular roaming.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`: keep the client VPN service running.
- `FOREGROUND_SERVICE_SPECIAL_USE`: keep the exit-node sharing service running.
- `WAKE_LOCK`: keep the CPU awake while sharing (timed or renewed chunks, released on stop).
- `POST_NOTIFICATIONS`: show VPN and sharing status notifications on Android 13+.
- `BIND_VPN_SERVICE`: required by Android for `VpnService`.
- `QUERY_ALL_PACKAGES`: list installed apps locally for the split-tunnel exclusion screen.
- `REQUEST_INSTALL_PACKAGES` (`github` flavor only): hand verified downloaded update APKs to Android's package installer.

OpenTailcat does not request camera, location, contacts, storage, or advertising permissions.

## Logging and diagnostics

There is no remote logging or telemetry server. The speed-test screen lets you copy a plain-text troubleshooting report (public IPs redacted) to the clipboard; other apps with clipboard access could read it if you copy it.

By default, native logs record only errors and brief network-change summaries (no tokens, payloads, or peer IPs). Turning on **Settings → Diagnostics → Debug failure reports** enables verbose upstream Tailcat engine logs (including local/public IP addresses and relay endpoints) in Android's system logcat and appends a telemetry summary to failure banners.

## Contact

Report security issues via [SECURITY.md](SECURITY.md). For other questions, use the repository's maintainer contact channel.
