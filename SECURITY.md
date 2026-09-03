# Security policy

## Release status

OpenTailcat 1.1.1 in the current source tree is a development build. It has an
integrated Go Mobile Tailcat engine with Phase 0 fail-closed capability gates,
Phase 1 reproducible builds, Phase 2 official token validation, and Phase 3
tunneled UDP userspace netstack code. IPv4 test-routing capabilities are true so
a live token can Connect. `ipv6` is false. Physical-device leak acceptance and
full release gates are pending; it must not be relied on as a production
privacy VPN.

### Implemented security controls

- The native Meow/Meowed gateway handshake runs during `prepare`. Kotlin will not
  call `prepare` or create a TUN while capabilities are incomplete.
- The Android reflection boundary enforces an API v2 native capability contract.
  Production IPv4 default-route installation requires `dataPlane`, `wireGuard`,
  `magicsock`, `twoPhaseStart`, `ipv4`, `tcp`, `udp`, `dns`, `liveStats`, and
  `cancelSafeLifecycle`. `ipv6` is required only when IPv6 routes would be
  installed (`requireIpv6`). Unknown capability JSON fields fail closed.
  Incomplete engines fail closed immediately.
- Token validation in Android and Go strictly enforces official token structures,
  rejects legacy/synthetic disco keys, rejects duplicate CBOR keys, and rejects
  expired tokens.
- Tunneled UDP uses a single gVisor netstack proxy routing datagrams exclusively
  through Tailcat WireGuard/Magicsock via `Client.DialUDP` without direct OS UDP
  sockets in `core-engine`. This path is unpromoted (`udp` is false).
- The native engine checks gateway `CapExitUDP` during `prepare` and fails before
  TUN attachment if connected to a legacy/TCP-only gateway.
- Profiles and tokens are stored in encrypted preferences backed by Android
  Keystore. Android backup and device-to-device transfer are disabled.
- The app UID bypasses the VPN to prevent Magicsock/DERP transport recursion.
- Cleartext traffic is disabled for the Android application.

### Remaining release blockers & pending gates

- **IPv4 flags are test-enabled, not Phase 8 accepted**: leak capture still pending.
- **IPv6 dual-stack route**: Native TUN IPv6 is dropped. Android still installs
  no IPv6 VPN address or `::/0` (Phase 5). IPv6 may bypass over the underlying
  network if a VPN were active.
- **Lifecycle promotion**: the cancellable machine exists; `twoPhaseStart` and
  `cancelSafeLifecycle` stay false until promotion evidence (Phase 6).
- **Live physical-device acceptance**: Uplink packet capture on multi-interface
  devices to verify zero direct destination leaks (Phase 8).
- **Production release signing**: Signing with a production keystore (Phase 8).

See [handoff.md](handoff.md) for the ordered continuation plan and test gates.
