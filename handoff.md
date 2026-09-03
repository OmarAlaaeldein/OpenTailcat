# OpenTailcat implementation handoff

This document is the authoritative continuation plan for the Android client.
It separates current behavior from intended behavior and is written so another
engineer or coding agent can implement the remaining work without repeating the
unsafe shortcuts already found in the tree.

## Audited snapshot

- Android repository: version 1.1.1 on `main`, after reverting over-promoted
  capability flags. Every unproven capability is false.
- Safe Android-shell checkpoint: `e475abc`.
- Phase 0 fail-closed checkpoint: `877942a`.
- Phase 1 reproducible-build checkpoint: `76563c9`.
- Phase 2 unified token contract checkpoint: `dfce360`.
- Phase 3 tunneled UDP data plane implementation complete; live physical acceptance pending; `udp` remains false.
- Phase 4 DNS routing and validation code exists; `dns` remains false until promotion evidence.
- Phase 7 telemetry schema and WireGuard counters exist; `liveStats` remains false until promotion evidence.
- Upstream Tailcat base: signed `v0.4.0`, commit
  `ce6fedcabc220bab3b94d470ab330219111eeae8`.
- Embedded Tailcat source: base plus local commit
  `49c65dace2d79b41d89f536289002816d13e5274` and later UDP, DNS, NetMon, and status extensions.
- Native binary: `app/libs/libtailcat.aar`, ARM64 and x86-64, built
  reproducibly with Go 1.27.0 and NDK 29.0.14206865. Current SHA-256:
  `db5d9acdea958a2327cbd4f6fa60408eb18227f6a3f7d8c6b947fd02c7347e31`.
- ARM64 and x86-64 ELF load segments are 16 KB aligned.
- Audit verification passed: `go test -race ./...`, `go vet ./...`, Android unit
  tests, lint with zero errors, `assembleRelease`, and `bundleRelease`.

Passing these build checks is not a data-plane release gate. No current test
establishes a full Android VPN or proves leak-free traffic.

## Release status

The current tree is a development prototype with verified token parsing and a
userspace netstack UDP proxy. DNS routing and telemetry code exist but are not
promoted. It is not a production full-device VPN. Do not distribute the APK as a
privacy or security product.

The remaining data-plane work is IPv6 dual-stack (`::/0`), cancellable lifecycle
state machine, capability promotion evidence, and physical-device acceptance.
Phase 0 prevents incomplete paths from being activated: every unproven
capability (`dataPlane`, `wireGuard`, `magicsock`, `twoPhaseStart`, `ipv4`,
`ipv6`, `tcp`, `udp`, `dns`, `liveStats`, `cancelSafeLifecycle`) remains false
and Android refuses to establish a default-route VPN.

## Current data-flow truth table

| Input from Android | Native handling | Actual egress |
| --- | --- | --- |
| IPv4 TCP | gVisor terminates TCP and proxies the stream with `Client.DialTCP` | Tailcat WireGuard/Magicsock to gateway |
| IPv4 UDP destination port 53 | gVisor proxies datagram via `Client.DialUDP` to TUN dest (PROFILE_RESOLVER) or `ForcedDNS` (FORCED_RESOLVER). Engine does not inspect TC bits; a libc/app TCP/53 retry is a normal TCP proxy | Tailcat WireGuard/Magicsock to gateway |
| Other IPv4 UDP | gVisor proxies datagrams via `Client.DialUDP` across Tailcat netstack | Tailcat WireGuard/Magicsock to gateway (pending live acceptance) |
| IPv6 | Android does not add a VPN IPv6 address or `::/0` | Underlying IPv6, or blocked only by Android lockdown |
| ICMP/ICMPv6 echo | Constructs a local echo reply | No gateway/Internet request is made |
| Native exit audit | TLS/HTTP through `Client.DialTCP` | Tailcat gateway |
| In-app speed test | `HttpURLConnection` from excluded app UID | Direct device network (explicitly labeled as physical-network benchmark in UI) |

## Non-negotiable invariants

1. Until every required release gate passes, `getCapabilitiesJSON` must cause
   the Android capability handshake to fail. A bundled AAR is not sufficient.
2. Never install `0.0.0.0/0` or `::/0` around an incomplete or unhealthy packet
   pump.
3. Never substitute an ordinary OS socket for a tunneled application flow. The
   app-UID exclusion makes that a leak by design.
4. Never report `CONNECTED` from method availability, a successful public
   Internet request, or a stale startup sample.
5. Never derive or invent a disco public key from a node public key. The two
   keys are intentionally unlinkable.
6. Unknown telemetry is represented as absent/unknown, not as measured zero.
7. Documentation, UI, notifications, release notes, and artifact names describe
   current verified behavior only.
8. Never sign or publish a release with the Android debug key.

## Scope and gateway compatibility

OpenTailcat is an initiating client. It does not own gateway NAT, WARP/Tor
policy, or DNS filtering. However, an arbitrary UDP VPN cannot be completed by
client code if the selected gateway accepts only TCP.

Official Tailcat `v0.4.0` currently configures `serve exit-node` with
`OnTCPForward`; its filter admits TCP only. Its client also leaves
`NetstackDialUDP` as an unreachable panic. This embedded tree already replaces
that panic, exports `Client.DialUDP`, and adds CLI `OnUDPForward`. Therefore:

- First determine whether the live target gateway (`nullexit` or another
  deployment) already accepts UDP flows over the Tailcat WireGuard peer.
- Record the gateway implementation/version and prove the capability with a
  minimal tunneled UDP echo test.
- If the gateway is TCP-only, add a compatible Tailcat gateway change or deploy
  a gateway version that supports UDP. Do not tunnel UDP over the device network
  and do not call a TCP-only milestone complete.
- Keep gateway policy out of the Android app. The only cross-side work allowed
  here is the protocol/data-plane capability required for interoperability.

The preferred Tailcat extension is native UDP in its existing userspace
netstack, not a new custom UDP-over-TCP framing protocol. A framing protocol
would require congestion, head-of-line, backpressure, fragmentation, and
versioning work while still requiring a gateway update.

## Instructions for the next implementation agent

1. Read `agents.md`, this file, and the affected source before editing.
2. Start with Phase 0 and make a reviewable fail-closed checkpoint. Do not begin
   a large UDP refactor while the current AAR can still advertise production
   readiness.
3. Preserve unrelated working-tree changes and never replace verified upstream
   Tailcat behavior with a new WireGuard, DERP, or discovery implementation.
4. Work phase by phase. At each checkpoint report changed files, exact tests,
   untested assumptions, binary/AAR rebuild state, and which capabilities remain
   false.
5. Do not mark a phase complete because code compiles. Its acceptance condition
   and negative/leak tests must pass.
6. Do not sign, publish, upload, or install a production artifact without an
   explicit maintainer request and the corresponding release gates.
7. If gateway UDP capability cannot be inspected or tested, finish safe
   client-side preparatory work but leave UDP/data-plane capabilities false and
   report the external compatibility blocker.

## Implementation sequence

Checkpoint status:

- Phase 0 — complete: incomplete native behavior fails closed.
- Phase 1 — complete: provenance and deterministic native builds verified.
- Phase 2 — complete: Kotlin and Go share the strict upstream-compatible token contract.
- Phase 3 — implementation complete: native userspace netstack UDP proxy, gateway CapExitUDP capability check, AllowProxy policy enforcement, and synchronized shutdown; physical-device live acceptance pending; `udp` remains false.
- Phase 4 — DNS routing code exists: strict IPv4/IPv6 address validation and PROFILE/FORCED resolver routing in native netstack. `GATEWAY_RESOLVER` is unused (treated as PROFILE). The engine does not inspect DNS TC bits. `dns` remains false until promotion evidence.
- Phase 7 — telemetry code exists: schema version 2, live WireGuard peer Tx/Rx and Magicsock path from `Client.Status()` while a bridge is running. RTT is a prepare-time snapshot; `RecordRTT` is not called in production, so jitter is always null. Jitter math is mean absolute consecutive difference, not RFC 3550. Kotlin still accepts schema v1 and synthesizes missing `state` as `RUNNING`. `liveStats` remains false until promotion evidence.
- Phases 5, 6, 8 — planned; `twoPhaseStart` and `cancelSafeLifecycle` remain false. Every unproven capability remains false.

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
   LinkProperties via `updateNetworkState`, and `Client.NetMon()` exists.
   Live Wi-Fi/cellular roaming re-evaluation remains Phase 6. The hard-coded
   DERP map remains.
5. Align CI to Go 1.27 and pin Go Mobile/NDK versions. Do not depend on an
   implicit toolchain auto-download from a Go 1.24 CI bootstrap.
6. Build with supported reproducible-path/`-trimpath` settings so developer
   workstation paths are not embedded. Verify that stack traces and symbol
   handling remain useful under the chosen release policy.
7. Add a CI task that rebuilds the AAR and fails when the checked-in AAR differs
   from source. Record `sha256`, `go version -m`, ABIs, Java signatures, and ELF
   alignment as artifacts.

Acceptance condition: a clean checkout deterministically produces an AAR with
the expected API, dependencies, ABIs, and 16 KB alignment.

### Phase 2: unify the token contract

**Checkpoint complete.** The canonical read-only fixture corpus is
`core-engine/testdata/token_fixtures.json`; it contains 43 deterministic cases.
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

Tests must include byte-for-byte golden vectors produced by the pinned upstream
version, malformed vectors, duplicate-map-key vectors, and cross-language
fixtures consumed by both Kotlin and Go.

Acceptance condition: Kotlin and Go return the same classification for every
fixture, and every token accepted for connection starts an upstream client
without synthetic key material.

### Phase 3: implement tunneled UDP end to end

**Checkpoint status: Implementation complete; live physical acceptance pending.**
All native proxy components, `Client.DialUDP`, gateway `OnUDPForward`, `CapExitUDP` negotiation,
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
5. Advertise a versioned UDP capability so the client fails before creating the
   TUN when paired with a TCP-only gateway.

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
- protected from data races (`lastActive` is currently raced);
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

**Checkpoint status: Routing code exists; `dns` remains false until promotion evidence.**

#### Implementation details

1. **Native DNS destination resolution (`core-engine`):**
   - Implemented `DNSConfig` on `TunBridge` (`Policy`: `"PROFILE_RESOLVER"` or `"FORCED_RESOLVER"`, `ForcedDNS`: `netip.AddrPort`).
   - Integrated `resolveDNSDestination` into `netstackProxy` for both `acceptUDP` and `acceptTCP`:
     - Under `PROFILE_RESOLVER` (default), preserves the destination IP from the TUN datagram verbatim and forwards it through `Client.DialUDP` or `Client.DialTCP`.
     - Under `FORCED_RESOLVER`, redirects port 53 queries exclusively to the configured `ForcedDNS` endpoint.
     - Any other policy string, including Kotlin `GATEWAY_RESOLVER`, is treated as `PROFILE_RESOLVER`.
   - Preserves full datagram boundaries up to 65,535 bytes to prevent truncation of large EDNS0 / DNSSEC responses. Bridge MTU is hardcoded 1280 and can still drop larger outbound packets before inject.
   - The engine does **not** inspect DNS TC bits. `TestDNSTruncationAndTCPRetryFallback` forwards the TC=1 UDP answer, then the test itself calls `DialTCP`. If Android/libc retries over TCP/53, that flow is a normal TCP proxy.
   - Do not promote `dns` until the evidence in the capability table exists.

2. **Android DNS validation and policy (`app`):**
   - Created `DnsValidator` with strict IPv4 and IPv6 validation. Rejects loopback (`127.0.0.0/8`, `::1`), multicast (`224.0.0.0/4`, `ff00::/8`), broadcast (`255.255.255.255`), unspecified (`0.0.0.0`, `::`), leading-zero octets, hostnames, URLs, and ports.
   - Added `DnsPolicy` enum (`PROFILE_RESOLVER`, `FORCED_RESOLVER`, `GATEWAY_RESOLVER`) and `DnsPreset` presets (Cloudflare, Quad9, Google). There is no UI policy picker; add-profile defaults to `PROFILE_RESOLVER`. `GATEWAY_RESOLVER` is never selected. `DnsPreset.ALL_PRESETS` is unused.
   - Added `PreferencesStorage` interface and `defaultDns` setting.
   - Integrated DNS validation and policy persistence in `ProfileRepository` (`addOrUpdateFromToken`, `updateProfileDns`) with fallback for corrupt legacy data.
   - Enforced DNS validation in `TailcatVpnService` before calling `Builder.addDnsServer`, propagating policy to native engine via the first `updateNetworkState`. Later `TunnelController` network-state updates omit `dnsPolicy`/`forcedDns` and can wipe the native policy.
   - Updated UI in `HomeScreen` (Add Profile dialog) and `SettingsScreen` (Defaults card) with real-time validation error feedback.

3. **Automated test coverage:**
   - Go (`core-engine/dns_test.go`): `TestDNSTransactionIDPreservation`, `TestDNSParallelQueries`, `TestDNSEDNS0AndLargeResponses`, `TestDNSTruncationAndTCPRetryFallback`, `TestDNSConfiguredPolicyAndDestinationMatching`, `TestDNSIPv4AndIPv6Resolvers`, `TestDNSTimeoutAndCancellation`, and `TestDNSLeakPrevention`.
   - Android (`app/src/test`): `DnsValidatorTest` (IPv4, IPv6, invalid octets, leading zeroes, loopback, broadcast, multicast, hostnames), `ProfileRepositoryTest` (valid creation, forced policy, rejection of invalid IPs, updating profile DNS, JSON persistence roundtrip, fallback for corrupt entries).

Acceptance condition: configured policy and observed resolver destination match, both UDP and TCP DNS leave through the gateway, later network-state updates preserve policy, and leak tests pass. Only then may `dns` become true.

### Phase 5: complete or deliberately block IPv6

**Checkpoint status: Unimplemented.** Android installs only `100.64.0.2/32` and
`0.0.0.0/0`. There is no IPv6 VPN address, no `::/0`, and no fail-closed IPv6
drop path. Native gVisor already registers IPv6 and can proxy IPv6 TCP/UDP if a
packet reached TUN; ICMPv6 echo is answered locally. `ipv6` remains false.

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

**Checkpoint status: Unimplemented.** Current engine has only `running`/`prepared`
booleans, holds `globalCore.mu` across Ping/DiscoPing, cannot cancel a blocked
`prepare`, overwrites a prepared-but-unattached client without `Close()`,
returns from `attachTun` when `readLoop` starts, and does not promote pump
errors to FAILED. Android `establish()` installs `0.0.0.0/0` before `attachTun`.
`CONNECTED` is set after `attachTun` plus `transportType != UNKNOWN`. Extra
capability JSON keys are ignored. `twoPhaseStart` and `cancelSafeLifecycle`
remain false.

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
  triggers immediate Android teardown.
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

**Checkpoint status: Schema and WireGuard counters exist; `liveStats` remains false until promotion evidence.**

Add an upstream client status surface based on the live WireGuard engine and
Magicsock status. Prefer extending `Client` with a read-only status method
analogous to the existing server `Status()`.

Current code reports schema version 2 with:

- engine state `STOPPED` / `PREPARED` / `RUNNING` (no `FAILED`); marshal-failure stub uses `ERROR`;
- monotonic session ID;
- WireGuard peer TX/RX, last handshake, CurAddr, and Relay from `client.Status()` while a bridge is running;
- TUN accepted/dropped counters distinct from WG when WG counters are non-zero; if both WG counters are 0, `txBytes`/`rxBytes` fall back to TUN;
- prepare-time Ping/DiscoPing RTT snapshot; `RecordRTT` is never called in production;
- jitter only after ≥3 `RecordRTT` samples, otherwise `null`. Live jitter is therefore always null. The formula is mean absolute consecutive difference, not RFC 3550 `J := J + (|D|-J)/16`;
- packet/drop counters for TCP, UDP, DNS, malformed IP, MTU, queue exhaustion, and policy rejections;
- exit-audit IP plus timestamp and error state;
- DERP names from `client.DERPMap()` when present, else hardcoded `regionNameForID`.

Kotlin `NetworkMetrics.fromJson` accepts schema versions 1 and 2, rejects ≥3, and synthesizes missing `state` as `RUNNING` when transport is known. `onEngineConnected` does not require `state == RUNNING` or a fresh health timestamp.

Do not promote `liveStats` until live RTT sampling exists, jitter matches the documented formula, Kotlin rejects incompatible/v1 telemetry, unknown values stay absent, and staleness is enforced when the data plane stops.

The in-app HTTP speed test runs from the excluded app UID. Label it as a direct
device-network benchmark, or replace it with a native Tailcat-routed benchmark.
Do not present it as tunnel performance in its current form.

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

The existing Android instrumentation test only stores a token. Replace or
supplement it with tests that start the real service on a test device, pass VPN
consent through a controlled harness, generate traffic from a second UID, and
assert route/service cleanup.

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

For leak tests, capture simultaneously on the Android uplink and gateway. With
the VPN connected, destination TCP/UDP/DNS/IPv6 packets must appear only at the
gateway; the client uplink may contain only encrypted Tailcat transport and
explicitly excluded-app traffic. Force each native pump to fail and confirm
routes are removed or Android lockdown blocks traffic.

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

## Commands

```bash
# Native checks
cd core-engine
go test ./...
go vet ./...

# Android checks
cd ..
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease

# Inspect native API/ABIs after rebuilding
unzip -l app/libs/libtailcat.aar
# Extract classes.jar to a temporary directory, then:
javap -classpath classes.jar com.tailcat.vpn.engine.Engine

# Verify repository state
git status --short
```

Live tokens, private signing keys, captures containing user traffic, and gateway
secrets must never be committed or pasted into public logs.
