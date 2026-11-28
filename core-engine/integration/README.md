# Tailcat Android integration

`core-engine/tailcat-integration` is a generated local Go module replacement for `github.com/tailscale/tailcat`, built from the unmodified `third_party/tailcat` submodule plus the small patch in `tailcat.patch`.

What the patch changes:

- Keeps `netns.SetEnabled(true)` on Android during engine initialization and DERP region selection (`opentailcat_android.go` / `opentailcat_other.go`), so STUN, DERP, and Magicsock sockets run the registered `VpnService.protect` callback at creation before bind/connect. Non-Android builds keep upstream behavior.
- Adds a typed `Client.NetworkMonitor()` method so `core-engine` can trigger Magicsock link-change rebinds without reflection or `unsafe.Pointer`.
- Keeps client initialization failing when `netmon.New` fails. Since v0.8.0 upstream silently substitutes a static monitor that never reports network changes; the patch restores the earlier `netmon.New: ...` error for `Client` only, so `Prepare` still fails closed ("Missing monitor support fails `Prepare`"). The `Server` (exit node) keeps upstream's fallback. No automated test forces a `netmon.New` failure, so re-read this hunk on every upstream update.
- Adds `tailcat.DERPMapFilter`, called on every DERP map decoded from JSON (fetched or cached), so `derp_filter.go` can vet and prune endpoints whatever URL or redirect they came from.
- Records a `DisconnectClient` that lands while `AllowClient` is still running for the same key (`removedAllow`), so the disconnected client is not added once the hook returns.

Regenerate and check from `core-engine/`:

```bash
python3 integration/sync_tailcat.py         # regenerate tailcat-integration/ and tailcat.patch
python3 integration/sync_tailcat.py --check # verify files match submodule + patch
```

Both `go test` and `core-engine/build-aar.sh` run `--check`. Do not edit `third_party/tailcat` or `tailcat-integration/` directly; update `sync_tailcat.py`, regenerate, and rebuild the AAR. Once upstream Tailcat exposes these hooks natively, this module replacement can be removed.
