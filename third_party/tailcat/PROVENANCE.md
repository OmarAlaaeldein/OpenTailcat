# Upstream Provenance: third_party/tailcat

## Upstream Base
- **Repository**: `github.com/tailscale/tailcat`
- **Signed Upstream Release**: `v0.4.0`
- **Base Commit**: `ce6fedcabc220bab3b94d470ab330219111eeae8`
- **License**: BSD 3-Clause License

## Local Fork / Delta
- **Pinned Local Commit**: `49c65dace2d79b41d89f536289002816d13e5274`
- **Status**: One commit after upstream `v0.4.0`. **This is an explicit local fork and must not be described as the untouched signed upstream tag.**
- **Exact Commit Delta**: Generated directly from Git objects (`git --git-dir=.git/modules/third_party/tailcat diff ce6fedc... 49c65da...`). Modifies only `tailcat.go` with 14 insertions and 4 deletions (see `upstream-v0.4.0-to-local-49c65da.patch`).

## Modifications in Commit 49c65da

1. **Server Network Monitor Fallback & `lb.Close()` removal (`Server.Start`)**:
   - Replaces `lb.Close()` and hard error on `netmon.New` failure with fallback to `netmon.NewStatic()`.

2. **Embedded DERP Map Fallback (`fetchDERPMap`)**:
   - Embeds default fallback DERP snapshot (`defaultDERPMapJSON`) for regions 301 (NYC), 302 (SFO), 303 (FRA), and 304 (TOK).

3. **Region Alias Mapping (`ConnInfo.Expand`)**:
   - Maps region IDs 1->301, 2->302, 3->303, 4->304 when resolving region aliases against the DERP map.

4. **Client Network Monitor Fallback & `lb.Close()` removal (`Client.initLocked`)**:
   - Replaces `lb.Close()` and hard error on client `netmon.New` failure with fallback to `netmon.NewStatic()`.

## Post-49c65da Audited Remediation (Phase 1)
- Added explicit warning log messages to `fetchDERPMap` so that selecting cached or embedded static fallback DERP maps produces observable diagnostics instead of silent relay staleness.
- Added `(c *Client) NetMon() *netmon.Monitor` accessor method to wire live network roaming event injection from Android into the active network monitor.

## Reproducing the Diff
To review the exact commit patch mechanically extracted from the Git object store:
```bash
git --git-dir=.git/modules/third_party/tailcat diff --no-ext-diff --binary ce6fedcabc220bab3b94d470ab330219111eeae8 49c65dace2d79b41d89f536289002816d13e5274
```
