# Privacy Policy for Tailcat

**Last updated:** August 31, 2026

Tailcat is designed without accounts, advertising, analytics SDKs, or a proprietary coordination service. This policy describes the current source tree; downstream builds that add a native engine, relays, or different endpoints must update it before distribution.

## Data stored on the device

Tailcat stores gateway names, connection tokens, selected profile, MTU defaults, and split-tunnel package names locally. These preferences are encrypted with AES-256 schemes using a key protected by Android Keystore. Existing plaintext preferences from earlier builds are migrated into encrypted storage and then removed.

Gateway tokens are secrets. Anyone who obtains a token may learn connection metadata or be able to attempt a gateway connection, depending on the gateway protocol. Protect device backups and screenshots accordingly. Tailcat disables Android backup and device-to-device transfer for its app data.

## Network requests made by the Android app

- On app startup and manual refresh, Tailcat requests Cloudflare's `https://1.1.1.1/cdn-cgi/trace` to display the app process' public IP and country code. If that fails, it requests `https://api.ipify.org?format=json` for the IP only.
- When the user explicitly starts a speed test, Tailcat requests Cloudflare endpoints at `1.1.1.1` and `speed.cloudflare.com` to measure HTTP latency, download throughput, and upload throughput.

These providers receive ordinary connection metadata such as the source IP, time, TLS information, and request headers under their own policies. Tailcat does not attach an account ID or advertising identifier.

The public-IP card is labeled **Device IP** when disconnected. When connected, the home screen displays **Exit IP** (measured via TLS through the encrypted tunnel) and the private VPN address (`100.64.0.2`).

## VPN traffic

Tailcat establishes an end-to-end encrypted WireGuard and Magicsock tunnel directly to the user-configured exit gateway. When active, device traffic routes through the encrypted tunnel to the exit gateway.

Traffic routed through the VPN is visible to the user-selected gateway operator and, if relayed before NAT hole-punching, the relay operator observes encrypted WireGuard packets. The gateway operator can observe connection metadata and decrypted exit traffic to the same extent as any VPN provider. Tailcat cannot make privacy promises on behalf of a gateway chosen by the user.

## Android permissions

- `INTERNET`: public-IP checks, speed tests, and future encrypted tunnel transport.
- `ACCESS_NETWORK_STATE`: validated connectivity and roaming detection.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`: continuous operation of an active Android VPN service.
- `POST_NOTIFICATIONS`: optional display of foreground VPN status on Android 13+.
- `BIND_VPN_SERVICE`: enforced by Android on the VPN service declaration.

Tailcat does not request camera, location, contacts, storage, or advertising permissions.

## Logging and diagnostics

The application contains no analytics or remote crash-reporting SDK. Network benchmark results and public-IP results are held in memory for display and are not uploaded by Tailcat to a project-owned server. Android, the device vendor, a gateway, and external endpoint operators may maintain their own logs independently.

## Contact

For security issues, follow [SECURITY.md](SECURITY.md). For non-sensitive policy questions, use the project repository's normal maintainer contact channel.
