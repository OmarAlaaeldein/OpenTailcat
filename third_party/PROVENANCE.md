# Upstream Provenance: third_party/tailcat

`third_party/tailcat` is a git submodule of the fork
https://github.com/OmarAlaaeldein/tailcat, itself derived from
https://github.com/tailscale/tailcat.

## Upstream Base

- **Repository**: `github.com/tailscale/tailcat`
- **Signed upstream release used as the original Android pin**: `v0.4.0`
  (`ce6fedcabc220bab3b94d470ab330219111eeae8`)
- **Current upstream pin**: `0c31395bfd1ae0c0ef2917c0ec20432466087417`
  (includes application-layer UDP: `Client.DialUDP` / `Server.OnUDPForward`)
- **License**: BSD 3-Clause License

## Fork commit

- **Pinned submodule commit**: `f56854491e2c6519ab060722e14d4097c701889b`
- **Must not be described as the untouched signed upstream tag.**

The fork commit is upstream `main` at `0c31395` plus Android-only patches:

1. `netmon.New` failure falls back to `netmon.NewStatic()` (SELinux/Android).
2. Embedded DERP map fallback with observable warning logs.
3. Region aliases `1->301`, `2->302`, `3->303`, `4->304`.
4. `Client.NetMon()`, `Client.Status()`, `Client.ServerNodeKey()`, `Client.DERPMap()`.

Application UDP uses upstream `DialUDP` / `OnUDPForward`. Do not reintroduce
application-flow `net.DialUDP` in `core-engine`.
