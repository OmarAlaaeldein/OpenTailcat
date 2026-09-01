# Security policy

## Release status

OpenTailcat 1.0.0 in the current source tree is a development build. It has an
integrated Go Mobile Tailcat engine and a working pre-route Meow handshake and
TCP proxy, but it has not passed the full-device VPN security gate and must not
be distributed or relied on as a privacy VPN.

### Known critical limitations

- Non-DNS UDP is currently sent through ordinary process UDP sockets. Because
  OpenTailcat's UID is excluded from Android VPN routing, this traffic bypasses
  the Tailcat gateway.
- The VPN installs only an IPv4 default route. IPv6 is not captured and may use
  the underlying network unless Android lockdown blocks it.
- The native engine is incomplete (UDP uses direct sockets, IPv6 is unassigned,
  telemetry is not live WireGuard counters); development builds fail closed so
  Android refuses to install a default route.
- Transport, RTT, and byte telemetry is not yet authoritative live
  WireGuard/Magicsock telemetry.
- Legacy tokens without a disco key are accepted by parsers but cannot be made
  connectable by deriving a disco key from the node public key.
- Live cellular/Wi-Fi roaming, forced DERP fallback, packet-pump failure, and
  traffic-leak testing have not passed.

See [handoff.md](handoff.md) for the ordered remediation and release tests.

## Controls already present

- The native Meow/Meowed gateway handshake runs before Android creates the TUN
  and default IPv4 route.
- The Android reflection boundary enforces an API v2 native capability contract
  requiring explicit, proven capabilities (`dataPlane`, `ipv4`, `ipv6`, `tcp`,
  `udp`, `dns`, `liveStats`, `cancelSafeLifecycle`). Incomplete or older engines
  fail closed immediately.
- Tokens are validated in Android and Go, and expired timestamp-bearing tokens
  are rejected. Duplicate-key and cross-language consistency hardening remains
  required.
- Profiles and tokens are stored in encrypted preferences backed by Android
  Keystore. Android backup and device-to-device transfer are disabled.
- The app UID bypasses the VPN to prevent Magicsock/DERP transport recursion.
  Application traffic implemented inside that UID must therefore use only
  Tailcat's internal data plane, never ordinary sockets.
- Startup exceptions close the Android TUN, telemetry failures request service
  teardown, and native `stop` is intended to be idempotent. Cancellation and
  concurrent lifecycle tests remain release gates.
- Cleartext traffic is disabled for the Android application.

## Required pre-distribution gates

- Fail-close native capabilities until TCP, UDP, DNS, IPv4, IPv6, lifecycle,
  roaming, and live telemetry are implemented and tested.
- Prove generic UDP and DNS use a compatible gateway; official Tailcat's current
  exit-node path is TCP-only without a matching UDP extension.
- Capture Android uplink and gateway traffic to prove there is no direct
  destination TCP, UDP, DNS, or IPv6 traffic while connected.
- Test API 26 and current Android, physical ARM64 hardware, Always-on VPN,
  lockdown, Wi-Fi/cellular roaming, screen-off, process death, and forced pump
  failures.
- Rebuild and inspect the AAR, verify R8/JNI retention and 16 KB page alignment,
  and produce an SBOM/license report.
- Sign APK/AAB artifacts only with the private production key and verify the
  resulting signatures. Never use the Android debug key for a release.

## Reporting a vulnerability

Do not publish secrets, gateway tokens, private signing material, traffic
captures, or working exploits in a public issue. Use the repository owner's
private security-reporting channel if configured. Otherwise open a minimal,
non-sensitive issue requesting private contact.

A useful report includes the affected commit/version, Android version and
device, network/gateway conditions, expected and observed behavior, and the
least-sensitive reproduction possible.
