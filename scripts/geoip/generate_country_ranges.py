#!/usr/bin/env python3
"""Generate app/src/main/assets/country_ranges.txt from the five RIR statistics.

The Regional Internet Registries (APNIC, RIPE NCC, ARIN, LACNIC, AFRINIC)
publish "delegated" statistics files that list every allocated or assigned
IP range with an ISO 3166-1 country code. This script merges them into one
compact text asset that the app bundles. The app never looks destinations up
online: the asset is the only source, so what a country filter blocks is
exactly what is committed here and reviewable.

Output format (format 1), one line per country, sorted by code:

    # comment lines start with "#"
    CN 1.0.1.0/24 1.0.2.0/23 ... 2001:250::/30 ...
    KP 175.45.176.0/22

IPv4 prefixes come first, then IPv6, each sorted by address and collapsed to
the fewest CIDR blocks. The output is deterministic for a given input: it
contains the registries' own serial numbers and no wall-clock timestamp.

Caveat that must stay visible to users: the registries record where a
resource was first allocated, not where it is used today. CDN, anycast and
cloud ranges are registered to their operator's country. Treat the result as
a best-effort filter, never as a guarantee of where a server is.

Usage:
    python3 scripts/geoip/generate_country_ranges.py            # download + write
    python3 scripts/geoip/generate_country_ranges.py --from-dir build/geoip
        # reuse files fetched earlier (the download step saves them there)

Run it from the repository root. Needs network access unless --from-dir is
given. Only the Python standard library is used.
"""
import argparse
import ipaddress
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "app/src/main/assets/country_ranges.txt"
DEFAULT_CACHE = ROOT / "build/geoip"

FORMAT_VERSION = 1

# (registry name in the file's version line, local file name, URL)
SOURCES = [
    ("apnic", "delegated-apnic-latest",
     "https://ftp.apnic.net/stats/apnic/delegated-apnic-latest"),
    ("ripencc", "delegated-ripencc-latest",
     "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest"),
    ("arin", "delegated-arin-extended-latest",
     "https://ftp.arin.net/pub/stats/arin/delegated-arin-extended-latest"),
    ("lacnic", "delegated-lacnic-latest",
     "https://ftp.lacnic.net/pub/stats/lacnic/delegated-lacnic-latest"),
    ("afrinic", "delegated-afrinic-latest",
     "https://ftp.afrinic.net/pub/stats/afrinic/delegated-afrinic-latest"),
]

# Mirrors the engine's validation (core-engine/country_block.go): a prefix
# shorter than these cannot be a real per-country delegation, and the engine
# refuses a list that contains one. Failing here keeps a bad asset out of git.
MIN_V4_BITS = 4
MIN_V6_BITS = 8

COUNTRY = re.compile(r"^[A-Z]{2}$")


def fetch(cache_dir: Path) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    for name, filename, url in SOURCES:
        print(f"fetching {url}", file=sys.stderr)
        request = urllib.request.Request(url, headers={"User-Agent": "opentailcat-geoip-generator"})
        with urllib.request.urlopen(request, timeout=300) as response:
            (cache_dir / filename).write_bytes(response.read())


def parse(path: Path, registry: str, v4, v6):
    """Adds one file's allocated/assigned records to the per-country lists.

    Returns the file's version line fields for the output header.
    """
    version = None
    with path.open(encoding="ascii", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            fields = line.split("|")
            if version is None:
                # version|registry|serial|records|startdate|enddate|UTCoffset
                if len(fields) < 7 or fields[1] != registry:
                    raise SystemExit(f"{path}: unexpected version line {line!r}")
                version = fields
                continue
            if len(fields) < 7 or fields[0] != registry:
                continue  # summary lines (registry|*|type|*|count|summary)
            _, country, kind, start, value, _, status = fields[:7]
            if kind not in ("ipv4", "ipv6") or status not in ("allocated", "assigned"):
                continue
            if not COUNTRY.match(country) or country == "ZZ":
                continue
            if kind == "ipv4":
                first = ipaddress.IPv4Address(start)
                last = ipaddress.IPv4Address(int(first) + int(value) - 1)
                v4[country].extend(ipaddress.summarize_address_range(first, last))
            else:
                v6[country].append(ipaddress.IPv6Network(f"{start}/{value}"))
    if version is None:
        raise SystemExit(f"{path}: empty or unrecognized statistics file")
    return version


def build(input_dir: Path) -> str:
    v4, v6 = defaultdict(list), defaultdict(list)
    sources = []
    for registry, filename, _ in SOURCES:
        path = input_dir / filename
        if not path.is_file():
            raise SystemExit(f"missing {path}; run without --from-dir to download it")
        version = parse(path, registry, v4, v6)
        sources.append(f"{registry} serial={version[2]} end={version[5]} records={version[3]}")

    lines = [
        f"# OpenTailcat country ranges, format {FORMAT_VERSION}",
        "# One line per country: ISO 3166-1 alpha-2 code, then space-separated CIDR prefixes",
        "# (IPv4 first, then IPv6), merged across registries and collapsed to the fewest blocks.",
        "# Source: RIR delegated statistics (allocated and assigned records only). They show where",
        "# a range was first allocated, not where it is used: this is a best-effort filter.",
        "# APNIC: files freely available for use; no responsibility for loss or damage from use.",
    ]
    lines += [f"# source: {text}" for text in sources]
    lines.append("# Generated by scripts/geoip/generate_country_ranges.py; do not edit by hand.")

    total4 = total6 = 0
    for country in sorted(set(v4) | set(v6)):
        prefixes = []
        for net in sorted(ipaddress.collapse_addresses(v4[country])):
            if net.prefixlen < MIN_V4_BITS:
                raise SystemExit(f"{country}: IPv4 prefix {net} is shorter than /{MIN_V4_BITS}")
            prefixes.append(str(net))
            total4 += 1
        for net in sorted(ipaddress.collapse_addresses(v6[country])):
            if net.prefixlen < MIN_V6_BITS:
                raise SystemExit(f"{country}: IPv6 prefix {net} is shorter than /{MIN_V6_BITS}")
            prefixes.append(str(net))
            total6 += 1
        lines.append(f"{country} {' '.join(prefixes)}")
    print(f"{len(lines)} lines; {total4} IPv4 and {total6} IPv6 prefixes", file=sys.stderr)
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--from-dir", type=Path,
                        help="read already-downloaded statistics files instead of fetching them")
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE,
                        help="where downloaded files are saved (default: build/geoip)")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    input_dir = args.from_dir
    if input_dir is None:
        fetch(args.cache_dir)
        input_dir = args.cache_dir
    text = build(input_dir)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="ascii")
    print(f"wrote {args.out} ({len(text)} bytes)", file=sys.stderr)


if __name__ == "__main__":
    main()
