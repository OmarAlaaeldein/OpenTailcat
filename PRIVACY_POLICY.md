# Privacy policy for OpenTailcat

**Last updated:** November 21, 2026

OpenTailcat has no accounts, advertising, analytics SDKs, remote crash
reporting, or coordination service. This policy covers the current source
tree. What the app is and how it works is in the [README](README.md);
engineering detail is in [AGENTS.md](AGENTS.md).

## Development status

Development preview, not a leak-free VPN. Current limitations are in the
README's [project status](README.md#project-status). Split-tunnel apps bypass
the tunnel.

## Data stored on the device

OpenTailcat stores gateway names, connection tokens, the selected profile, MTU
and DNS settings, and split-tunnel package names locally. These use AES-256
encrypted preferences with a key held by Android Keystore. Plaintext
preferences from earlier builds are migrated and cleared. Android backup and
device-to-device transfer are disabled for app data.

The app can display a saved profile's token as a QR code, and can import a QR
code from an image you choose with the system picker. The image is decoded in
memory on the device and is not sent anywhere. OpenTailcat does not request
camera permission and does not hand tokens to an external scanner.

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
- **Share this phone** (see below) connects to a relay. With the default
  relay it fetches the public relay map (`https://tailcat.dev/derpmap.json`)
  and picks the nearest region. If you name your own relay, it connects to
  that host instead and does not fetch the map. The phone opens a connection
  to every destination a guest asks for.
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

## Sharing this phone

Share this phone is optional and off until you start it. While it runs, you are
the gateway operator for the person you shared with.

- **What the phone handles.** A guest's traffic is WireGuard-encrypted to your
  phone, which forwards it to the Internet over your own Wi-Fi or mobile
  network. Like any gateway, your phone handles that traffic unencrypted, and
  the sites it reaches see your IP address. Your network provider may also see
  it and may count it against your plan.
- **What the app keeps.** Only live counters in memory: connected devices,
  bytes in each direction, and open flows. It does not record the destinations
  or contents of a guest's traffic, and logs none of it. Devices appear as a
  short fingerprint of their session key, not a name or account.
- **Keys and the code.** Each session creates new keys and a new code in
  memory. Nothing is written to storage. Stopping, expiry, or Android closing
  the app discards the keys, so the code stops working. The code is shown in a
  window Android will not screenshot, and is copied to the clipboard only when
  you press Copy.
- **Who can connect.** By default each new device waits until you approve it.
  You can instead allow anyone holding the code (always time-limited). Anyone
  you approve, or who holds the code in that mode, can use your connection
  until sharing ends.
- **What is refused.** Guests cannot reach your phone itself or the networks it
  is on (loopback, private, carrier-internal, link-local, multicast).
- **A notification** shows while sharing, with the device count and data moved.

Responsibility for what a guest does through your connection, including your
carrier's or network's terms, rests with you. Share only with people you trust.

## Android permissions

- `INTERNET` — Tailcat transport, public-IP checks, benchmarks, exit audit.
- `ACCESS_NETWORK_STATE` — connectivity and network-change detection.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` — active VPN
  service.
- `FOREGROUND_SERVICE_SPECIAL_USE` — the foreground service that forwards a
  guest's traffic while you share this phone.
- `WAKE_LOCK` — keeps the CPU awake only while sharing, so forwarding does not
  stall when the screen turns off. It is released when sharing stops.
- `POST_NOTIFICATIONS` — optional VPN and sharing status notifications on
  Android 13+.
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
