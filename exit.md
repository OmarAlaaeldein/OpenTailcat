# Exit-node mode: plan

Status: M1 (Go `exitnode` package), M2 (native bridge, rebuilt AAR, Kotlin
facade) and M3 (foreground service, notifications) are written and unit-tested.
Verified on an API 35 arm64 emulator: start, foreground service (`specialUse`),
ongoing notification, the VPN interlock, notification-to-screen routing, and
Stop (wake lock, service and notification all released). Not verified: a real
guest connecting, approval, forwarding, and power; that is the remaining gate.

Two decisions changed from the first draft; see "What changed" at the end.

OpenTailcat is a client: it consumes a `tc…` token and sends traffic to someone
else's gateway. This plan adds the inverse role — the phone becomes the
gateway, prints its own token, and shares it by QR or copy.

## Why it is feasible

The pinned library already contains the server half; this is a role flip, not a
new data plane:

- `tailcat.Server` in `core-engine/tailcat-integration/tailcat.go:412`, with
  restorable `Key` and `PresharedKey`, selectable `Region`/`DERPMapURL`, and
  `TailcatAddr()` (`:1022`) that returns the token payload directly.
- `OnTCPForward` (`:512`) and `OnUDPForward` — the exit-node hooks, wired in the
  upstream CLI at `third_party/tailcat/cmd/tailcat/tailcat.go:1476` as
  `tcpForwardTo(dst)` / `udpForwardTo(dst)`.
- `AllowClient`, `KeySet`, `DisconnectClient` (`:1009`) for admission and a real
  kill switch.
- `Client.Key` is settable, so a guest *can* present a stable identity. Today the
  client mints a fresh key per connect, so every reconnect is a new device and
  needs approval again. A per-profile client key is the follow-up that makes
  approval stick; it is a client change, not a node change.

Minting is nearly free: `NewPrivateKey` → `Server{Key, PresharedKey}` →
`Start` → `TailcatAddr()` yields the same `"tc" + Base64URL(CBOR(…))` string
that `core-engine/token.go` already parses, DERP region embedded. The only
contract change needed is the client-key pin described below.

## The decisive problem: a token is a bearer credential

The token carries the **server's** keys (`p`, `k`), not the client's, so anyone
who scans the QR exits through the user's IP and data plan. `AllowClient` sees
each client's own key, not one pre-approved key, so it cannot express "only this
one device".

Options, in preference order:

1. **Pin the client key (recommended).** Add an optional token field (`c`,
   32-byte node public key). The server's `AllowClient` admits only that key and
   rejects everything else. Sharing then means: the guest's client generates a
   key, the host enters it, the token is minted for that pair. Contract change:
   `core-engine/token.go`, `core/TokenParser.kt`,
   `core-engine/testdata/token_fixtures.json` + the differential corpus, both
   test suites, per the rule in `AGENTS.md`.
2. **Time-boxed open token.** No pin, but the server enforces `exp` itself and
   the token defaults to short-lived (e.g. 1 h). Anyone holding it may connect
   until it expires. Simpler, weaker; acceptable as an explicitly-labelled
   "temporary access" mode.

Do not ship option 2 as the only mode. `exp` is currently enforced by the
*client* (`IsExpired` in `core-engine/token.go:84`); a server that ignores it
accepts tokens forever.

**Implemented instead: approval admission** (superseding option 1). The server
asks `AllowClient` about every unknown device key and the library says rejected
clients retry about once a second. So an unknown device is parked as *pending*
and refused; the host sees it, taps Approve, and the device's next retry gets
in. No key exchange, and no token-format change: option 1's `c` field is not
needed. Open admission remains as an explicit, labelled mode, and the node
enforces its own expiry either way.

## Design

### Keys and revocation

The exit-node session uses an **ephemeral** node key and PSK, generated at
start and discarded at stop. Restoring them (which `Server` supports) would
give a stable address across restarts — that is the wrong default here:
discarding them *is* the revocation. Rotating keys is one tap ("Stop sharing"),
and every previously issued token dies with them. A "keep this address" toggle
is a later, separate decision.

### Destination policy (not in the first draft)

"Exit through my phone" must not mean "reach my phone". The upstream CLI
deliberately serves localhost; a phone shared with a stranger must not. The node
refuses loopback, RFC 1918, CGNAT (carrier-internal on cellular), unique-local,
link-local (including cloud metadata), multicast and reserved ranges, judged on
the IP the library hands the handler. There is no hostname, so there is no
resolution step to rebind. 4-in-6 and NAT64 forms are judged by the IPv4 address
they embed. `AllowLocalDestinations` exists for tests and is not exposed through
the bridge. See `core-engine/exitnode/policy.go`.

### Mode interlock

Exit node and client tunnel are mutually exclusive, hard enforced in the service
layer:

- The client role occupies the `VpnService` slot with `0.0.0.0/0` + `::/0`.
  With that tunnel up, the exit node's own outbound dials would be captured and
  re-enter the phone — a loop, and a leak of the served traffic into the user's
  tunnel.
- Enforcement: starting one refuses while the other is running, with a
  user-visible reason. Not a warning, a refusal.
- A third-party VPN being active is allowed but must be surfaced: the exit
  node's egress is then that VPN's, so the node's traffic is attributable to a
  service the user may not have intended. Mention it in the UI when detected.

### Go package

New package `core-engine/exitnode/`, never `package engine`.

`no_os_socket_test.go` parses every non-test file of the engine package and
forbids `net.Dial*`/`Listen*`. An exit node legitimately dials over device
routes, so putting it in `package engine` would force an allowlist entry and
erode safety invariant 2. A separate package keeps the rule honest; the test's
scope is documented at the top of that file.

Shape:

```go
type Config struct {
    RegionID      tailcfg.DERPRegionID
    AllowedPeer   key.NodePublic // zero = any (option 2 above)
    ExpiresAt     time.Time
    MaxTCP, MaxUDP int
}

type Node struct { /* *tailcat.Server, counters, limits */ }

func Start(cfg Config) (*Node, error)   // generates ephemeral key + PSK
func (n *Node) Token() string            // TailcatAddr()
func (n *Node) StatsJSON() string        // peers, bytes, uptime, expiry
func (n *Node) DisconnectPeer(key.NodePublic) bool
func (n *Node) Close() error             // idempotent, bounded
```

- `OnTCPForward`/`OnUDPForward` dial the requested destination over device
  routes and proxy both directions; half-close forwarding, bounded buffers,
  per-peer and global connection caps.
- ICMP has no forwarding path in the library. Reply locally to echo or drop and
  count, exactly as the client does; do not synthesize.
- Missing dest, bad address family, or a dial failure closes the flow — no
  synthesized success.

### Bridge exports

Additions to `core-engine/bridge.go`, each recovering panics as errors like
every other export:

- `startExitNode(cfgJSON) error`
- `exitNodeToken() (string, error)`
- `exitNodeStatsJSON() (string, error)`
- `exitNodeDisconnectPeer(pubHex) (bool, error)`
- `stopExitNode() error`

AAR rebuild is mandatory in the same change (`core-engine/build-aar.sh`), with
`app/libs/libtailcat.aar` and its `.sourcehash`, per `AGENTS.md`.

### Android

No `VpnService`, no consent dialog, no TUN: the node is an ordinary foreground
service with UDP sockets. That is a genuine simplification, and also why the
feature does not inherit the client's leak surface.

- `ExitNodeService`: `startForeground` with peer count, served bytes, expiry
  countdown, and a Stop action. Foreground service type must be chosen for
  API 34+ (`specialUse` with a subtype property is the likely fit; `dataSync`
  is a stretch). Needs verification on API 35/37.
- Token screen: reuse the existing secure-dialog pattern
  (`SecureFlagPolicy.SecureOn`) and the ZXing QR renderer from pairing. Copy
  button, expiry time, "anyone with this code can use your connection" warning,
  and the pinned-peer fingerprint when the pin is in use.
- Keys never leave the app except inside the displayed token; no logs, no
  clipboard side effects beyond the explicit Copy press (invariant 10).
- Battery/doze: `PARTIAL_WAKE_LOCK` scoped to the session, released in
  `onDestroy`; measure before deciding. A Wi-Fi-only default is worth
  considering.

## Work breakdown

| Milestone | Content | Est. |
| --- | --- | --- |
| M1 | `exitnode` package: server, forwarders, minting, limits, expiry; in-process tests. **Done** (incl. real client-to-node test over a local DERP) | 5–7 d |
| M2 | Bridge exports, panic recovery, AAR rebuild, Kotlin facade (`TunnelExitNode`). **Done**; native mutual exclusion with the client engine added | 2–3 d |
| M3 | `ExitNodeService`, share screen, interlock. **Done** (foreground service of type `specialUse`, ongoing + request-alert + ended notifications, session-scoped wake lock); not yet measured on a device | 5–7 d |
| M4 | Stable per-profile client key, per-peer caps, TCP idle timeout, battery measurement | 3–5 d |
| M5 | Docs (`AGENTS.md`, `README.md`, Fastlane text), release 1.7.0, F-Droid metadata bump | 2 d |

Roughly four focused weeks. M1 is the only part with real unknowns, and it is
testable without Android.

## Testing

- **Unit (Go):** mint → parse round-trip through the existing parser;
  `AllowClient` rejects wrong peer and expired token; forwarder proxies TCP and
  UDP to a local listener; caps enforced; `Close` idempotent and bounded.
- **Desktop pair:** two processes over DERP, one client one exit node, TCP +
  UDP + DNS; compare against `tailcat serve exit-node` behaviour.
- **Emulator pair:** two AVDs, token by QR, real forwarding while the screen is
  off; asserts byte counters move and the notification stays accurate.
- **Negative:** client-mode start refused while serving and vice versa; expired
  token rejected by the server, not just the client; killed session drops
  established flows.
- **Power:** 30-minute idle and active sessions, measure drain and thermal
  throttling. This is the acceptance gate for the feature, not an afterthought.

## Risks

| Risk | Mitigation |
| --- | --- |
| Token leaks → stranger's traffic on the user's IP | Client-key pin, short `exp` enforced server-side, one-tap key rotation |
| Abuse / carrier ToS / legal exposure | Prominent warning, peer count visible, no "share publicly" affordance, `README` limits statement |
| Doze and battery make it unusable | Measure early (M1 desktop cannot answer this; M3 must), Wi-Fi-only default if needed |
| DERP-only throughput is poor | Measure; if unacceptable, document "direct connections preferred" rather than hide it |
| Loop when both modes run | Hard interlock plus a test that asserts refusal |
| Invariant erosion (`no_os_socket_test.go`) | Separate package, no allowlist entry |
| F-Droid review churn | Ship after 1.6.5 inclusion, as 1.7.0 through `AutoUpdateMode` |

## Sequencing

1. Land the current 1.6.5 submission (MR 57709, pinned to `5d43afe`). This plan
   must not disturb it: no changes to the pinned commit, the metadata, or the
   Fastlane tree until that MR is resolved.
2. M1 on the desktop. If minting, admission, and forwarding do not behave, stop
   before spending Android effort.
3. M2–M3 to prove battery and UX on a device.
4. M4 hardening, then 1.7.0.

## Open decisions

1. Client-key pin as the default (`c` field), or time-boxed open tokens first?
2. Ephemeral keys only, or a "keep this address" option for repeat guests?
3. Wi-Fi-only by default, or allow cellular with a data-cost warning?
4. Foreground service type and whether a wake lock is acceptable at all.
5. Does the exit node belong in this app, or in a sibling build? It changes the
   store listing's threat model from "connects to a gateway you run" to "can be
   a gateway".

## What this does not change

The client path — TUN, gVisor, `Client.DialTCP`/`DialUDP`, DNS policy, MTU
handling, telemetry, the updater, and every safety invariant in `AGENTS.md` —
stays as it is. Exit-node code does not enter `netstack_proxy.go`,
`lifecycle.go`, or `TunnelController`'s client state machine; it gets its own
lifecycle and its own tests.

## What changed from the first draft

1. **Approval admission replaced the client-key pin.** Same goal (a leaked token
   alone is not enough), no token-contract change, no key exchange.
2. **A destination policy was added.** The first draft protected the host's data
   plan but not the host's own network.

## Known gaps before this can ship

- **DNS failover in the engine resolver is partial (client path too).** Found on
  the emulator: Android's primary DNS (10.0.2.3) never answers, and the engine's
  resolver used to dial only the first server, so a node start timed out
  fetching the DERP map. `main.go` now prefers the server that last answered and
  moves on after a read timeout (`dnsServerConn`, `dns_rotation_test.go`). It is
  not complete: with a flaky good server one timeout moves the preference back
  to a dead one, costing one more timeout. The complete fix is hedged queries to
  all servers. Boot the emulator with `-dns-server 8.8.8.8,8.8.4.4` to test
  without depending on it.
- **Wake lock is a guess until measured.** The service holds a `PARTIAL_WAKE_LOCK`
  for the session (timeout = TTL + 60 s). Whether it is needed, and what it
  costs in battery and heat, is the 30-minute power test, still to do.
- **Always-on VPN with "Block connections without VPN"** blocks this app's own
  sockets while the VPN is off, which would stop the node dialing out. It cannot
  be detected beforehand (Android only reports it to a running VpnService), so
  the setup screen says so. A guest whose flows all fail with zero bytes moved
  is the symptom; `dialFailures` in the stats shows it.
- **The interlock is a refusal on both sides plus a one-poll backstop**, not a
  proof. Add it to the TLA+ model in `formal/` with the lifecycle change.
- **TCP flows have no idle timeout**, only the cap and the session expiry.
- **The host cannot verify who is asking.** The guest app shows no device ID, so
  Approve rests on the host watching the person connect. The UI warns when more
  than one device asks at once, which is the visible symptom of a leaked code.
- **Changing `core-engine/` changes the native source hash.** Nothing here can be
  committed until the AAR and `.sourcehash` are rebuilt, or CI fails.
- `go.mod` gained four indirect requirements and `go.sum` was tidied, because the
  end-to-end test runs an in-process DERP relay (`derpserver`).
