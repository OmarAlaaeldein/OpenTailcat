# Application-owned Tailcat integration

Upstream currently exposes neither its network monitor nor an Android-safe
engine initialization policy. This integration is maintained by OpenTailcat;
its additional API is **not an upstream-supported API**.

`../tailcat-integration` contains only the root library sources and license
from the unmodified `third_party/tailcat` pin, plus the small changes shown in
`tailcat.patch`. It is a local Go module replacement, not a runtime overlay.
Ordinary `go test`, static analysis and gomobile all compile the same sources.

The patch keeps netns enabled throughout Android engine initialization and
region selection, so transport sockets receive the registered protect hook
before bind/connect. Other platforms keep upstream behavior. A typed
`Client.NetworkMonitor()` accessor replaces reflection and `unsafe.Pointer`.

Regenerate with `python3 integration/sync_tailcat.py` from `core-engine`.
`python3 integration/sync_tailcat.py --check` verifies every copied source and
the reviewable patch. The engine tests and AAR builder require this check.
An upstream update requires explicit regeneration, patch review, tests and a
rebuilt AAR; it cannot silently change the private-field runtime access.

Remove this module replacement once a reviewed upstream version supplies
equivalent APIs. Do not edit files in the pinned submodule or generated copy.
