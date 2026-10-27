# Privacy policy for OpenTailcat

**Last updated:** October 26, 2026

OpenTailcat has no accounts, advertising, analytics SDKs, remote crash
reporting, or coordination service. This policy covers the current source tree;
the native engine is described in [AGENTS.md](AGENTS.md).

## Development status

This is a development build, not a leak-free VPN. It installs IPv4 and IPv6
default routes after the packet pumps are live, proxies TCP/UDP/DNS through
your gateway, and drops ICMP echo. Split-tunnel apps bypass the tunnel.
Reconnection removes the TUN between attempts, so enable Android's Always-on
VPN with Block connections without VPN if gaps matter to you.

Do not rely on this build for traffic whose exposure would harm you.

## Data stored on the device

OpenTailcat stores gateway names, connection tokens, the selected profile, MTU
and DNS settings, and split-tunnel package names locally. These use AES-256
encrypted preferences with a key held by Android Keystore. Plaintext
preferences from earlier builds are migrated and cleared. Android backup and
device-to-device transfer are disabled for app data.

Gateway tokens are secrets. Anyone who obtains one may learn connection
metadata or attempt to reach its gateway. Protect device access, screenshots,
logs, and exported diagnostics.

## Network requests

- On startup and manual refresh, the app requests Cloudflare
  `https://1.1.1.1/cdn-cgi/trace` (TLS-pinned) to show the app process's public
  IP and country. This follows device routes, not the tunnel.
- A benchmark started while CONNECTED uses `Client.DialTCP` through the
  gateway; `speed.cloudflare.com` is resolved with DNS-over-TCP to
  `1.1.1.1:53`. When not CONNECTED it uses ordinary app connections. The UI
  labels which path ran.
- During a prepared session the native engine contacts the gateway peer and
  the relay described by the token or DERP map. It may fetch
  `https://tailcat.dev/derpmap.json`, and relay hostnames use the device
  resolver.
- **Check for updates** requests the GitHub Releases API; **Download** fetches
  the APK from GitHub and its release-asset hosts. The F-Droid flavor has no
  updater.
- After TUN attachment, the engine performs an exit-IP audit through Tailcat
  (authenticated TLS `GET /cdn-cgi/trace` to `1.1.1.1` via `Client.DialTCP`).
  Intercepted DNS is proxied through Tailcat under the profile or forced
  resolver policy.

These providers and your gateway receive ordinary connection metadata such as
source IP, time, TLS details, and request headers under their own policies.
OpenTailcat adds no account ID or advertising identifier.

## Gateway visibility

Traffic carried through Tailcat is WireGuard-encrypted between the device and
the selected gateway. A DERP relay sees encrypted transport metadata. The
gateway operator can observe decrypted exit traffic, like any VPN provider.
OpenTailcat makes no privacy promises on behalf of a gateway you choose.

Apps selected in split-tunnel settings bypass the VPN. OpenTailcat does not
exclude itself: Tailcat transport sockets are protected with
`VpnService.protect`, while its other requests follow device routes.

## Android permissions

- `INTERNET` — Tailcat transport, public-IP checks, benchmarks, exit audit.
- `ACCESS_NETWORK_STATE` — connectivity and network-change detection.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` — active VPN
  service.
- `POST_NOTIFICATIONS` — optional VPN status notification on Android 13+.
- `BIND_VPN_SERVICE` — required by Android for the VPN service.
- `QUERY_ALL_PACKAGES` — lists installed apps for the split-tunnel picker. The
  list stays on the device.
- `REQUEST_INSTALL_PACKAGES` — hands a downloaded update to the installer.

No camera, location, contacts, storage, or advertising permissions are
requested.

## Logging and diagnostics

There is no analytics or project-owned telemetry endpoint. Benchmark and IP
results live in memory. The Network Benchmark screen can copy a plain-text
report (redacted public IPs) to the clipboard; the app sends it nowhere, but
other apps with clipboard access may read it.

Native logs may contain connection errors or relay metadata; tokens and traffic
payloads are not intentionally logged. Detailed engine logs, including public
and local addresses and relay endpoints, are written to the Android system log
only while Settings → Diagnostics → Debug failure reports is on. Otherwise the
engine logs errors and one line per network change. Android, vendors,
gateways, relays, and endpoint operators may keep independent logs.

## Contact

For security issues use [SECURITY.md](SECURITY.md). For other policy questions,
use the repository's maintainer contact channel.
