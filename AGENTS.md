# OpenTailcat Android engineering guide

> Project root: `/Users/omar/Developer/OpenTailcat`  
> Android: min API 26, compile/target API 35  
> Android toolchain: Kotlin 2.2.10, AGP 9.3.0, Gradle 9.5.0, JDK 21  
> Native toolchain in `core-engine/go.mod`: Go 1.27.0

Read this file and `handoff.md` completely before changing code.
`handoff.md` is the authoritative, detailed implementation and test plan.

## Current audited status

The repository is **not production-ready**. IPv4 test-routing capabilities are
true so a live token can Connect. `ipv6` stays false. Phase 8 physical leak
acceptance is unimplemented.

The checked-in AAR is built reproducibly with Go 1.27.0, NDK r29 (29.0.14206865),
16 KB ELF load alignment, and verified Java signatures.

Audit snapshot: version 1.1.9 on `main`. IPv4 Connect is test-enabled. `ipv6` is false.

Critical current behavior:

- IPv4 TCP: gVisor -> Tailcat `Client.DialTCP` -> gateway.
- UDP/53: gVisor netstack proxies datagrams via `Client.DialUDP` to the TUN
  destination (PROFILE_RESOLVER) or `ForcedDNS` (FORCED_RESOLVER). The engine
  does not inspect DNS TC bits; a libc/app TCP/53 retry is a normal TCP proxy.
- Other IPv4 UDP: gVisor netstack proxies datagrams via `Client.DialUDP` across
  Tailcat netstack (no application-flow `net.DialUDP` in `core-engine`).
- IPv6: after `prepare`, Android installs `fd7a:115c:a1e0::2/128` and `::/0` on
  the same TUN as IPv4 default routes, then `attachTun`. Native `handleIPv6`
  proxies TCP/UDP with a 250ms dial timeout so dual-stack apps can fall back to
  tunneled IPv4; ICMPv6 echo is dropped; oversized IPv6 gets a local Packet Too
  Big; oversized IPv4 gets Fragmentation Needed. `ipv6` stays false.
- ICMP echo: IPv4 answered locally. ICMPv6 echo is dropped.
- Speed test: when CONNECTED, ping/download/upload use `Client.DialTCP` through
  the gateway (`speed.cloudflare.com` is resolved with OS DNS on the app UID);
  otherwise the app-UID physical path.
- Telemetry: schema version 2. WireGuard peer Tx/Rx and Magicsock path come from
  live `Client.Status()` when a bridge is running. RTT is sampled from
  `DiscoPing` about every 5s while a bridge is running; jitter is null until
  three samples. Kotlin rejects v1 and requires `RUNNING` plus fresh
  `healthUnixSec` for CONNECTED. `liveStats` is test-enabled.
- Capabilities: API v2 IPv4 test-routing flags true; `ipv6` false. After
  `prepare`, Android installs `0.0.0.0/0` and `::/0`, then `attachTun`.
- Tests: unit, integration, race, lint, and build tests pass; complete live
  physical hardware tunnel test pending.

Do not call this build secure, protected, complete, production-ready, or
leak-free. Do not publish or sign it as a VPN release.

## Active implementation focus

1. Phase 5 dual-stack: live IPv6 internet egress, or keep `ipv6` false. Do not
   promote `ipv6` yet.
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

Data-plane interoperability still requires a compatible gateway. Official
Tailcat v0.4.0 `serve exit-node` is TCP-only: it configures `OnTCPForward`, its
filter admits TCP, and the client-side `NetstackDialUDP` is an unreachable
panic. This embedded tree already replaces that panic, exports `Client.DialUDP`,
and adds CLI `OnUDPForward`. If the live `nullexit` gateway already supports
native tunneled UDP, prove and version that capability. Otherwise a matching
gateway-side Tailcat UDP extension/deployment is required. A client-only direct
socket is never an acceptable substitute.

## Upstream provenance

`third_party/tailcat` is an embedded tree derived from:

- signed upstream `v0.4.0`:
  `ce6fedcabc220bab3b94d470ab330219111eeae8`; plus
- local commit:
  `49c65dace2d79b41d89f536289002816d13e5274`.

The local commit adds a static netmon fallback, embedded DERP-map fallback, and
region aliases. Later audited work adds UDP dial/forward, capability bits, DERP
fallback warnings, and `Client.NetMon()`. Preserve and document that delta
explicitly. Do not claim the embedded source is the untouched signed tag. The
fabricated `android0`/`10.0.2.15` interface is gone; Android now supplies
LinkProperties via `updateNetworkState`. The hard-coded DERP map remains.
Roaming still belongs to Phase 6.

## Required implementation order

Follow the detailed methods and acceptance conditions in `handoff.md`:

1. [x] Phase 0: Restore fail-closed capability negotiation.
2. [x] Phase 1: Make upstream/native provenance and AAR builds reproducible.
3. [x] Phase 2: Align Kotlin and Go token parsing and reject connect-time legacy tokens lacking disco keys.
4. [x] Phase 3: Add native Tailcat UDP using userspace netstack; delete application-flow `net.DialUDP`.
5. [ ] Phase 4: DNS routing code exists; IPv4 `dns` is test-enabled until Phase 8 evidence.
6. [ ] Phase 5: IPv6 TCP/UDP is proxied with `::/0` after `prepare` then `attachTun`; `ipv6` stays false until live egress evidence.
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
- Go duplicates the supplied TUN FD; Android owns the original.
- `stop` is concurrent-idempotent and waits with a 3s bound.
- `detachTun` stops pumps, keeps the prepared client, and returns to `PREPARED`.
- `disarmPumps` clears pump-failure without stopping the session.
- After `prepare`, Android establishes one TUN with `0.0.0.0/0` and `::/0`, then
  `attachTun`. The VPN service is `START_STICKY` with `stopWithTask=false`.
  Shutdown closes the TUN before native `stop`. The UI resyncs CONNECTED from
  live `getStatsJSON`.

## Token contract

Official connectable tokens require:

```text
"tc" + Base64URL(CBOR({
  "p": 32-byte server node public key,
  "k": 32-byte server disco public key,
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
9. The app UID bypasses the VPN; in-process HTTP/UDP is direct unless explicitly
   carried by `Client.DialTCP`/`Client.DialUDP`.
10. Keep secrets, live tokens, signing keys, and traffic captures out of source
    control and public logs.

These invariants are the release bar. Current IPv4 test-routing installs
`0.0.0.0/0` and `::/0` after `prepare` and before `attachTun`; a brief window
exists until pumps are live.

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
source.

## Documentation rule

README, privacy, security, third-party notices, UI copy, notifications, and
release notes describe verified shipped behavior only. Planned work belongs in
`handoff.md` and must be labeled unimplemented.
