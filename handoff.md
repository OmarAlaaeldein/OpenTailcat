# OpenTailcat implementation handoff

This document is the authoritative continuation plan for the Android client.
It separates current behavior from intended behavior and is written so another
engineer or coding agent can implement the remaining work without repeating the
unsafe shortcuts already found in the tree.

## Audited snapshot

- Android repository: version 1.3.7 on `main` (all 1.3.3–1.3.6 changes — audit H1–H7 source fixes after 1.2.2/1.2.3, S+-aware startup instrumented expectation, dead-code sweep, strict interior-whitespace token error, 5s UDP capability probe with periodic re-probe, `tcpOnly` telemetry, private-DNS rejection, native pump panic containment, disco-failure transport downgrade with `discoStale`, dead `GATEWAY_RESOLVER` removal, structured data-plane failure reporting with a debug diagnostics flag, nil-dial hardening with panic call-site reporting, typed-nil Close fix and per-flow panic isolation, HealthStale 15s/3-poll grace for intermittent 6s auto-close, notification/TelemetryCard live TUN rates instead of always-zero WG txBytes, 200.x stale-DNS networking-corruption fix (`pendingDNS` cleared on `abandonPrepare`, `TelemetryCard` now `isLiveRunning`), working split-tunnel exclusions with `addDisallowedApplication`, all-apps picker with search, PREPARED `rttMs` reporting, and a speed-test troubleshooter that surfaces silent stage failures and tunnel diagnostics — see the dated sections below). On top of 1.3.7, `main` also has a speed-test **troubleshooter clipboard export** (TopAppBar copy → `SpeedTestReport`, metrics/tunnel snapshot on `SpeedTestResult`, IP-sanitized free text) and a Settings **Updates** card that checks `api.github.com/.../releases/latest`, downloads the ABI-matching APK into cache (SHA-256 verify when a digest is available), signature-compares the archive, and installs via FileProvider + `REQUEST_INSTALL_PACKAGES`. IPv4 test-routing capabilities are
  true so Connect can be exercised with a live token. `ipv6` is true; `ipv6Egress` is session-measured.
- Safe Android-shell checkpoint: `e475abc`.
- Phase 0 fail-closed checkpoint: `877942a`.
- Phase 1 reproducible-build checkpoint: `76563c9`.
- Phase 2 unified token contract checkpoint: `dfce360`.
- Phase 3 tunneled UDP data plane implementation complete; live physical acceptance pending; IPv4 `udp` is test-enabled.
- Phase 4 DNS routing exists with pending-config and omit-means-preserve; IPv4 `dns` is test-enabled.
- Phase 5 IPv6 TCP/UDP is proxied with a 250ms dial timeout; ICMPv6 echo is dropped; oversized IPv6 gets a local Packet Too Big; Android installs `::/0` only after pumps are live; `ipv6` is true; `ipv6Egress` is session-measured.
- Phase 6 cancellable session context, readiness barriers, pump-failure `FAILED`, bounded `Stop`, `DetachTun`, and `DisarmPumps` exist. After `prepare`, Android establishes a host-only TUN (no VPN DNS), attaches pumps, `detachTun`, then installs `0.0.0.0/0` and `::/0` with VPN DNS and reattaches. The VPN service is `START_STICKY` with `stopWithTask=false`; shutdown closes the TUN before native `stop`. IPv4 test-routing enables `twoPhaseStart` and `cancelSafeLifecycle`.
- Phase 7 telemetry schema and WireGuard counters exist; RTT is sampled from live `DiscoPing` while a bridge is running; Kotlin rejects schema v1 and does not synthesize `RUNNING`; the measured `tcpOnly` latch (5s UDP probe at prepare, 30s re-probe while latched) is reported in stats and the UI; `liveStats` is test-enabled.
- Upstream Tailcat base: signed `v0.4.0`, commit
  `ce6fedcabc220bab3b94d470ab330219111eeae8`.
- Tailcat source: git submodule of `github.com/tailscale/tailcat` at
  unmodified `0c31395bfd1ae0c0ef2917c0ec20432466087417` (application-layer UDP).
- Native binary: `app/libs/libtailcat.aar`, ARM64 and x86-64, built
  reproducibly with Go 1.27.1 and NDK 29.0.14206865. Current SHA-256:
   `986c21150a4da2890b78023523a9b2bd6415ddc68de301000e792af6c16a2436`
  (previous source-fixed AAR SHAs `9e3256a9449347159913215cad258acbd528601a39175d310dda0e3bfd6b311c`,
   `c5b479c0b5710ed926804cb0b827472e5648b6bd356679195815ac888fa1b606`,
   `aa0fa1bdda9ae102d3ef7a7153c2d1ceca3f5165c8c707e8b490d97997a75999`, and
   1.3.2 SHA `a03e832082535bc4f8f860147bd1fa523e42d1c716e24d71a26c0041034b0976`).
  Sidecars: `app/libs/libtailcat.aar.sha256`, `app/libs/libtailcat.aar.sourcehash`.
  Liveprobe token for host tests is read from `OPENTAILCAT_LIVE_TOKEN` or
  `~/.opentailcat-private/live-token.txt` (never committed).
- ARM64 and x86-64 ELF load segments are 16 KB aligned.
- Audit verification passed: `go test -race ./...`, `go vet ./...`, Android unit
  tests, lint with zero errors, `assembleRelease`, and `bundleRelease`.
- Host liveprobe (`go test -tags liveprobe`, token via `OPENTAILCAT_LIVE_TOKEN`
  or `~/.opentailcat-private/live-token.txt`): IPv4 TLS via gateway OK; short
  DialTCP download sample ~10.8 Mbps; transport observed as DERP_RELAY with
  later direct path contact; `ipv6Egress=false` (gateway WAN / probe fail);
  one prepare sample latched `tcpOnly=true` (UDP probe) and one `tcpOnly=false`.

Passing these build checks is not a data-plane release gate. No current test
establishes a full Android VPN or proves leak-free traffic.

### Networking corruption on failed Connect — fixed in tree (200.x forced resolver)

In 1.3.2 and earlier (including the 1.2.14 checkpoint above) a failed `prepare`
— e.g. `gateway handshake failed: context deadline exceeded` after dialing a
`200.111.5.10:443`/`[2001:db8::1]:443` DERP or a user `FORCED_RESOLVER`
`200.160.0.8:53` — corrupted networking for **some time** (observed as “forces
me to use an IP that starts with 200”):

* `core-engine/lifecycle.go:219` `abandonPrepare()` cleared `sess/state` but
  left `globalCore.pendingDNS` (`core-engine/main.go:214`
  `globalCore.pendingDNS.Store(&cfg)`) from the `TailcatVpnService.kt:126`
  `updateNetworkState` that ran **before** `prepare`. The next `AttachTun`
  (`lifecycle.go:256` `dns := globalCore.pendingDNS.Load()`) therefore `Load()`ed
  the stale `FORCED_RESOLVER` (`app/libs/libtailcat.aar:19509312` build) and
  `bridge.go:97` `SetDNSConfig` forced subsequent port-53 flows through
  `Client.DialUDP` to the stale `200.x:53` (`netstack_proxy.go:resolveDNSDestination`).
  When that `200.x` was unreachable, `policyRejections`/`queueExhaustion`
  dropped DNS and, with the 5s `udpProbeTimeout` (`bridge.go:697`) latched
  `tcpOnly=true`, non-DNS UDP was also dropped until the 30s
  `udpReprobeInterval` (`bridge.go:701`) or an explicit `Stop()` finally cleared
  `pendingDNS` (`lifecycle.go:408`). The window was typically 30s + the 15s
  `HealthStale` grace (`service/EngineHealth.kt:13` `STALE_TEARDOWN_POLLS=3`) —
  i.e. “for some time” after a single failed tap.
* `app/src/main/java/com/tailcat/vpn/ui/screens/home/components/TelemetryCard.kt:57`
  used `tunnelActive = transportType != UNKNOWN`. After `FAILED`/`HealthStale`,
  `NetworkMetrics.kt:50` `isLiveRunning()` was already false (`state != "RUNNING"`
  or `healthUnixSec` stale) but `transportType` could remain `DIRECT_P2P`/`DERP_RELAY`,
  so the card kept showing `Exit IP: 200.x` (`metrics.tunnelEgressIp` from the
  previous session’s `bridge.go:894` `egressIP`) instead of `Device IP:`.
  Users saw a stale `200.` exit and perceived a forced `200.` route.

Fix in this tree (AAR `aa0fa1bd...`, `app/src/main/java/com/tailcat/vpn/ui/screens/home/components/TelemetryCard.kt:60`
now `isLiveRunning(nowSec)`; `core-engine/lifecycle.go:225` `pendingDNS.Store(nil)`
on `abandonPrepare`): a failed `prepare` no longer leaves a stale
`FORCED_RESOLVER`. Verified: `go test -race ./...`, `testDebugUnitTest`/`lintDebug`,
and `VpnStartupInstrumentedTest` (synthetic `tc…` with embedded `200.111.5.10`
DERP) now goes `CONNECTING (10s) → DISCONNECTED` with `lastError=gateway handshake
failed: context deadline exceeded`, `networkMetrics=UNKNOWN`/`tunnelEgressIp=null`,
`ip route` shows no `200.` TUN route, and a subsequent profile with `1.1.1.1`
correctly uses `1.1.1.1` — no forced `200.`.

Documented here per `AGENTS.md:Documentation rule` — `README.md`/`docs/releases/`
describe verified shipped behavior only; this handoff records the corrected
failure path.

## Split-tunnel exclusions now apply to the VPN interface (2026-09-21)

Through 1.3.3, Settings > Apps stored `splitTunnelExcludedApps`, but
`TunnelController.validateStartRequest` and `LeakGuard.refusalReasonForStartup`
refused Connect whenever the list was non-empty (`SPLIT_TUNNEL_BLOCKED`), and
`VpnService.Builder.addDisallowedApplication` was never called. A user could
select apps to bypass the VPN, but Connect then failed with
"Disable split-tunnel exclusions before connecting".

Fix in this tree (Kotlin-only; AAR unchanged):

- `TailcatVpnService.vpnBuilder` now applies each stored exclusion with
  `Builder.addDisallowedApplication` on both the warm (host-only) and routed
  (`0.0.0.0/0` + `::/0`) interfaces. The list is snapshotted once per start and
  filtered by `SplitTunnelExclusions.validPackages`, which skips blank entries
  and packages no longer installed so a stale entry cannot fail `establish`.
- The split-tunnel Connect refusal is removed from
  `TunnelController.validateStartRequest` and the service startup path;
  `LeakGuard` is deleted and `LOCKDOWN_REQUIRED_API` moved into `LockdownProbe`.
  Lockdown still never gates Connect (status only).
- Bypass remains leak-by-design per invariant 3: excluded apps use the ordinary
  OS network. UI copy states checked apps bypass the VPN and that the tunnel is
  not leak-free while any app is checked, and that with Always-on lockdown
  Android blocks checked apps from the network entirely (documented platform
  behavior for disallowed applications under lockdown).
- OpenTailcat's own UID is never on the list; the Settings picker filters out
  the app package. Magicsock/DERP bypass still relies on `VpnService.protect`
  (`TransportSocketProtect`), not on app-UID exclusion.
- Tests: `SplitTunnelExclusionsTest` (blank/uninstalled filtering, ordering);
  `LeakGuardTest` deleted; `LockdownProbeTest` updated.

Still pending: the Phase 8 split-tunnel acceptance evidence above (second-UID
probe traffic must appear directly on the uplink pcap and be absent from the
gateway pcap while exclusions are set). Pass locally only after that capture.

## Settings > Apps picker now lists all installed apps (2026-09-21)

Through 1.3.4 the split-tunnel picker used `LauncherApps.getActivityList`,
which returns only apps with a launcher icon. Background and headless apps
never appeared. In 1.3.5 the picker enumerates every package installed for
the user via `PackageManager.getInstalledApplications` (`SettingsScreen.kt`),
adds a search field filtering by app name or package name, and declares
`QUERY_ALL_PACKAGES` in the manifest (required on API 30+ for full package
enumeration; lint advisory suppressed with `tools:ignore`). Exclusion
application, leak-by-design copy, and the Phase 8 split-tunnel packet-capture
acceptance are unchanged.

## Release status

The current tree is **1.3.7** versionCode **37** (AAR `986c21150a4da2890b78023523a9b2bd6415ddc68de301000e792af6c16a2436`,
Go 1.27.1, NDK 29.0.14206865, 16 KB) with the Phase 8 analyzer SLL2 fix,
the 200.x stale-DNS fix, the split-tunnel exclusion fix, the all-apps picker
fix, PREPARED `rttMs` reporting, and the speed-test troubleshooter above.
1.3.6 used versionCode 36; 1.3.5 used versionCode 35; 1.3.4 used
versionCode 34; the 1.2.14 checkpoint used versionCode 27. The `development`
build type is release R8/resource optimization with the existing development
certificate and no debug UI tooling. `release` signing remains separate. To
reproduce these APKs, run `./gradlew assembleDevelopment`. The existing
instrumentation suite targets debug; optimized APKs are checked directly
through the emulator UI. This packaging correction promotes no VPN
capabilities.

The current tree is a development prototype with verified token parsing and a
userspace netstack UDP proxy. DNS routing and telemetry code exist; IPv4 `dns`
and `liveStats` are test-enabled, not Phase 8 accepted. Capability JSON also
reports `testRouting: true` (optional, non-gating); Settings surfaces it while
Phase 8 physical leak acceptance is pending. It is not a production
full-device VPN. Do not distribute the APK as a privacy or security product.

IPv4-only Connect is enabled for live-token testing. `ipv6` is true; `ipv6Egress` is session-measured.
Android installs `0.0.0.0/0` and `::/0` after pumps are live. This is not a
production leak-free release. Remaining work is live IPv6 egress evidence,
Phase 8 physical capture/signing, and honest promotion-table evidence for the
IPv4 flags now set true.

## Current data-flow truth table

| Input from Android | Native handling | Actual egress |
| --- | --- | --- |
| IPv4 TCP | gVisor terminates TCP and proxies the stream with `Client.DialTCP` | Tailcat WireGuard/Magicsock to gateway |
| IPv4 UDP destination port 53 | gVisor proxies datagram via `Client.DialUDP` to TUN dest (PROFILE_RESOLVER) or `ForcedDNS` (FORCED_RESOLVER). Engine does not inspect TC bits; a libc/app TCP/53 retry is a normal TCP proxy | Tailcat WireGuard/Magicsock to gateway |
| Other IPv4 UDP | gVisor proxies datagrams via `Client.DialUDP` across Tailcat netstack | Tailcat WireGuard/Magicsock to gateway (pending live acceptance) |
| IPv6 TCP/UDP | gVisor inject → `DialTCP`/`DialUDP` with 250ms timeout | Gateway if it has IPv6 WAN; else RST/drop so apps can use tunneled IPv4 |
| ICMPv6 echo | Dropped | No gateway/Internet request is made |
| IPv6 over MTU | Local ICMPv6 Packet Too Big | No gateway request |
| IPv4 over MTU | Local ICMP Fragmentation Needed | No gateway request |
| IPv4 ICMP echo | Constructs a local echo reply | No gateway/Internet request is made |
| Native exit audit | TLS/HTTP through `Client.DialTCP` | Tailcat gateway |
| In-app speed test | When CONNECTED: `Client.DialTCP` through the gateway (`speed.cloudflare.com` resolved with DNS-over-TCP via `Client.DialTCP` to `1.1.1.1:53`). Otherwise `HttpURLConnection` from excluded app UID | Gateway TCP when CONNECTED; direct device network otherwise. UI labels the path. |

## Non-negotiable invariants

1. Until every required release gate passes, do not claim production readiness.
   IPv4 test-routing flags are currently true so Connect can run; client
   `ipv6` is true (`ipv6Egress` is session-measured and needs gateway WAN).
   Unknown capability fields still fail closed. A bundled AAR is not
   Phase 8 acceptance.
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

Official Tailcat `v0.4.0` configures `serve exit-node` with `OnTCPForward` and
admits TCP only. Current upstream (this submodule pin) exports `Client.DialUDP`
and `Server.OnUDPForward`. Therefore:

- First determine whether the live target gateway (your Tailcat gateway or another
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

1. Read `AGENTS.md`, this file, and the affected source before editing.
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

## Implementation sequence

Checkpoint status:

- Phase 0 — complete: incomplete native behavior fails closed.
- Phase 1 — complete: provenance and deterministic native builds verified.
- Phase 2 — complete: Kotlin and Go share the strict upstream-compatible token contract.
- Phase 3 — implementation complete: native userspace netstack UDP proxy using upstream `Client.DialUDP` / `OnUDPForward`; physical-device live acceptance pending; IPv4 `udp` is test-enabled.
- Phase 4 — DNS routing code exists: pending DNS is stored before attach and applied on `attachTun`. Absent `dnsPolicy` in later `updateNetworkState` does not reset policy. `GATEWAY_RESOLVER` is unused (treated as PROFILE). The engine does not inspect DNS TC bits. IPv4 `dns` is test-enabled.
- Phase 5 — IPv6 TCP/UDP proxied with a 250ms dial timeout; ICMPv6 echo dropped; oversized IPv6 gets Packet Too Big; Android installs `::/0` after pumps are live. `ipv6` is true; `ipv6Egress` is session-measured.
- Phase 6 — session context, short mutex, always-Close previous client, readiness barriers, pump-exit `FAILED` + `healthUnixSec`, bounded `Stop`, `DetachTun`, `DisarmPumps`. After `prepare`, Android establishes a host-only TUN (no VPN DNS), attaches, `detachTun`, then installs `0.0.0.0/0`/`::/0` with VPN DNS and reattaches. Sticky VPN service; TUN closed before native `stop`. IPv4 test-routing enables `twoPhaseStart` and `cancelSafeLifecycle`.
- Phase 7 — telemetry code exists: schema version 2. RTT is sampled from `DiscoPing` about every 5s while a bridge is running; jitter is null until three samples. WireGuard peer Tx/Rx stay 0 because upstream `Client` has no Status API. Kotlin requires version 2, does not synthesize missing `state` as `RUNNING`, and CONNECTED requires live `RUNNING` + fresh `healthUnixSec`. `liveStats` is test-enabled.
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
`core-engine/testdata/token_fixtures.json`; it contains 46 deterministic cases.
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
   - Integrated DNS validation and policy persistence in `ProfileRepository` (`addOrUpdateFromToken`, `updateProfileDns`) with fallback for corrupt legacy data.
   - Enforced DNS validation in `TailcatVpnService` before calling `Builder.addDnsServer`. Native omit-means-preserve keeps pending DNS across roam `updateNetworkState` payloads that lack `dnsPolicy`.
   - Updated UI in `HomeScreen` (Add Profile dialog) and `SettingsScreen` (Defaults card) with real-time validation error feedback.

3. **Automated test coverage:**
   - Go (`core-engine/dns_test.go`): `TestDNSTransactionIDPreservation`, `TestDNSParallelQueries`, `TestDNSEDNS0AndLargeResponses`, `TestDNSTruncationAndTCPRetryFallback`, `TestDNSConfiguredPolicyAndDestinationMatching`, `TestDNSIPv4AndIPv6Resolvers`, `TestDNSTimeoutAndCancellation`, and `TestDNSLeakPrevention`.
   - Android (`app/src/test`): `DnsValidatorTest` (IPv4, IPv6, invalid octets, leading zeroes, loopback, broadcast, multicast, hostnames), `ProfileRepositoryTest` (valid creation, forced policy, rejection of invalid IPs, updating profile DNS, JSON persistence roundtrip, fallback for corrupt entries).

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

**Checkpoint status: Schema, WireGuard counters, and live `DiscoPing` RTT exist; `liveStats` is test-enabled until Phase 8 evidence.**

Add an upstream client status surface based on the live WireGuard engine and
Magicsock status. Prefer extending `Client` with a read-only status method
analogous to the existing server `Status()`.

Current code reports schema version 2 with:

- engine state includes `STOPPED` / `PREPARING` / `PREPARED` / `ATTACHING` / `RUNNING` / `STOPPING` / `FAILED`; marshal-failure stub uses `ERROR`;
- monotonic session ID;
- WireGuard peer TX/RX stay 0: upstream `Client` has no Status API. Do not invent counters;
- TUN accepted/dropped counters distinct from WG. `txBytes`/`rxBytes` are WireGuard peer counters only (never a TUN fallback);
- live `DiscoPing` RTT about every 5s while a bridge is running; failed pings are skipped; Endpoint set => DIRECT_P2P else DERP_RELAY;
- jitter only after ≥3 `RecordRTT` samples, otherwise `null`. The formula is mean absolute consecutive difference, not RFC 3550 `J := J + (|D|-J)/16`;
- packet/drop counters for TCP, UDP, DNS, malformed IP, MTU, queue exhaustion, and policy rejections;
- exit-audit IP plus timestamp and error state;
- DERP names from the token's embedded region when present; otherwise empty (not invented).

Kotlin `NetworkMetrics.fromJson` requires schema version 2, rejects v1 and missing version, and leaves missing `state` empty. `onEngineConnected` requires `RUNNING` plus fresh `healthUnixSec`.

Do not promote `liveStats` until live RTT sampling exists, jitter matches the documented formula, Kotlin rejects incompatible/v1 telemetry, unknown values stay absent, and staleness is enforced when the data plane stops.

When CONNECTED, the in-app speed test uses native `MeasureTunnel*` (`Client.DialTCP`);
`speed.cloudflare.com` is resolved with DNS-over-TCP via `Client.DialTCP` to
`1.1.1.1:53`. When not CONNECTED, it uses `HttpURLConnection` from the excluded
app UID. The UI labels which path ran. Do not present the physical path as
tunnel performance.

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
# pcap), then generate second-UID probes from adb shell (uid 2000, not the
# VPN app). Optional CAPTURE_SECONDS=45 bounds each capture.
CAPTURE_IFACE=en0 CAPTURE_SECONDS=45 scripts/phase8/capture-uplink.sh captures/uplink.pcap &
CAPTURE_IFACE=eth0 CAPTURE_SECONDS=45 scripts/phase8/capture-gateway.sh captures/gateway.pcap &
scripts/phase8/generate-probes.sh   # PROBE_IPS / PROBE_ROUNDS / PROBE_PORT
wait
scripts/phase8/analyze-uplink.sh captures/uplink.pcap 1.1.1.1,8.8.8.8 captures/gateway.pcap
```

Pass: probe destinations are absent on the uplink pcap and present on the
gateway pcap. Uplink may contain only Tailcat/WireGuard/DERP (and Magicsock
sockets protected via `VpnService.protect`). Force each native pump to fail
and confirm routes are removed or Android lockdown blocks traffic.

Split-tunnel (excluded-app) evidence is the inverse: excluded UID probes must
appear on the uplink pcap and be absent from the gateway pcap.

Do not commit pcaps or live tokens. `ipv6` is true on the client; treat `ipv6Egress` and Phase 8 dual
capture as the honesty bar for public IPv6 egress. Host analyzer unit tests and
`e2e-analyze.sh` synthetic fixtures are tooling checks, not Phase 8 acceptance.

#### Phase 8 dual-capture run log (2026-09-23)

Physical phone + live Tailcat gateway (nullexit stack in Colima/Docker on the
same Mac). Phone app connected (token from `~/.opentailcat-private/live-token.txt`);
user opened `https://1.1.1.1`, `https://8.8.8.8`, `https://9.9.9.9` on the phone
during the capture window. Pcaps are gitignored under `captures/`.

**What was captured**

| File | Where | How |
|---|---|---|
| `captures/gateway.pcap` | Inside the `warp` / tailcat container network namespace (after WireGuard decrypt, before WARP encapsulation) | `colima ssh` → `sudo nsenter -t $(docker inspect -f '{{.State.Pid}}' warp) -n tcpdump -i any -s 0 -w /tmp/p8cap/gateway.pcap` |
| `captures/outer.pcap` | Colima **host** namespace (outer view: DERP/WARP/docker bridge — **not** the phone radio) | `colima ssh` → `sudo tcpdump -i any -s 0 -w /tmp/p8cap/outer.pcap` |

Classic pcap (magic `a1b2c3d4`), link type LINUX_SLL2 (276). Sizes ~104 MB /
~166 MB, ~136k / ~234k packets. Stopped with `pkill tcpdump`, copied out via
`colima ssh cat`.

**Counts (non-DNS probe dests only; DNS:53 to those IPs is ignored by the analyzer)**

| File | `1.1.1.1` | `8.8.8.8` | `9.9.9.9` |
|---|---|---|---|
| gateway | 297 | 40 | 26 |
| outer | 0 | 0 | 0 |

Gateway flows are mostly `172.16.0.2 → 1.1.1.1/8.8.8.8/9.9.9.9:443` (TCP) plus
ICMP to `1.1.1.1`. `172.16.0.2` is the WARP `tun0` address inside the netns —
i.e. decrypted phone traffic being forwarded out the gateway path.

**Analyzer (after SLL2 fix)**

```bash
scripts/phase8/analyze-uplink.sh \
  captures/outer.pcap 1.1.1.1,8.8.8.8,9.9.9.9 captures/gateway.pcap
# PASS uplink: probe destinations absent
# PASS gateway: probe destinations present
# exit 0

# Control: gateway misused as uplink must fail
scripts/phase8/analyze-uplink.sh \
  captures/gateway.pcap 1.1.1.1,8.8.8.8,9.9.9.9 captures/gateway.pcap
# FAIL uplink leak dests: [1.1.1.1 8.8.8.8 9.9.9.9]  exit 1
```

SLL2 bug fixed in `core-engine/phase8_pcap.go`: payload offset is a fixed
header of 20 bytes; `12+addr_len` is wrong when `addr_len=0` (common on
`tcpdump -i any` — all 463 probe packets in this gateway pcap had `addr_len=0`).
Test covers `addr_len` 0 and 8. AAR rebuilt: sha256
`986c21150a4da2890b78023523a9b2bd6415ddc68de301000e792af6c16a2436`,
sourcehash `1916945b42a9ae78ffa2ad070752c1d60063f1276eaa5d968e2fb8af2764a075`.
`scripts/phase8/run-host-gates.sh` green after the fix.

**Scope (plain)**

- Proven for this run: phone probe traffic reached the gateway and left toward
  the probe destinations on the gateway path; cleartext probe dests were not
  seen on the Colima host outer capture taken at the same time.
- Not proven: that the **phone’s own radio / home AP** never saw cleartext
  `1.1.1.1`. `outer.pcap` is this Mac’s outer interfaces, not the phone’s
  Wi‑Fi hop. Session path was **DERP relay** (`path=relay nyc`, 0 direct peers),
  so the phone’s first hop is Cloudflare, not this Mac. Full Phase 8 still needs
  a simultaneous capture on the phone’s actual uplink (AP/next hop, or rooted
  `wlan0`) paired with the gateway pcap, then the same analyzer invocation.
- Synthetic `e2e-analyze.sh` and host gates are tooling only (see above).

Commands used for host gates after the fix:

```bash
cd core-engine && GOPROXY=off go test ./... && GOPROXY=off go vet ./...
bash core-engine/build-aar.sh
scripts/phase8/run-host-gates.sh
```

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


## IPv6 remaining blockers (capability true; gateway WAN still required)

Client `ipv6` is true with fail-closed HE behavior. Real IPv6 Internet still needs:

1. Gateway / WARP path with working IPv6 WAN (`ipv6Egress=true` in stats).
2. Connected Always-on session with `::/0` installed after pumps are live (already implemented).
3. Second-UID IPv6 TCP/UDP probe succeeds only via gateway; simultaneous uplink+gateway classic PCAPs pass phase8-analyze (H7 fail-closed).
4. PMTU / Packet Too Big path exercised; ICMPv6 echo remains local-drop.
5. Gateway under test has IPv6 WAN. Client 250ms IPv6 dial timeout is intentional fail-fast toward tunneled IPv4 when the gateway lacks IPv6.

Omar device checklist: enable Always-on lockdown, rebuild AAR (`core-engine/build-aar.sh` with Go 1.27.1 + NDK 29.0.14206865), install development APK, confirm `isLockdownEnabled` after Connect, run `scripts/phase8` dual capture including an IPv6 probe address.
