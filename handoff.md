# OpenTailcat implementation handoff

This document is the authoritative continuation plan for the Android client.
It separates current behavior from intended behavior and is written so another
engineer or coding agent can implement the remaining work without repeating the
unsafe shortcuts already found in the tree.

## Audited snapshot

- Android repository: `a98a281` (`main`) at the time of audit.
- Safe Android-shell checkpoint: `e475abc`.
- Upstream Tailcat base: signed `v0.4.0`, commit
  `ce6fedcabc220bab3b94d470ab330219111eeae8`.
- Embedded Tailcat source: base plus local commit
  `49c65dace2d79b41d89f536289002816d13e5274`.
- Native binary: `app/libs/libtailcat.aar`, ARM64 and x86-64, built with Go
  1.27.0. The exported Java class is `com.tailcat.vpn.engine.Engine`.
- The checked-in AAR last changed in `08806c5`, while `core-engine/egress.go`
  changed later in `e5d20a9`. The binary still contains
  `User-Agent: Tailcat-Android/1.0` and local `/Users/omar/...` build paths, so
  it is not a byte-for-byte build of the current source and is not reproducible.
- ARM64 ELF load segments in the audited AAR are 16 KB aligned.
- Audit verification passed: `go test ./...`, `go vet ./...`, Android unit
  tests, lint with zero errors, `assembleRelease`, and `bundleRelease`.

Passing these build checks is not a data-plane release gate. No current test
establishes a full Android VPN or proves leak-free traffic.

## Release status

The current tree is a development TCP prototype with a valid Tailcat handshake,
not a production full-device VPN. Do not distribute the APK as a privacy or
security product.

The most serious defect is in `core-engine/bridge.go`: all non-DNS UDP uses
ordinary process sockets. Android excludes OpenTailcat's UID from the VPN so
those sockets leave through Wi-Fi/cellular rather than through WireGuard. IPv6
also lacks a TUN address and default route. Nevertheless, the native engine
advertises every capability as true and Android displays a connected/protected
state.

## Current data-flow truth table

| Input from Android | Native handling | Actual egress |
| --- | --- | --- |
| IPv4 TCP | gVisor terminates TCP and proxies the stream with `Client.DialTCP` | Tailcat WireGuard/Magicsock to gateway |
| IPv4 UDP destination port 53 | Converts DNS datagram to DNS-over-TCP | Tailcat to fixed `1.1.1.1`, then `8.8.8.8` |
| Other IPv4 UDP | `net.ResolveUDPAddr` + `net.DialUDP` | Direct device network; bypasses gateway |
| IPv6 | Android does not add a VPN IPv6 address or `::/0` | Underlying IPv6, or blocked only by Android lockdown |
| ICMP/ICMPv6 echo | Constructs a local echo reply | No gateway/Internet request is made |
| Native exit audit | TLS/HTTP through `Client.DialTCP` | Tailcat gateway |
| In-app speed test | `HttpURLConnection` from excluded app UID | Direct device network |

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
`NetstackDialUDP` as an unreachable panic. Therefore:

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

Choose and expose one explicit policy:

- **Gateway/profile resolver:** preserve the original destination from the TUN
  and proxy it through `Client.DialUDP`, with normal TCP fallback when the app
  retries after a truncated response.
- **Forced resolver:** ignore the packet destination only when the profile/UI
  clearly names and stores the forced resolver policy.
- **Gateway filtering resolver:** send queries to a gateway-owned address only
  when the gateway token/capability advertises it.

Do not show `customDns` while silently sending to Cloudflare/Google. Validate
resolver IPs before saving profiles. Test transaction IDs, parallel queries,
EDNS0, DNSSEC-sized responses, truncation, TCP retry, IPv4/IPv6 resolvers,
timeouts, cancellation, and no direct port-53 traffic from the client.

Acceptance condition: the configured policy and observed resolver destination
match, and both UDP and TCP DNS leave through the gateway.

### Phase 5: complete or deliberately block IPv6

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

Add an upstream client status surface based on the live WireGuard engine and
Magicsock status. Prefer extending `Client` with a read-only status method
analogous to the existing server `Status()`.

Report:

- explicit engine state and monotonic session ID;
- current direct endpoint or DERP region from live peer status;
- last successful WireGuard receive/handshake time;
- WireGuard peer TX/RX bytes, distinct from TUN accepted/dropped counters;
- sampled RTT with timestamp and path;
- jitter only after enough real samples, otherwise `null`;
- packet/drop/error counters for TCP, UDP, DNS, malformed IP, MTU, and queue
  exhaustion; and
- exit-audit IP plus timestamp and error state.

Transport and RTT must update after path changes. Region names come from the
active DERP metadata, not a hard-coded table. Define a versioned JSON schema and
reject incompatible telemetry in Kotlin.

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
