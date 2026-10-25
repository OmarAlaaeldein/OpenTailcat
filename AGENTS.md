# OpenTailcat Android engineering guide

> Project root: repository checkout  
> Android: min API 26, compile API 37, target API 35  
> Android toolchain: Kotlin 2.2.10, AGP 9.4.1, Gradle 9.6.0, JDK 21  
> Native toolchain in `core-engine/go.mod`: Go 1.27.3

Read this file completely before changing code. It is the single
authoritative contract, status snapshot, phase plan, and test gate list.

## Current audited status

The repository is **not production-ready**. IPv4 test-routing capabilities are
true so a live token can Connect. `ipv6` is true (client dual-stack path;
session `ipv6Egress` reports gateway WAN). Phase 8 physical leak
acceptance is unimplemented.

The checked-in AAR is built reproducibly with Go 1.27.3, NDK r29 (29.0.14206865),
16 KB ELF load alignment, and verified Java signatures.

Current version: 1.5.4 (versionCode 43). Per-release changes are in
`docs/releases/`; the 2026-09-26 review and its fix logs are in
`docs/review-2026-09-26.md`.
IPv4 Connect is test-enabled. `ipv6` is true; `ipv6Egress` is measured per session.

Critical current behavior:

- IPv4 TCP: gVisor -> Tailcat `Client.DialTCP` -> gateway. A clean EOF in one
  direction is passed on as a half-close and the proxy waits for both
  directions; a reset or write error ends both. `DialTCP` returns before the
  gateway dials the destination, so an unreachable host shows up as
  connected-then-EOF (a real refusal needs a gateway change). At most 512
  proxied TCP connections; the local gVisor stack uses 128 KiB default / 1 MiB
  max TCP buffers.
- UDP/53: gVisor netstack proxies datagrams via `Client.DialUDP` to the TUN
  destination (PROFILE_RESOLVER) or `ForcedDNS` (FORCED_RESOLVER). The engine
  does not inspect DNS TC bits on this path; a libc/app TCP/53 retry is a
  normal TCP proxy. With `tcpOnly`, UDP/53 is carried as DNS-over-TCP (one
  pipelined gateway connection per app socket, redialed if the resolver
  closes it) and an
  answer larger than the client's EDNS size (or 512 B) comes back as a
  TC-flagged header and question.
- Other IPv4 UDP: gVisor netstack proxies datagrams via `Client.DialUDP` across
  Tailcat netstack (no application-flow `net.DialUDP` in `core-engine`).
- UDP payloads over 1232 B (Tailcat `MaxUDPPayload`) are dropped and counted in
  `mtuExceeded`, because the tunnel loses them. An unfragmented datagram gets
  ICMP Fragmentation Needed (next-hop MTU 1260, IPv4 with DF only) or Packet
  Too Big (MTU 1280, IPv6); a datagram reassembled from fragments is dropped
  after reassembly. Locally generated ICMP errors are limited to 100/s.
- UDP flow table: one device-wide table of 1024 flows (every app shares the TUN
  address). When it is full the least recently active flow is evicted
  (`udpEvictions`) instead of refusing the new one. Idle timeouts: DNS 2 s after
  every query is answered, 10 s with one outstanding; other flows 30 s until a
  reply, then 2 min.
- IPv6: Android installs `fd7a:115c:a1e0::2/128` on a warm TUN, then `::/0` after
  pumps are live. Native `handleIPv6` proxies TCP/UDP; public IPv6 is rejected before dial when `ipv6Egress` is false, otherwise IPv6 uses the IPv4 dial budget (15 s TCP / 10 s UDP), so
  dual-stack apps fall back to tunneled IPv4. The check applies to the
  destination after the DNS redirect (a query to an IPv6 resolver forced to an
  IPv4 resolver still works) and exempts NAT64 `64:ff9b::/96`. Kotlin refuses
  Connect when the profile DNS server is IPv6 and prepare measured no gateway
  IPv6 egress. ICMPv6 echo is dropped;
  oversized IPv6 gets a local Packet Too Big; oversized IPv4 with DF gets
  Fragmentation Needed. Capability `ipv6` is true; without gateway IPv6 WAN, public IPv6 is
  fail-closed (RST/drop) so Happy Eyeballs uses tunneled IPv4.
- ICMP echo: IPv4 and ICMPv6 echo are dropped (counted in `policyRejections`).
  Tailcat carries only TCP and UDP, and a local reply would report every host
  as reachable.
- Speed test: when CONNECTED, ping/download/upload use `Client.DialTCP` through
  the gateway (`speed.cloudflare.com` is resolved with DNS-over-TCP via
  `Client.DialTCP` to `1.1.1.1:53`); otherwise ordinary app sockets on the
  device's current routes (not a tunnel measurement). Both paths report the
  running rate while measuring (the gateway path through a `SpeedProgress`
  callback about every 250 ms), so the gauge moves during either.
- Telemetry: schema version 2. RTT is sampled from `DiscoPing` about every 5s
  while a bridge is running; jitter is null until three samples. Transport
  follows the last successful `DiscoPing` (Endpoint set => DIRECT_P2P).
  `lastDiscoOkUnixSec` is the bridge start or the last successful `DiscoPing`;
  sub-millisecond RTTs report as 1 ms. `dropCounters.udpEvictions` counts
  UDP flows evicted from a full table; `linkQueueDrops` counts local-stack
  packets dropped on a full queue to the TUN writer and `udpBufferDrops`
  datagrams dropped on a full local UDP receive buffer. `dnsQueries` counts
  plain DNS forwarded to the gateway (each UDP/53 datagram and each proxied
  TCP/53 connection); encrypted DNS (Android Private DNS) is not counted.
  WireGuard peer Tx/Rx (`txBytes`/`rxBytes`) stay 0 because upstream `Client`
  has no Status API; `tunTxBytes`/`tunRxBytes` count each packet once as it
  crosses the TUN. `lastHandshakeSec`, `directEndpoint` and
  `wireguardTx/RxBytes` are no longer sent (they were never set).
  Kotlin rejects v1 and requires `RUNNING` plus fresh `healthUnixSec` for
  CONNECTED. `liveStats` is test-enabled.
- Logging: the Tailcat client's logger drops upstream lines (public IP from
  STUN, local addresses, peer endpoints) unless `updateNetworkState` sent
  `verboseLogs: true`, which Kotlin sets from Settings > Diagnostics at
  connect; otherwise only a `LinkChange: major/minor` summary and the engine's
  own error lines reach logcat.
- Capabilities: API v2 dual-stack flags true including `ipv6`. The JSON also
  includes `testRouting: true`, which marks that Phase 8 physical leak
  acceptance has not passed; Kotlin surfaces this in Settings and does not
  gate Connect on it. After
  `prepare`, Android attaches a host-only TUN (no VPN DNS), `detachTun`, then
  installs `0.0.0.0/0` and `::/0` with VPN DNS and reattaches. Profile
  `tunnelMtu` is forwarded in `updateNetworkState` so the native bridge and
  netstack use the same MTU as `VpnService.Builder`.
- Tests: unit, race, lint, and build checks have passed in earlier audits;
  native proxy tests use controlled fakes, and host liveprobes are opt-in.
  A full local-gateway integration harness and physical tunnel acceptance
  remain pending. CI (`.github/workflows/ci.yml`) also
  runs gofmt, staticcheck and govulncheck; `emulator.yml` runs an emulator
  smoke test on `main` pushes and pull requests, and on `main` pushes, nightly
  and on demand a live-gateway emulator run (pairing from the
  `OTC_LIVE_TOKEN` secret; skipped without it). Scripts are in
  `scripts/emulator/`.

Do not call this build secure, protected, complete, production-ready, or
leak-free. Do not publish or sign it as a VPN release.

## Current data-flow truth table

| Input from Android | Native handling | Actual egress |
| --- | --- | --- |
| IPv4 TCP | gVisor terminates TCP and proxies the stream with `Client.DialTCP`; half-close is passed on and both directions finish before teardown; at most 512 connections | Tailcat WireGuard/Magicsock to gateway |
| IPv4 UDP destination port 53 | gVisor proxies datagram via `Client.DialUDP` to TUN dest (PROFILE_RESOLVER) or `ForcedDNS` (FORCED_RESOLVER). Engine does not inspect TC bits; a libc/app TCP/53 retry is a normal TCP proxy. With `tcpOnly` it is carried as DNS-over-TCP and an answer over the client's EDNS size (or 512 B) returns TC-flagged. The flow closes 2 s after every query is answered | Tailcat WireGuard/Magicsock to gateway |
| Other IPv4 UDP | gVisor proxies datagrams via `Client.DialUDP` across Tailcat netstack. One device-wide table of 1024 flows evicts the least recently active flow when full; idle timeout 30 s until a reply, then 2 min | Tailcat WireGuard/Magicsock to gateway (pending live acceptance) |
| UDP payload over 1232 B (Tailcat `MaxUDPPayload`) | Dropped and counted in `mtuExceeded`. Unfragmented: local ICMP Fragmentation Needed with next-hop MTU 1260 (IPv4 with DF) or Packet Too Big 1280 (IPv6), at most 100 ICMP errors/s. Reassembled from fragments: dropped after reassembly | No gateway request |
| IPv6 TCP/UDP | gVisor inject → `DialTCP`/`DialUDP` with the IPv4 dial budget; public IPv6 rejected pre-dial when `ipv6Egress` is false, checked after the DNS redirect; NAT64 `64:ff9b::/96` exempt. Kotlin refuses Connect for an IPv6 profile DNS server without `ipv6Egress` | Gateway if it has IPv6 WAN; else RST/drop so apps can use tunneled IPv4 |
| ICMPv6 echo | Dropped | No gateway/Internet request is made |
| IPv6 over MTU | Local ICMPv6 Packet Too Big | No gateway request |
| IPv4 over MTU | Local ICMP Fragmentation Needed when DF is set; otherwise dropped | No gateway request |
| IPv4 ICMP echo | Dropped like ICMPv6 (a local reply would fake reachability) | No gateway/Internet request is made |
| Native exit audit | TLS/HTTP through `Client.DialTCP` | Tailcat gateway |
| In-app speed test | When CONNECTED: `Client.DialTCP` through the gateway (`speed.cloudflare.com` resolved with DNS-over-TCP via `Client.DialTCP` to `1.1.1.1:53`). Otherwise `HttpURLConnection` on the device's current routes | Gateway TCP when CONNECTED; otherwise whatever route the device uses (direct when disconnected, into the TUN while it is still up in DEGRADED/RECONNECTING). |

## Active implementation focus

1. Phase 5 dual-stack: client `ipv6` promoted with prepare-time egress probe and
   fail-closed public IPv6 when the gateway has no IPv6 WAN. Gateway IPv6 WAN
   remains a deployment dependency for true dual-stack egress.
2. Phase 6 promotion: two-phase start exists; promote `twoPhaseStart` and
   `cancelSafeLifecycle` only after the evidence in this guide.
3. Phase 4/7 promotion: keep DNS preserve and telemetry honesty; promote `dns`
   and `liveStats` only after the evidence in this guide.
4. Phase 8: Pass physical-device uplink packet capture, multi-interface
   handover, leak verification, and production signing gates.

Only promote remaining capabilities after the evidence listed in this guide
passes.

## Scope

OpenTailcat is an initiating Android client. It does not own gateway NAT,
WARP/Tor selection, or filtering policy. Do not build a gateway listener into
the Android app.

Data-plane interoperability still requires a compatible gateway. Tailcat
v0.4.0 `serve exit-node` was TCP-only; signed v0.7.0 (2026-09-16) adds UDP
forwarding through `--serve=exit-node`. The submodule is pinned to that
signed `v0.7.0` tag, which exports `Client.DialUDP` and `Server.OnUDPForward`. If the live user-controlled Tailcat gateway
already supports native tunneled UDP, prove and version that capability.
Otherwise a matching gateway-side Tailcat UDP deployment is required. A
client-only direct socket is never an acceptable substitute.

## Upstream provenance

`third_party/tailcat` is a git submodule of
`https://github.com/tailscale/tailcat`, pinned to the unmodified signed
`v0.7.0` tag (`15ab9e68bfc6534a61797d7af28cedd42b54a3a5`). See
`third_party/PROVENANCE.md`. Do not
add OpenTailcat patches in the submodule. Android supplies LinkProperties and
the underlying default interface via `updateNetworkState`; the engine hands
the interface to netmon and calls `InjectEvent` on the client's monitor
(reached by type-checked reflection in `netmon_hook.go`, since upstream has no
accessor), so a network switch rebinds Magicsock. Android follows the application
default and, on API 31+, the best matching non-VPN network callback. Older
Android uses its reported transport or a sole candidate; ambiguous underlays
remain unknown instead of being ranked. Live roaming evidence still belongs
to Phase 6.

Dependency updates are applied by hand rather than by a bot: a Go module or
submodule bump changes the compiled AAR, so it has to land together with a
rebuild and the gate evidence above. Dependabot version updates and its
security-update PRs are disabled for this repository.

## Required implementation order

1. [x] Phase 0: Restore fail-closed capability negotiation.
2. [x] Phase 1: Make upstream/native provenance and AAR builds reproducible.
3. [x] Phase 2: Align Kotlin and Go token parsing and reject connect-time legacy tokens lacking disco keys.
4. [x] Phase 3: Add native Tailcat UDP using userspace netstack; delete application-flow `net.DialUDP`.
5. [ ] Phase 4: DNS routing code exists; IPv4 `dns` is test-enabled until Phase 8 evidence.
6. [x] Phase 5: IPv6 TCP/UDP proxied with `::/0` after pumps; `ipv6` capability true;
      session `ipv6Egress` + fail-closed HE fix. Gateway IPv6 WAN still required for real IPv6 internet.
7. [ ] Phase 6: Cancellable machine and two-phase routes exist; live roam/leak evidence pending.
8. [ ] Phase 7: Schema and live `DiscoPing` RTT exist; WG counters are unavailable; `liveStats` is test-enabled until Phase 8 evidence.
9. [ ] Phase 8: Pass automated, local-gateway, physical-device, packet-capture, signing, 16 KB, R8/JNI, SBOM, and license gates.

Checkpoint status:

- Phase 0 — complete: incomplete native behavior fails closed.
- Phase 1 — complete: provenance and deterministic native builds verified.
- Phase 2 — complete: Kotlin and Go share the strict upstream-compatible token contract.
- Phase 3 — implementation complete: native userspace netstack UDP proxy using upstream `Client.DialUDP` / `OnUDPForward`; physical-device live acceptance pending; IPv4 `udp` is test-enabled.
- Phase 4 — DNS routing code exists: pending DNS is stored before attach and applied on `attachTun`. Absent `dnsPolicy` in later `updateNetworkState` does not reset policy. `GATEWAY_RESOLVER` is unused (treated as PROFILE). The engine does not inspect DNS TC bits. IPv4 `dns` is test-enabled.
- Phase 5 — IPv6 TCP/UDP proxied (public IPv6 is rejected before dial when `ipv6Egress` is false, otherwise IPv6 uses the IPv4 dial budget (15 s TCP / 10 s UDP)); ICMPv6 echo dropped; oversized IPv6 gets Packet Too Big; Android installs `::/0` after pumps are live. `ipv6` is true; `ipv6Egress` is session-measured.
- Phase 6 — session context, short mutex, always-Close previous client, readiness barriers, pump-exit `FAILED` + `healthUnixSec`, bounded `Stop`, `DetachTun`, `DisarmPumps`. After `prepare`, Android establishes a host-only TUN (no VPN DNS), attaches, `detachTun`, then installs `0.0.0.0/0`/`::/0` with VPN DNS and reattaches. Sticky VPN service; TUN closed before native `stop`. IPv4 test-routing enables `twoPhaseStart` and `cancelSafeLifecycle`.
- Phase 7 — telemetry code exists: schema version 2. RTT is sampled from `DiscoPing` about every 5s while a bridge is running; jitter is null until three samples. WireGuard peer Tx/Rx stay 0 because upstream `Client` has no Status API (the never-set `lastHandshakeSec`, `directEndpoint` and `wireguardTx/RxBytes` fields were removed). Kotlin requires version 2, does not synthesize missing `state` as `RUNNING`, and CONNECTED requires live `RUNNING` + fresh `healthUnixSec`. `liveStats` is test-enabled.
- Phase 8 — host gates and Wireshark/tshark pcap analyzer exist (`scripts/phase8`, `cmd/phase8-analyze`). Physical dual-capture on ARM64 and production signing remain. `ipv6` is true; `ipv6Egress` is session-measured.

### Phase 0: restore fail-closed behavior

Complete this before any networking refactor:

1. Change native capabilities so the current AAR does not satisfy Android's
   production gate. At minimum `dataPlane` must be false. Prefer an API v2
   object with explicit fields such as `ipv4`, `ipv6`, `tcp`, `udp`, `dns`,
   `liveStats`, and `cancelSafeLifecycle`, all false until tested.
2. Update `TunnelEngine.kt` so a production connection requires the complete
   capability set for the routes it will install. Unknown fields or older API
   versions fail closed.
3. Keep the Meow handshake and parser available to tests, but do not create a
   default-route VPN from partial capabilities.
4. Add a unit test asserting the current incomplete engine is unavailable to
   Android. Remove the existing test that merely asserts all booleans are true.
5. Remove or quarantine downloadable APK claims until the release matrix below
   passes.

Acceptance condition: pressing Connect cannot install a default route with the
currently incomplete engine.

### Phase 1: make provenance and builds reproducible

1. Preserve the local Tailcat delta explicitly. Either:
   - restore `third_party/tailcat` as a submodule pinned to a named fork commit;
     or
   - keep the embedded tree and add a patch/provenance file containing the
     upstream base, local commit, rationale, and exact diff.
2. Do not describe `49c65da` as the untouched signed tag. It is one local commit
   after `v0.4.0`.
3. Review the embedded static DERP map. Treat endpoint data as versioned and
   expiring; a network-fetch failure must not silently select indefinitely stale
   relay addresses without an observable warning.
4. Replace the fabricated `android0`/`10.0.2.15` interface fallback with an
   Android-provided network-state bridge or another upstream-supported monitor.
   A hard-coded emulator address cannot represent Wi-Fi/cellular roaming.
   **Done in tree:** that fabricated interface is gone. Android supplies
   LinkProperties and the underlying default interface (`defaultInterface`)
   via `updateNetworkState`. Upstream `Client` has no netmon accessor, and
   Android's netmon only polls every 10 minutes, so the engine reads the
   client's monitor from `Client.lb.sys.NetMon` with type-checked reflection
   (`netmon_hook.go`; a test fails if the layout changes), passes the default
   interface to `netmon.UpdateLastKnownDefaultRouteInterface`, and calls
   `InjectEvent`, which makes the engine rebind Magicsock and re-STUN on a
   major change. Live Wi-Fi/cellular roaming evidence remains Phase 6. DERP maps are fetched from upstream and sanitized by
   `derp_filter.go`; upstream also has an in-memory fetch cache.
5. Align CI to Go 1.27 and pin Go Mobile/NDK versions. Do not depend on an
   implicit toolchain auto-download from a Go 1.24 CI bootstrap.
6. Build with supported reproducible-path/`-trimpath` settings so developer
   workstation paths are not embedded. Verify that stack traces and symbol
   handling remain useful under the chosen release policy.
7. Add a CI task that rebuilds the AAR and fails when the checked-in AAR differs
   from source. Record `sha256`, `go version -m`, ABIs, Java signatures, and ELF
   alignment as artifacts. A single host reproduces the archive byte for byte;
   across hosts gomobile's cgo glue and the NDK linker change the bytes and the
   Go build ID, so CI compares the module list, exported Java API, and the
   `sourcehash` instead.

Acceptance condition: a clean checkout deterministically produces an AAR with
the expected API, dependencies, ABIs, and 16 KB alignment.

### Phase 2: unify the token contract

**Checkpoint complete.** The canonical read-only fixture corpus is
`core-engine/testdata/token_fixtures.json`; it contains 90 deterministic cases.
`go run ./cmd/generate-fixtures` is the only fixture-generation path and uses
fixed key material. Both parsers reject whitespace and Base64URL padding,
accept only canonical upstream field names, preserve accepted token bytes, and
reserve `LEGACY_REISSUE_REQUIRED` for historical numeric-`r` tokens. Valid
official fixtures are additionally checked with upstream `ParseConnBlob`.

Use upstream `tailcat.ParseConnBlob` as the authority for official tokens. The
Kotlin parser is an early UX/security check, but native validation remains
mandatory.

Required behavior:

- Official short token: exact `p` node key, exact `k` disco key, positive `i`.
- Official resolved token: exact `p`, exact `k`, non-empty structured `r`.
- Reject an official connection token without `k`; do not set `k = p`.
- If legacy numeric `r` tokens remain parseable for migration, return a
  specific `LEGACY_REISSUE_REQUIRED` result and never attempt a connection.
- Enforce duplicate CBOR key rejection before decoding into a Go map. Include
  exact duplicate keys as well as aliases.
- Reject trailing CBOR objects, indefinite/oversized structures beyond explicit
  limits, fractional timestamps, overflow, `exp < iat`, and expired tokens.
- Make Kotlin and Go identical on prefix case, Base64URL padding, aliases,
  unknown fields, maximum token size, and timestamp semantics.
- Do not mutate an official token while validating it. Canonicalization is only
  for a schema that can be reproduced without inventing cryptographic fields.
- Embedded DERP maps allow only region fields `i`/`c`/`m`/`N` and node fields
  `n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`. Node `x` (`InsecureForTests`) is rejected.
  Loopback, unspecified, link-local, and multicast `h`/`4`/`6` values are rejected.

Tests must include byte-for-byte golden vectors produced by the pinned upstream
version, malformed vectors, duplicate-map-key vectors, and cross-language
fixtures consumed by both Kotlin and Go.

Acceptance condition: Kotlin and Go return the same classification for every
fixture, and every token accepted for connection starts an upstream client
without synthetic key material.

### Phase 3: implement tunneled UDP end to end

**Checkpoint status: Implementation complete; live physical acceptance pending.**
All native proxy components, upstream `Client.DialUDP`, gateway `OnUDPForward`,
`AllowProxy` policy filtering, zero-length preservation, and `udpWg` shutdown synchronization have
been implemented and unit/integration tested. Physical device uplink packet-capture and live gateway
audit remain the required live acceptance gate.

#### Preferred upstream client extension

Extend the embedded/forked Tailcat client using its existing Tailscale netstack:

1. Replace the client-side `NetstackDialUDP` panic with a wrapper around
   `ns.DialContextUDP` or `DialContextUDPWithBind`, following the nil-interface
   handling pattern used by `tsnet`.
2. Add an exported `Client.DialUDP(ctx, netip.AddrPort)` method analogous to
   `DialTCP`.
3. For an IPv4 destination, map the address into the same NAT64/4-in-6 form used
   by `DialTCP`, so packets traverse Tailcat's IPv6-only WireGuard peer.
4. Preserve one UDP datagram per `Write`/`Read`; do not use `io.Copy` as a stream.
5. Bind source addresses/ports deliberately where reply routing requires it and
   document any NAT behavior.

#### Gateway side

For an official Tailcat-based gateway:

1. Add an explicit exit-node UDP capability, rather than widening filters for
   every server mode.
2. In exit-node mode, admit UDP to permitted destinations in the packet filter.
3. Use Tailscale netstack's UDP forwarding path or an `OnUDPForward` policy hook
   to open the gateway-side OS socket and copy datagrams bidirectionally.
4. Apply the same `AllowProxy`/destination policy to TCP and UDP. Reject
   loopback, link-local, multicast, metadata-service, or private destinations as
   required by gateway policy; do not embed those policy choices in Android.
5. Advertise a versioned UDP capability. Current `prepare` accepts TCP-only
   gateways (`tcpOnly` session: DNS over TCP, other UDP dropped) rather than
   failing before TUN creation.

If the existing gateway is not built from this tree, implement only the client
portion after confirming its already-deployed protocol provides equivalent UDP
semantics.

#### Android TUN side

Prefer one gVisor IP stack for both TCP and UDP. Add the UDP transport protocol
and a `udp.NewForwarder`, create a connected gVisor UDP endpoint per flow, and
proxy datagrams to `Client.DialUDP`. This lets gVisor own IP checksums,
fragmentation/reassembly, ICMP errors, and MTU behavior instead of manually
constructing reply packets.

Every UDP flow table must be:

- keyed by address family, protocol, source address/port, and destination
  address/port;
- bounded globally and per source, with a defined rejection policy;
- cancellation-safe and closed exactly once;
- protected from data races (`lastActive` is an atomic);
- equipped with idle deadlines and maximum datagram sizes;
- backpressured so unbounded goroutines cannot be created by packet input; and
- tested for concurrent close, late replies, port reuse, zero-length datagrams,
  truncation, and full 65,507/65,527-byte protocol limits where supported.

Delete every application-flow `net.DialUDP` from `core-engine`. Direct OS UDP is
allowed only inside upstream Magicsock/DERP transport code or on the gateway's
egress side.

Acceptance condition: QUIC/HTTP3, UDP echo, and a non-DNS UDP test all work with
the client device's direct UDP to the destination blocked. Packet capture shows
only Tailcat transport between client and gateway.

### Phase 4: make DNS policy truthful

**Checkpoint status: Routing code exists; IPv4 `dns` is test-enabled until Phase 8 evidence.**

#### Implementation details

1. **Native DNS destination resolution (`core-engine`):**
   - Implemented `DNSConfig` on `TunBridge` (`Policy`: `"PROFILE_RESOLVER"` or `"FORCED_RESOLVER"`, `ForcedDNS`: `netip.AddrPort`).
   - Integrated `resolveDNSDestination` into `netstackProxy` for both `acceptUDP` and `acceptTCP`:
     - Under `PROFILE_RESOLVER` (default), preserves the destination IP from the TUN datagram verbatim and forwards it through `Client.DialUDP` or `Client.DialTCP`.
     - Under `FORCED_RESOLVER`, redirects port 53 queries exclusively to the configured `ForcedDNS` endpoint.
     - Any other policy string, including Kotlin `GATEWAY_RESOLVER`, is treated as `PROFILE_RESOLVER`.
     - Preserves full datagram boundaries up to 65,535 bytes to prevent truncation of large EDNS0 / DNSSEC responses. Bridge MTU is profile-driven (`updateNetworkState` `tunnelMtu`, clamped 1280–1500) and matches `VpnService.Builder`.
   - The engine does **not** inspect DNS TC bits. `TestDNSTruncationAndTCPRetryFallback` forwards the TC=1 UDP answer, then the test itself calls `DialTCP`. If Android/libc retries over TCP/53, that flow is a normal TCP proxy.
   - Do not promote `dns` until the evidence in the capability table exists.

2. **Android DNS validation and policy (`app`):**
   - Created `DnsValidator` with strict IPv4 and IPv6 validation. Rejects loopback (`127.0.0.0/8`, `::1`), multicast (`224.0.0.0/4`, `ff00::/8`), broadcast (`255.255.255.255`), unspecified (`0.0.0.0`, `::`), leading-zero octets, hostnames, URLs, and ports.
    - `DnsPolicy` is `PROFILE_RESOLVER` or `FORCED_RESOLVER` (legacy
      `GATEWAY_RESOLVER` migrates via `fromString` to `PROFILE_RESOLVER`).
      Add-profile dialog and Settings "Active profile DNS" expose radios for
      profile vs forced; Settings default DNS is a free-form IP (examples
      1.1.1.1, 9.9.9.9).
   - Added `PreferencesStorage` interface and `defaultDns` setting.
   - Integrated DNS validation and policy persistence in `ProfileRepository` (`addOrUpdateFromToken`, `updateProfileDns`) with fallback for corrupt legacy data. Saved profiles are parsed one entry at a time: a corrupt entry is dropped on its own, and only an unreadable list yields no profiles. Profile list and active-profile writes commit synchronously, so a process killed right after a delete cannot bring the profile back.
   - Enforced DNS validation in `TailcatVpnService` before calling `Builder.addDnsServer`. Native omit-means-preserve keeps pending DNS across roam `updateNetworkState` payloads that lack `dnsPolicy`.
   - Updated UI in `HomeScreen` (Add Profile dialog) and `SettingsScreen` (Defaults card) with real-time validation error feedback.

3. **Automated test coverage:**
   - Go (`core-engine/dns_test.go`): `TestDNSTransactionIDPreservation`, `TestDNSParallelQueries`, `TestDNSEDNS0AndLargeResponses`, `TestDNSTruncationAndTCPRetryFallback`, `TestDNSConfiguredPolicyAndDestinationMatching`, `TestDNSIPv4AndIPv6Resolvers`, `TestDNSTimeoutAndCancellation`, and `TestDNSLeakPrevention`.
   - Android (`app/src/test`): `DnsValidatorTest` (IPv4, IPv6, invalid octets, leading zeroes, loopback, broadcast, multicast, hostnames), `ProfileRepositoryTest` (valid creation, forced policy, rejection of invalid IPs, updating profile DNS, JSON persistence roundtrip, a corrupt entry dropped without losing the others).

Acceptance condition: configured policy and observed resolver destination match, both UDP and TCP DNS leave through the gateway, later network-state updates preserve policy, and leak tests pass. Only then may `dns` become true.

### Phase 5: complete or deliberately block IPv6

**Checkpoint status: IPv6 TCP/UDP proxied; `ipv6` capability true; prepare-time `ipv6Egress` probe with fail-closed public IPv6 when the gateway lacks IPv6 WAN.**
Android installs `100.64.0.2/32` and `fd7a:115c:a1e0::2/128` on a warm TUN, then
`0.0.0.0/0` and `::/0` after pumps are live. `handleIPv6` injects TCP/UDP into
gVisor; ICMPv6 is dropped. When `ipv6Egress` is false, public IPv6 is RST/dropped before DialTCP so
Happy Eyeballs uses tunneled IPv4 (DialTCP alone can succeed to the gateway
before the remote IPv6 dial fails). Live IPv6 internet depends on the gateway.

The release definition requires working IPv6, not silent bypass.

1. Prove the selected gateway can forward IPv6 TCP and UDP to the Internet.
2. Configure an IPv6 TUN address and add `::/0` only after native and gateway
   IPv6 capabilities are confirmed.
3. Ensure gVisor handles IPv6 extension headers and fragmentation. Add explicit
   tests for fragment headers, large UDP, ICMPv6 Packet Too Big, and PMTU.
4. If a gateway has no IPv6 WAN, either provide a documented translation policy
   at the gateway or mark it incompatible with full dual-stack mode. Never omit
   `::/0` and imply there is no leak.
5. During development, a safe IPv4-only mode may route `::/0` to a native drop
   path so IPv6 fails closed, but that mode is not the full release definition.

Acceptance condition: public IPv4 and IPv6 both change to gateway egress while
connected, and neither family reaches the Internet directly on pump failure.

### Phase 6: lifecycle, readiness, and roaming

Decision (2026-09-28, review item 18): the warm-TUN two-phase start stays.
Routing `0.0.0.0/0` and `::/0` into a TUN before its pumps attach would be
fail-closed (packets queue or drop, they do not leak), and one establish after
`prepare` would drop a detach/reattach cycle. But it would install default
routes for an engine that has not yet proven live pumps, which invariant 1
forbids; the host-only warm attach is that proof.

Startup crash correction (2026-09-05): the controller checks Android VPN consent
before requesting a foreground service, and the service checks it again before
starting native work. Foreground-promotion exceptions are handled. A rejected
service request posts a transient `shortService` notification on API 34+ and
then stops; this satisfies Android's foreground-start contract even when VPN
consent is absent. `shortService` never runs the tunnel. Failed starts clear
`vpnWanted` so Activity/application restoration does not repeatedly retry them;
an explicit Connect request can retry after permission is granted. The
dedicated-emulator `VpnStartupInstrumentedTest` uses synthetic keys and checks
missing consent, service rejection, process survival, retry, and cleanup. This
does not establish physical-device or encrypted data-plane acceptance.

**Checkpoint status: Machine exists; IPv4 test-routing enables `twoPhaseStart`
and `cancelSafeLifecycle`.** Session context cancels blocked `prepare`. Mutex is
not held across Ping/DiscoPing. A second `prepare` closes a previous unattached
client. `attachTun` waits for TUN read, gVisor output, UDP, and health loops
to enter. Required pump exit sets `FAILED` and clears `healthUnixSec`. `Stop` is
bounded and concurrent-idempotent. `DetachTun` stops pumps and returns to
`PREPARED`. `DisarmPumps` clears pump-failure without stopping the session.
After `prepare`, Android establishes a host-only TUN (no VPN DNS), attaches pumps,
`detachTun`, then a routed TUN with `0.0.0.0/0`/`::/0` and VPN DNS. The VPN service is
`START_STICKY` with `stopWithTask=false`. Shutdown closes the TUN before native
`stop`. CONNECTED requires native `RUNNING` plus fresh `healthUnixSec`. Unknown
capability JSON fields fail closed.

Refactor the global engine into a synchronized state machine:

```text
STOPPED -> PREPARING -> PREPARED -> ATTACHING -> RUNNING
    ^          |            |           |          |
    +----------+------------+-----------+----------+
                     STOPPING / FAILED
```

Implementation requirements:

- Do not hold the global mutex during network I/O or while waiting for pumps.
- Give `prepare` a session context stored in the engine. `stop` cancels it so a
  JNI call in progress can return promptly.
- A second `prepare` always closes a previous prepared client, even if no TUN
  was attached.
- `attachTun` uses readiness barriers for TUN read, gVisor output, UDP, and
  monitoring loops. Closing a channel immediately before entering a loop is not
  proof that both packet directions are usable.
- Unexpected exit of any required pump transitions the engine to `FAILED` and
  triggers immediate Android teardown (TUN closed, engine stopped). While the
  user still wants the VPN, the service then reconnects with `StartRetry`
  backoff; a gateway silent for 60 s (`lastDiscoOkUnixSec`) is handled the same
  way. There is no fail-closed TUN between attempts (lockdown required).
- Descriptor ownership is explicit: Go duplicates the supplied FD; Android
  owns the original; each side closes only its descriptor.
- `stop` is idempotent under concurrent calls and waits with a bounded timeout.
- Wipe references to tokens/private keys when stopping; acknowledge that Go
  garbage collection limits guarantees about physical memory erasure.
- Feed Android network changes into Tailcat/Magicsock. Do not substitute a
  fabricated emulator interface. Re-evaluate endpoints and direct/DERP paths on
  Wi-Fi/cellular changes.
- Android must not return to `CONNECTED` merely because `getStatsJSON` parsed.
  It requires a fresh native `RUNNING` health timestamp and live pumps.

Acceptance condition: cancellation during every startup stage and repeated
connect/disconnect/process-recreate tests leave no TUN, route, socket, or
foreground service behind.

### Phase 7: authoritative telemetry

**Checkpoint status: Schema and live `DiscoPing` RTT exist; WireGuard counters are unavailable; `liveStats` is test-enabled until Phase 8 evidence.**

Add an upstream client status surface based on the live WireGuard engine and
Magicsock status. Prefer extending `Client` with a read-only status method
analogous to the existing server `Status()`.

Current code reports schema version 2 with:

- engine state includes `STOPPED` / `PREPARING` / `PREPARED` / `ATTACHING` / `RUNNING` / `STOPPING` / `FAILED`; marshal-failure stub uses `ERROR`;
- monotonic session ID;
- WireGuard peer TX/RX stay 0: upstream `Client` has no Status API. Do not invent counters;
- TUN accepted/dropped counters distinct from WG. `txBytes`/`rxBytes` are WireGuard peer counters only (never a TUN fallback); `tunTxBytes`/`tunRxBytes` count each packet once as it crosses the TUN, and locally generated ICMP errors are not counted as received;
- live `DiscoPing` RTT about every 5s while a bridge is running; failed pings are skipped; Endpoint set => DIRECT_P2P else DERP_RELAY;
- jitter only after ≥3 `RecordRTT` samples, otherwise `null`. The formula is mean absolute consecutive difference, not RFC 3550 `J := J + (|D|-J)/16`;
- packet/drop counters for TCP, UDP, DNS, malformed IP, MTU, queue exhaustion, and policy rejections;
- exit-audit IP plus timestamp and error state;
- DERP names from the token's embedded region when present; otherwise empty (not invented).

Kotlin `NetworkMetrics.fromJson` requires schema version 2, rejects v1 and missing version, and leaves missing `state` empty. `onEngineConnected` requires `RUNNING` plus fresh `healthUnixSec`.

Do not promote `liveStats` until live RTT sampling exists, jitter matches the documented formula, Kotlin rejects incompatible/v1 telemetry, unknown values stay absent, and staleness is enforced when the data plane stops.

When CONNECTED, the in-app speed test uses native `MeasureTunnel*` (`Client.DialTCP`);
`speed.cloudflare.com` is resolved with DNS-over-TCP via `Client.DialTCP` to
`1.1.1.1:53`. When not CONNECTED, it uses `HttpURLConnection` on the device's
current routes (the app does not exclude its own UID, so this still enters the
TUN while it is up). Do not present that path as tunnel performance.

Acceptance condition: telemetry changes during forced DERP/direct transitions,
matches packet captures/status counters within documented accounting rules, and
becomes stale/failed when the data plane stops.

### Phase 8: testing and release engineering

#### Automated unit and race tests

- Run `go test -race ./...` on host-testable packages.
- Token cross-language golden/malformed corpus.
- UDP flow table bounds, datagram boundaries, deadlines, races, cancellation,
  and cleanup.
- IPv4/IPv6 packet and MTU cases.
- Engine state-machine transitions and concurrent `prepare`/`attachTun`/`stop`.
- Telemetry schema, unknown/null values, monotonic counters, and staleness.
- Kotlin capability negotiation and service teardown paths.

#### Local integration harness

Run a compatible Tailcat gateway plus controlled TCP, UDP, DNS, HTTP3, and
IPv6 echo services. Tests must prove both success through the gateway and
failure when the gateway capability is absent. Avoid embedding live tokens in
source or logs.

The Android instrumentation suites cover pairing, VPN startup rejection, and
live-gateway scenarios through `scripts/emulator/`. Extend that evidence with
physical-device traffic from a second UID and route/service cleanup checks;
emulator results do not establish physical acceptance.

#### Live device matrix

Test API 26 and current Android on at least one physical ARM64 device:

- handshake success and failure;
- IPv4/IPv6 TCP;
- generic UDP and QUIC/HTTP3;
- DNS UDP/TCP and large responses;
- direct Magicsock and forced DERP fallback;
- Wi-Fi -> cellular -> Wi-Fi roaming;
- captive/offline transitions;
- screen off/doze;
- repeated connect/disconnect;
- service revoke and process death;
- split-tunnel exclusions;
- Always-on VPN and Block connections without VPN; and
- MTUs 1280 through 1500, including large transfers.

For leak tests, capture simultaneously on the Android uplink and gateway with
Wireshark/tshark (classic pcap, not pcapng). PCAPdroid cannot be the tap: it
is a second VPN. Capture the phone’s Wi-Fi hop on the AP/next hop, or
`tcpdump`/`tshark` on a rooted `wlan0`. On macOS, `tshark -D` lists
interfaces; empty capture lists need `brew install --cask wireshark-chmodbpf`.

```bash
# Host automated gates (not a leak pass): -race, unit, lint, AAR hash +
# sourcehash, and synthetic phase8-analyze e2e (PASS/FAIL/fail-closed).
scripts/phase8/run-host-gates.sh
# Analyzer-only synthetic e2e (also in CI on every push):
scripts/phase8/e2e-analyze.sh

# Phone connected + Always-on lockdown. Start BOTH captures first (classic
# pcap, 128-byte headers only, BPF-filtered to the phone / probe IPs). The
# uplink capture must see the phone's own frames: Mac Internet Sharing
# (bridge100, the default), an AP/switch mirror port, or a rooted phone's
# wlan0 (CAPTURE_TOPOLOGY=internet-sharing|mirror|rooted-wlan0). A Mac's en0
# on a shared Wi-Fi LAN does not see another device's unicast traffic.
DEVICE_IP=192.168.2.2 CAPTURE_SECONDS=45 scripts/phase8/capture-uplink.sh captures/uplink.pcap &
CAPTURE_IFACE=eth0 CAPTURE_SECONDS=45 scripts/phase8/capture-gateway.sh captures/gateway.pcap &
# Probes come from a browser app's uid (VIEW intents), not adb shell or the VPN app.
scripts/phase8/generate-probes.sh   # PROBE_IPS / PROBE_ROUNDS / PROBE_PACKAGE
wait
DEVICE_IP=192.168.2.2 TUNNEL_PEERS=<gateway-and-DERP-IPs> \
  scripts/phase8/analyze-uplink.sh captures/uplink.pcap 1.1.1.1,8.8.8.8 captures/gateway.pcap
```

Exit codes: 0 PASS, 1 FAIL, 2 usage or capture error, 3 INCONCLUSIVE (not a
pass). The analyzer has a positive control: the uplink capture must hold at
least 5 packets from `DEVICE_IP` to a `TUNNEL_PEERS` address in the probe window, and the two
captures must overlap in time (2 s skew allowed), or the result is
INCONCLUSIVE. Plain DNS (port 53) from the phone to anything but a tunnel peer
is a FAIL; the gateway's own DNS to a probe address never counts as a probe
hit (`GATEWAY_SRC` narrows gateway matches to the tunnel-side source). Open
question: Android's connectivity checks and DERP hostname lookups may send
plain DNS outside the tunnel and fail a real capture under this rule; no
allow-list exists yet.

Pass: probe destinations are absent on the uplink pcap and present on the
gateway pcap. Uplink may contain only Tailcat/WireGuard/DERP (and Magicsock
sockets protected via `VpnService.protect`). Force each native pump to fail
and confirm routes are removed or Android lockdown blocks traffic.

Split-tunnel (excluded-app) evidence is the inverse: excluded UID probes must
appear on the uplink pcap and be absent from the gateway pcap.

Do not commit pcaps or live tokens. `ipv6` is true on the client; treat `ipv6Egress` and Phase 8 dual
capture as the honesty bar for public IPv6 egress. Host analyzer unit tests and
`e2e-analyze.sh` synthetic fixtures are tooling checks, not Phase 8 acceptance.

The 2026-09-23 dual-capture run log is in
[`docs/history/handoff-2026-09.md`](docs/history/handoff-2026-09.md).

#### Release artifacts

1. Rebuild the AAR from the audited source and archive its metadata/hash.
2. Run unit, lint, static, integration, physical-device, 16 KB alignment, and
   R8/JNI tests.
3. Generate the current Gradle outputs; do not assume old ABI-split filenames.
4. Sign with a private production key held outside the repository.
5. Verify APK/AAB signatures, package/version, embedded AAR ABIs, SBOM, and
   third-party licenses.
6. Publish checksums and release notes that list only verified behavior.

## Capability promotion rules

Capabilities are evidence-backed release switches, not developer assertions:

| Capability | May become true only after |
| --- | --- |
| `twoPhaseStart` | Cancellation and readiness tests prove no route before handshake and live pumps after attach |
| `wireGuard` | Live gateway traffic and authoritative peer status prove encrypted data-plane use |
| `magicsock` | Direct and forced-DERP paths plus roaming are observed and telemetry updates |
| `tcp` | IPv4 and IPv6 TCP integration/large-transfer tests pass |
| `udp` | Generic UDP/QUIC, gateway capture, bounds, MTU, and leak tests pass |
| `dns` | Configured resolver policy, UDP/TCP fallback, large response, and leak tests pass |
| `ipv4` | Default-route and public-egress tests pass with failure teardown |
| `ipv6` | `::/0`, public IPv6 egress, PMTU, and no-bypass tests pass |
| `liveStats` | Counters/path/health are sourced from live engine state and staleness is enforced |
| `dataPlane` | Every capability required by the shipped routing mode is true |

Android must evaluate individual capabilities and the route set together. Do
not collapse partial support into a single optimistic boolean.

## IPv6 remaining blockers (capability true; gateway WAN still required)

Client `ipv6` is true with fail-closed HE behavior. Real IPv6 Internet still needs:

1. Gateway / WARP path with working IPv6 WAN (`ipv6Egress=true` in stats).
2. Connected Always-on session with `::/0` installed after pumps are live (already implemented).
3. Second-UID IPv6 TCP/UDP probe succeeds only via gateway; simultaneous uplink+gateway classic PCAPs pass phase8-analyze (H7 fail-closed).
4. PMTU / Packet Too Big path exercised; ICMPv6 echo remains local-drop.
5. Gateway under test has IPv6 WAN. Without it, the client rejects public IPv6 before dialing (RST/drop) so apps fail fast to tunneled IPv4; the old 250 ms IPv6 dial timeout was removed.

Device checklist: enable Always-on lockdown, rebuild AAR (`core-engine/build-aar.sh` with Go 1.27.3 + NDK 29.0.14206865), install development APK, confirm lockdown in Android VPN settings, run `scripts/phase8` dual capture including an IPv6 probe address.

## Native API

The current AAR exports `com.tailcat.golib.engine.Engine`:

```text
getCapabilitiesJSON() -> String
prepare(token)
attachTun(tunFd)
detachTun()
disarmPumps()
getStatsJSON() -> String
stop()
updateNetworkState(json)
parseToken(token)
measureTunnelPingMS()
measureTunnelDownloadMbps(SpeedProgress)
measureTunnelUploadMbps(SpeedProgress)
setSocketProtector(SocketProtector)
ensureTransportProtect()
```

Keep the original five methods for Android compatibility while versioning their
payloads. `updateNetworkState` is used by Kotlin. `parseToken` is exported for
the AAR verifier; Kotlin uses its own `TokenParser`. Kotlin's `TunnelEngine`
calls `Engine` directly (no reflection); if the native library cannot load, the
engine reports itself unavailable and Connect stays disabled.

## Current lifecycle

- `prepare` validates an official token, completes a Meow/Meowed handshake, and
  allows TCP-only gateways (DNS over TCP; other UDP dropped). `tcpOnly` is
  decided by STUN binding requests through `Client.DialUDP` to Cloudflare and
  Google STUN (a DNS answer alone does not count, since gateways treat port 53
  specially). While latched it is re-probed every 30 s; while off, 8 UDP flows
  in a row that end without a reply trigger a probe, and a failed probe
  latches it mid-session. Session context lets `stop`
  cancel a blocked `prepare`. Mutex is not held across Ping/DiscoPing.
- A second `prepare` always closes a previous prepared client.
- `attachTun` returns after TUN read, gVisor write, UDP GC, and health loops
  have entered. Required pump exit sets `FAILED` and clears `healthUnixSec`.
- Kotlin sets `CONNECTED` only for native `RUNNING` plus fresh `healthUnixSec`.
- Go duplicates the supplied TUN FD and sets it non-blocking so closing it
  interrupts the reader (the flag is shared with Android's descriptor);
  Android owns the original.
- `stop` is concurrent-idempotent and returns within about 3 s; a slower
  upstream `Client.Close` finishes in the background. Kotlin never calls it
  on the main thread.
- `detachTun` stops pumps, keeps the prepared client, and returns to `PREPARED`.
- `disarmPumps` clears pump-failure without stopping the session.
- Every exported function recovers a panic and returns it as an error
  (`getStatsJSON` returns an `ERROR` state), so a Go panic cannot abort the
  app process. A panic in `prepare` also resets the engine to `STOPPED`. Panics
  in the RTT sampler are logged only; they no longer mark the session `FAILED`.
- After `prepare`, Android establishes a host-only TUN (no VPN DNS), `attachTun`,
  `detachTun`, then a routed TUN with `0.0.0.0/0` and `::/0` plus VPN DNS and
  `attachTun` again. The VPN
  service is `START_STICKY` with `stopWithTask=false`. Shutdown closes the TUN
  before native `stop`. Only the service promotes a session to CONNECTED; on
  resume the UI only restores a VPN the user still wants (`onUiResumed`).
- A mid-session failure (native `FAILED`, 3 stale health polls, 3 failed stats
  reads, or no gateway reply for 60 s per `lastDiscoOkUnixSec`) closes the TUN
  and stops the engine at once, then, while `vpnWanted` is set, the service
  reconnects with `StartRetry` backoff and a RECONNECTING notification that
  names the cause. Between attempts there is no TUN, so without Always-on
  lockdown traffic uses the device network (a fail-closed hold needs prepare
  to run behind routes, which Tailcat's `netns.SetEnabled(false)` prevents).

## Token contract

Official connectable tokens require:

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte server node public key,
  "k": 32-byte server disco public key,
  "q"?: 32-byte WireGuard pre-shared key,
  "i"?: positive DERP region ID,
  "r"?: non-empty array of DERP region metadata
}))
```

Optional canonical `exp`/`iat` timestamps are accepted with identical Kotlin and
Go validation (`exp < iat` and expiry fail closed). Kotlin and Go must agree
exactly on prefix case, padding, aliases, unknown fields, size bounds, duplicate
keys, timestamps, and trailing objects.

Embedded DERP maps allow only region fields `i`/`c`/`m`/`N` and node fields
`n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`. Node `x` (`InsecureForTests`) is rejected.
Loopback, unspecified, link-local, and multicast `h`/`4`/`6` values are rejected.

Legacy numeric `r` tokens without `k` may be recognized only to show a reissue
message. Never set the disco key equal to the node key; the real disco public
key cannot be derived from the node public key.

## Safety invariants

1. Never install `0.0.0.0/0` or `::/0` for an incomplete engine.
2. Never use ordinary client-side OS sockets for traffic claimed to be tunneled.
3. Never set `CONNECTED` until authenticated transport and all packet pumps are
   live and native health is fresh.
4. Never synthesize telemetry, reachability, handshake, speed, or egress values.
5. A local ICMP reply or public Internet probe is not a gateway/data-plane test.
6. Close the TUN immediately after startup, pump, or health failure.
7. Reject invalid/expired tokens in both Android and native code.
8. Do not sign a release with the debug key.
9. The app does not exclude its own UID. Only Tailcat transport sockets are
   protected with `VpnService.protect` and bypass the TUN; other in-process
   HTTP/UDP follows the device routes (into the TUN while it is up). Never
   label in-process traffic "direct" or "tunneled" without checking which
   applies; gateway measurements must use `Client.DialTCP`/`Client.DialUDP`.
10. Keep secrets, live tokens, signing keys, and traffic captures out of source
    control and public logs.

These invariants are the release bar. Default routes are installed only after
pumps are live; a brief window remains while the routed TUN is reattached.

## Definition of done

Entering a current official token and pressing Connect must:

1. authenticate and reach the selected compatible gateway before installing
   any default route;
2. carry arbitrary full-device IPv4 and IPv6 TCP, UDP, and DNS through the
   gateway with direct Magicsock and DERP fallback;
3. expose only measured, current engine state and counters;
4. change public IPv4 and IPv6 to gateway egress only while connected;
5. roam between Wi-Fi and cellular without direct traffic leakage;
6. tear down promptly and completely after every failure, revoke, stop, or
   process lifecycle event; and
7. pass the automated, physical-device, packet-capture, signing, and licensing
   gates above.

An AAR build, successful Meow ping, working TCP, locally answered ICMP, or a
single exit-IP probe is not completion.

## Verification commands

```bash
cd core-engine
go test ./...
go vet ./...

cd ..
# The github/fdroid flavors make the bare testDebugUnitTest, lintDebug and
# assembleDebugAndroidTest names ambiguous; name the variant explicitly.
./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug lintFdroidDebug
./gradlew assembleGithubDebug assembleGithubDevelopment
./gradlew assembleFdroidRelease -PnoAbiSplits
./gradlew bundleGithubRelease

# Emulator (see scripts/emulator/README.md): no-gateway smoke test, and the
# live-gateway checks with an app already paired (or OTC_LIVE_TOKEN set).
./gradlew assembleGithubDebug assembleGithubDebugAndroidTest
scripts/emulator/ci-smoke.sh
scripts/emulator/ci-live.sh
```

After any emulator work, always shut it down and stop the JVM when finished:

```bash
adb -s emulator-5554 emu kill  # repeat for every attached emulator in `adb devices`
./gradlew --stop
```

After native changes, rebuild and inspect `app/libs/libtailcat.aar`, including
Java signatures, ARM64/x86-64 contents, `go version -m`, R8/JNI retention,
SHA-256, and 16 KB ELF load alignment. The checked-in AAR must never lag native
source. `build-aar.sh` writes `app/libs/libtailcat.aar.sourcehash` from the
`core-engine` + `third_party` file tree; CI fails if that hash does not match
the current tree.

```bash
# Inspect native API/ABIs after rebuilding
unzip -l app/libs/libtailcat.aar
# Extract classes.jar to a temporary directory, then:
javap -classpath classes.jar com.tailcat.golib.engine.Engine
```

## Instructions for the next implementation agent

1. Read this guide and the affected source before editing.
2. Phases 0–3 are complete in tree. Continue from remaining Phase 4–8 evidence
   work. Do not promote remaining capabilities or claim production readiness
   without the evidence in this file.
3. Preserve unrelated working-tree changes and never replace verified upstream
   Tailcat behavior with a new WireGuard, DERP, or discovery implementation.
4. Work phase by phase. At each checkpoint report changed files, exact tests,
   untested assumptions, binary/AAR rebuild state, and which capabilities remain
   false.
5. Do not mark a phase complete because code compiles. Its acceptance condition
   and negative/leak tests must pass.
6. Do not sign, publish, upload, or install a production artifact without an
   explicit maintainer request and the corresponding release gates.
7. IPv4 `udp` is test-enabled. If live gateway UDP cannot be proven, do not
   treat Phase 3 as physically accepted; report the external compatibility
   blocker. Do not revert to application-flow `net.DialUDP`.

## Documentation rule

README, privacy, security, third-party notices, UI copy, notifications, and
release notes describe verified shipped behavior only. Planned work belongs in
this guide and must be labeled unimplemented.
