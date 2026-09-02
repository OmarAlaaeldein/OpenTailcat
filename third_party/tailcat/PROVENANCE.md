# Upstream Provenance: third_party/tailcat

## Upstream Base
- **Repository**: `github.com/tailscale/tailcat`
- **Signed Upstream Release**: `v0.4.0`
- **Base Commit**: `ce6fedcabc220bab3b94d470ab330219111eeae8`
- **License**: BSD 3-Clause License

## Local Fork / Delta
- **Pinned Local Commit**: `49c65dace2d79b41d89f536289002816d13e5274`
- **Status**: Local fork with Phase 1–3 remediations. **Must not be described as the untouched signed upstream tag.**

## Modifications in Commit 49c65da

1. **Server Network Monitor Fallback (`Server.Start`)**:
   - Replaces hard error on `netmon.New` failure with fallback to `netmon.NewStatic()`.

2. **Embedded DERP Map Fallback (`fetchDERPMap`)**:
   - Embeds default fallback DERP snapshot (`defaultDERPMapJSON`) for regions 301 (NYC), 302 (SFO), 303 (FRA), and 304 (TOK).

3. **Region Alias Mapping (`ConnInfo.Expand`)**:
   - Maps region IDs 1->301, 2->302, 3->303, 4->304 when resolving region aliases against the DERP map.

4. **Client Network Monitor Fallback (`Client.initLocked`)**:
   - Replaces hard error on client `netmon.New` failure with fallback to `netmon.NewStatic()`.

## Post-49c65da Audited Remediation (Phase 1)
- Added explicit warning log messages to `fetchDERPMap` so that selecting cached or embedded static fallback DERP maps produces observable diagnostics instead of silent relay staleness.
- Added `(c *Client) NetMon() *netmon.Monitor` accessor method to wire live network roaming event injection from Android into the active network monitor.

## Post-49c65da Audited Remediation (Phase 3: Tunneled UDP Data Plane)
- Added exit capability bit constants (`CapExitTCP = 0x01`, `CapExitUDP = 0x02`) and capability encoding/decoding helper functions `EncodeMeowedWithCaps` and `ParseMeowedCaps` in `disco.go`.
- Server advertises `CapExitTCP | CapExitUDP` in `meowed` ping-pong handshake; Client stores and checks capabilities via `(c *Client) HasServerCap(cap uint8) bool`.
- Wired `dialer.NetstackDialUDP` in both client and server to `ns.DialContextUDP(ctx, dst)` instead of panicking.
- Exported `(c *Client) DialUDP(ctx context.Context, ap netip.AddrPort) (net.Conn, error)` with NAT64 mapping for IPv4 destinations to route datagrams over WireGuard to the exit gateway.
- Added `Server.OnUDPForward func(netip.AddrPort) (handler func(nettype.ConnPacketConn))` and wired `ns.GetUDPHandlerForFlow` to forward tunneled UDP packets in exit-node mode.
- Enforced `s.AllowProxy != nil && !s.AllowProxy(dst)` for both incoming TCP and UDP exit flows.
- Widened server packet filter to admit `ipproto.UDP` when `OnUDPForward` is configured.
- Added `udpForwardTo` handler in `cmd/tailcat/tailcat.go` for `--serve=exit-node` to forward datagrams with preserved zero-length and full-size boundaries.
- Added `TestServeExitNodeUDP` and `TestAllowProxyRejection` in `cmd/tailcat/serve_test.go`.

## Mechanical Patch Generation & Reproduction
To generate and verify the unified patch from the submodule's Git database against upstream `v0.4.0`:
```bash
git --git-dir=.git/modules/third_party/tailcat --work-tree=third_party/tailcat diff --no-ext-diff --binary ce6fedcabc220bab3b94d470ab330219111eeae8 > third_party/tailcat/upstream-v0.4.0-to-phase3.patch
```

### Patch Checksum
- **File**: `third_party/tailcat/upstream-v0.4.0-to-phase3.patch`
- **SHA-256**: `aea934cf3a7c034def3624a44c6a58ec01909697cea31b65be1936ff27b91d9c`
