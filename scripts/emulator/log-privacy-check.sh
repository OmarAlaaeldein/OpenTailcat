#!/usr/bin/env bash
# Log privacy check (review: engine logs held public IPs). With Debug failure
# reports off, restart the session and look for IP addresses in the engine's
# logcat lines (tag GoLog) for WAIT seconds (default 40). Addresses of the
# fixed public services the engine probes (Cloudflare 1.1.1.1 / 1.0.0.1 and
# their IPv6) and tunnel-internal addresses (100.64.x, fd7a:115c:a1e0::/48)
# are allowed. Output shows only masked lines and counts.
# Exits 1 if any other address appears.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
WAIT=${WAIT:-40}

adb logcat -c
adb shell am force-stop "$PKG"
"$(dirname "$0")/connect.sh" >/dev/null || { echo "could not reconnect" >&2; exit 2; }
sleep "$WAIT"

adb logcat -d -v tag -s GoLog | tr -d '\r' | python3 -c '
import re, sys
allowed = {"1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"}
v4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
v6 = re.compile(r"(?<![0-9A-Za-z:])(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}(?![0-9A-Za-z:])")
# Addresses inside the tunnel (the VPN address and Tailcat per-session ULA)
# do not reveal the device network.
tunnel_internal = lambda a: a.startswith("100.64.") or a.lower().startswith("fd7a:115c:a1e0:")
lines = [l for l in sys.stdin if re.match(r"[VDIWEF]/GoLog", l)]
bad = []
for l in lines:
    found = [a for a in v4.findall(l) + v6.findall(l)
             if a not in allowed and not tunnel_internal(a)]
    if found:
        bad.append(v6.sub("<ip6>", v4.sub("<ip4>", l.rstrip())))
print(f"GoLog lines: {len(lines)}, lines with other IP addresses: {len(bad)}")
for l in bad[:20]:
    print("  " + l)
sys.exit(1 if bad else 0)
'
