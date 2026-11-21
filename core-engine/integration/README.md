# Tailcat Android integration

`core-engine/tailcat-integration` is a generated local Go module replacement for `github.com/tailscale/tailcat`, built from the unmodified `third_party/tailcat` submodule plus the small patch in `tailcat.patch`.

What the patch changes:

- Keeps `netns.SetEnabled(true)` on Android during engine initialization and DERP region selection (`opentailcat_android.go` / `opentailcat_other.go`), so STUN, DERP, and Magicsock sockets run the registered `VpnService.protect` callback at creation before bind/connect. Non-Android builds keep upstream behavior.
- Adds a typed `Client.NetworkMonitor()` method so `core-engine` can trigger Magicsock link-change rebinds without reflection or `unsafe.Pointer`.

Regenerate and check from `core-engine/`:

```bash
python3 integration/sync_tailcat.py         # regenerate tailcat-integration/ and tailcat.patch
python3 integration/sync_tailcat.py --check # verify files match submodule + patch
```

Both `go test` and `core-engine/build-aar.sh` run `--check`. Do not edit `third_party/tailcat` or `tailcat-integration/` directly; update `sync_tailcat.py`, regenerate, and rebuild the AAR. Once upstream Tailcat exposes these hooks natively, this module replacement can be removed.
