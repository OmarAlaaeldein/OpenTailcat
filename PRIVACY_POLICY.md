# Privacy policy for OpenTailcat

**Last updated:** September 1, 2026

OpenTailcat is designed without accounts, advertising, analytics SDKs, remote
crash reporting, or a proprietary coordination service. This policy describes
the current source tree, including its known development limitations.

## Development warning

The current build is not a leak-free full-device VPN and must not be relied on
for privacy-sensitive traffic:

- IPv4 TCP accepted by the native adapter is proxied through gVisor and Tailcat to the selected gateway.
- IPv4 UDP is proxied through gVisor userspace netstack to Tailcat WireGuard/Magicsock, eliminating direct OS application sockets.
- UDP destination port 53 is intercepted and routed according to the configured DNS policy (profile resolver destination or forced preset) through Tailcat WireGuard, supporting 4096-byte datagrams and TC=1 TCP fallback.
- Android currently installs no IPv6 VPN address or default route. IPv6 may use the underlying network unless Android's system lockdown blocks it.
- The in-app network benchmark uses ordinary app connections. OpenTailcat's own UID bypasses the VPN, so these requests measure the direct device network and are explicitly labeled as physical-network benchmarks in the UI.
- Fail-closed capability negotiation ensures that default-route VPN establishment is refused while data-plane capabilities remain unproven.

## Data stored on the device

OpenTailcat stores gateway names, connection tokens, the selected profile, MTU
defaults, DNS/profile settings, and split-tunnel package names locally. These
preferences use AES-256 encrypted preferences with a key protected by Android
Keystore. Existing plaintext preferences from earlier builds are migrated and
then cleared.

Gateway tokens are secrets. Anyone who obtains one may learn connection
metadata or attempt to reach its gateway. Protect device access, screenshots,
logs, and exported diagnostics. OpenTailcat disables Android backup and
device-to-device transfer for application data.

## Network requests made by the app

- On startup and manual refresh, the app requests Cloudflare
  `https://1.1.1.1/cdn-cgi/trace` to display the app process' public IP and
  country code. If that fails, it requests
  `https://api.ipify.org?format=json` for the IP only.
- When the user starts a benchmark, the app requests Cloudflare endpoints at
  `1.1.1.1` and `speed.cloudflare.com` for HTTP latency, download, and upload
  measurements.
- During a prepared Tailcat session, the native engine contacts the relay
  described by the token or DERP map and the configured gateway peer.
- After TUN attachment, the native engine attempts an exit-IP audit through
  Tailcat. It first requests `api.ipify.org` over HTTP through the gateway and
  falls back to an authenticated TLS request to Cloudflare `1.1.1.1`.
- Intercepted DNS is currently sent through the gateway to Cloudflare or Google
  as described in the development warning.

These providers and the selected gateway receive ordinary connection metadata
such as source IP, time, TLS information, and request headers under their own
policies. OpenTailcat does not attach an account ID or advertising identifier.

## VPN and gateway visibility

Traffic that is actually carried through Tailcat is WireGuard-encrypted between
the device and selected gateway. When DERP is used, the relay operator observes
encrypted transport metadata. The gateway operator can observe decrypted exit
traffic and connection metadata to the same extent as another VPN provider.
OpenTailcat cannot make privacy promises on behalf of a user-selected gateway.

Because of the current UDP and IPv6 limitations, destination services may also
observe the device's direct public IP for bypassed traffic. This is a known
release blocker, not an intended split-tunnel feature.

Apps explicitly selected in OpenTailcat's split-tunnel settings also bypass the
VPN. OpenTailcat itself is always excluded so its Magicsock/DERP transport does
not recursively enter the TUN.

## Android permissions

- `INTERNET`: Tailcat transport, public-IP checks, speed tests, exit auditing,
  and current packet forwarding.
- `ACCESS_NETWORK_STATE`: validated connectivity and network-change detection.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`: continuous
  operation of an active Android VPN service.
- `POST_NOTIFICATIONS`: optional foreground VPN status on Android 13+.
- `BIND_VPN_SERVICE`: enforced by Android on the VPN service declaration.

OpenTailcat does not request camera, location, contacts, storage, or advertising
permissions.

## Logging and diagnostics

The application contains no analytics or project-owned telemetry endpoint.
Benchmark and IP results are held in memory for display. Native/system logs may
contain connection errors or relay metadata; tokens and traffic payloads must
not be intentionally logged. Android, device vendors, gateways, relays, and
external endpoint operators may maintain independent logs.

## Contact

For security issues, follow [SECURITY.md](SECURITY.md). For non-sensitive policy
questions, use the repository's normal maintainer contact channel.
