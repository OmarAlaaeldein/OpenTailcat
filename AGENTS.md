# OpenTailcat Android engineering guide

> Project root: `/Users/omar/Developer/OpenTailcat`  
> Android: min API 26, compile/target API 35  
> Android toolchain: Kotlin 2.2.10, AGP 9.3.0, Gradle 9.5.0, JDK 21  
> Native toolchain in `core-engine/go.mod`: Go 1.27.1

Read this file and `handoff.md` completely before changing code.
`handoff.md` is the authoritative, detailed implementation and test plan.

## Current audited status

The repository is **not production-ready**. IPv4 test-routing capabilities are
true so a live token can Connect. `ipv6` is true (client dual-stack path;
session `ipv6Egress` reports gateway WAN). Phase 8 physical leak
acceptance is unimplemented.

The checked-in AAR is built reproducibly with Go 1.27.1, NDK r29 (29.0.14206865),
16 KB ELF load alignment, and verified Java signatures.

Current version: 1.4.0, with audit H1–H7 source fixes after the 1.2.2/1.2.3 audits,
an S+-aware startup instrumented expectation, a behavior-neutral dead-code sweep,
a strict interior-whitespace token error, a 5s UDP capability probe with
periodic re-probe, `tcpOnly` telemetry, structured data-plane failure
reporting with a debug diagnostics flag, nil-dial hardening with panic
call-site reporting, a 200.x stale-DNS networking-corruption fix
(`pendingDNS` cleared on `abandonPrepare`, `TelemetryCard` now `isLiveRunning`),
working split-tunnel exclusions applied with `addDisallowedApplication`, and a
Settings > Apps picker that lists every installed package (not only launcher
apps) with `QUERY_ALL_PACKAGES` plus a search field.
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
  normal TCP proxy. With `tcpOnly`, UDP/53 is carried as DNS-over-TCP and an
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
- ICMP echo: IPv4 answered locally (fragments are not answered). ICMPv6 echo
  is dropped.
- Speed test: when CONNECTED, ping/download/upload use `Client.DialTCP` through
  the gateway (`speed.cloudflare.com` is resolved with DNS-over-TCP via
  `Client.DialTCP` to `1.1.1.1:53`); otherwise ordinary app sockets on the
  device's current routes (not a tunnel measurement).
- Telemetry: schema version 2. RTT is sampled from `DiscoPing` about every 5s
  while a bridge is running; jitter is null until three samples. Transport
  follows the last successful `DiscoPing` (Endpoint set => DIRECT_P2P).
  WireGuard peer Tx/Rx stay 0 because upstream `Client` has no Status API.
  Kotlin rejects v1 and requires `RUNNING` plus fresh `healthUnixSec` for
  CONNECTED. `liveStats` is test-enabled.
- Capabilities: API v2 dual-stack flags true including `ipv6`. The JSON also
  includes `testRouting: true`, which marks that Phase 8 physical leak
  acceptance has not passed; Kotlin surfaces this in Settings and does not
  gate Connect on it. After
  `prepare`, Android attaches a host-only TUN (no VPN DNS), `detachTun`, then
  installs `0.0.0.0/0` and `::/0` with VPN DNS and reattaches. Profile
  `tunnelMtu` is forwarded in `updateNetworkState` so the native bridge and
  netstack use the same MTU as `VpnService.Builder`.
- Tests: unit, integration, race, lint, and build tests pass; complete live
  physical hardware tunnel test pending.

Do not call this build secure, protected, complete, production-ready, or
leak-free. Do not publish or sign it as a VPN release.

## Active implementation focus

1. Phase 5 dual-stack: client `ipv6` promoted with prepare-time egress probe and
   fail-closed public IPv6 when the gateway has no IPv6 WAN. Gateway IPv6 WAN
   remains a deployment dependency for true dual-stack egress.
2. Phase 6 promotion: two-phase start exists; promote `twoPhaseStart` and
   `cancelSafeLifecycle` only after the evidence in `handoff.md`.
3. Phase 4/7 promotion: keep DNS preserve and telemetry honesty; promote `dns`
   and `liveStats` only after the evidence in `handoff.md`.
4. Phase 8: Pass physical-device uplink packet capture, multi-interface
   handover, leak verification, and production signing gates.

Only promote remaining capabilities after the evidence listed in `handoff.md`
passes.

## Scope

OpenTailcat is an initiating Android client. It does not own gateway NAT,
WARP/Tor selection, or filtering policy. Do not build a gateway listener into
the Android app.

Data-plane interoperability still requires a compatible gateway. Tailcat
v0.4.0 `serve exit-node` was TCP-only; signed v0.7.0 (2026-09-16) adds UDP
forwarding through `--serve=exit-node`. This submodule pin
(`v0.5.0-25-g0c31395bf`) exports `Client.DialUDP` and `Server.OnUDPForward`. If the live user-controlled Tailcat gateway
already supports native tunneled UDP, prove and version that capability.
Otherwise a matching gateway-side Tailcat UDP deployment is required. A
client-only direct socket is never an acceptable substitute.

## Upstream provenance

`third_party/tailcat` is a git submodule of
`https://github.com/tailscale/tailcat`, pinned to unmodified
`0c31395bfd1ae0c0ef2917c0ec20432466087417` (application-layer UDP). Do not
claim it is the signed `v0.4.0` tag. See `third_party/PROVENANCE.md`. Do not
add OpenTailcat patches in the submodule. Android supplies LinkProperties via
`updateNetworkState`. Roaming still belongs to Phase 6.

## Required implementation order

Follow the detailed methods and acceptance conditions in `handoff.md`:

1. [x] Phase 0: Restore fail-closed capability negotiation.
2. [x] Phase 1: Make upstream/native provenance and AAR builds reproducible.
3. [x] Phase 2: Align Kotlin and Go token parsing and reject connect-time legacy tokens lacking disco keys.
4. [x] Phase 3: Add native Tailcat UDP using userspace netstack; delete application-flow `net.DialUDP`.
5. [ ] Phase 4: DNS routing code exists; IPv4 `dns` is test-enabled until Phase 8 evidence.
6. [x] Phase 5: IPv6 TCP/UDP proxied with `::/0` after pumps; `ipv6` capability true;
      session `ipv6Egress` + fail-closed HE fix. Gateway IPv6 WAN still required for real IPv6 internet.
7. [ ] Phase 6: Cancellable machine and two-phase routes exist; live roam/leak evidence pending.
8. [ ] Phase 7: Schema, WG counters, and live `DiscoPing` RTT exist; `liveStats` is test-enabled until Phase 8 evidence.
9. [ ] Phase 8: Pass automated, local-gateway, physical-device, packet-capture, signing, 16 KB, R8/JNI, SBOM, and license gates.

## Native API

The current AAR exports `com.tailcat.vpn.engine.Engine`:

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
measureTunnelDownloadMbps()
measureTunnelUploadMbps()
```

Keep the original five methods for Android compatibility while versioning their
payloads. `updateNetworkState` is used by Kotlin. `parseToken` is exported for
the AAR verifier; Kotlin uses its own `TokenParser`.

Current lifecycle:

- `prepare` validates an official token, completes a Meow/Meowed handshake, and
  allows TCP-only gateways (DNS over TCP; other UDP dropped). Session context lets `stop`
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
  before native `stop`. The UI resyncs CONNECTED from live `getStatsJSON`.

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

A current official token must establish a real gateway session and carry
arbitrary full-device IPv4/IPv6 TCP, UDP, and DNS through WireGuard with direct
Magicsock and DERP fallback. Public IPv4/IPv6 must change only while connected;
telemetry must be live and authoritative; roaming and every failure path must
remain leak-free; and all release gates in `handoff.md` must pass.

An AAR, successful `Ping`, working TCP, DNS-over-TCP, local ICMP response, or a
single exit-IP audit is not completion.

## Verification commands

```bash
cd core-engine
go test ./...
go vet ./...

cd ..
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

After native changes, rebuild and inspect `app/libs/libtailcat.aar`, including
Java signatures, ARM64/x86-64 contents, `go version -m`, R8/JNI retention,
SHA-256, and 16 KB ELF load alignment. The checked-in AAR must never lag native
source. `build-aar.sh` writes `app/libs/libtailcat.aar.sourcehash` from the
`core-engine` + `third_party` file tree; CI fails if that hash does not match
the current tree.

## Documentation rule

README, privacy, security, third-party notices, UI copy, notifications, and
release notes describe verified shipped behavior only. Planned work belongs in
`handoff.md` and must be labeled unimplemented.
