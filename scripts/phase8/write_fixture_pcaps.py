#!/usr/bin/env python3
"""Write classic Ethernet pcap fixtures for phase8-analyze self-checks.

The uplink normally carries the device's WireGuard packets to the tunnel peer
(seconds 0-9); the gateway normally carries decrypted probe traffic from its
tunnel-side address to the probe (seconds 2-6) plus its own DNS to the probe.

Modes:
  --mode pass            clean uplink + gateway with probe present (PASS)
  --mode leak            uplink also carries device -> probe (FAIL)
  --mode dns-leak        uplink also carries device DNS to a LAN resolver (FAIL)
  --mode no-gw-probe     gateway lacks probe dest (FAIL)
  --mode gw-dns-only     gateway only has its own DNS to the probe (FAIL)
  --mode no-tunnel       uplink never shows the device's tunnel traffic (INCONCLUSIVE)
  --mode stale-gateway   gateway capture is an hour after the uplink (INCONCLUSIVE)

Synthetic frames only — never live traffic. Not Phase 8 acceptance.
"""
from __future__ import annotations

import argparse
import struct
import sys

T0 = 1_790_000_000
GATEWAY_TUNNEL_IP = "172.16.0.2"
LAN_RESOLVER = "192.168.2.1"
OTHER_HOST = "10.0.0.7"


def frame_ipv4(src: str, dst: str, proto: int = 17, sport: int = 0, dport: int = 0) -> bytes:
    def ip4(s: str) -> bytes:
        return bytes(int(p) for p in s.split("."))

    l4 = {6: 20, 17: 8}.get(proto, 0)
    ip = bytearray(20 + l4)
    ip[0] = 0x45
    ip[2:4] = struct.pack("!H", len(ip))
    ip[8] = 64
    ip[9] = proto
    ip[12:16] = ip4(src)
    ip[16:20] = ip4(dst)
    if l4:
        ip[20:24] = struct.pack("!HH", sport, dport)
    eth = bytearray(14)
    eth[12:14] = b"\x08\x00"
    return bytes(eth) + bytes(ip)


def write_pcap(path: str, records: list[tuple[int, bytes]]) -> None:
    hdr = struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 0x40000, 1)
    body = b"".join(
        struct.pack("<IIII", T0 + sec, 0, len(f), len(f)) + f for sec, f in records
    )
    with open(path, "wb") as f:
        f.write(hdr + body)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument(
        "--mode",
        choices=("pass", "leak", "dns-leak", "no-gw-probe", "gw-dns-only", "no-tunnel", "stale-gateway"),
        required=True,
    )
    p.add_argument("--uplink", required=True)
    p.add_argument("--gateway", required=True)
    p.add_argument("--probe", default="1.1.1.1")
    p.add_argument("--peer", default="203.0.113.9")
    p.add_argument("--device", default="10.0.0.2")
    args = p.parse_args()

    tunnel = [(s, frame_ipv4(args.device, args.peer, 17, 41641, 41641)) for s in range(10)]
    probe_hits = [(s, frame_ipv4(GATEWAY_TUNNEL_IP, args.probe, 6, 40000, 443)) for s in range(2, 7)]
    gateway_dns = [(3, frame_ipv4(GATEWAY_TUNNEL_IP, args.probe, 17, 5353, 53))]

    uplink = tunnel
    gateway = probe_hits + gateway_dns
    if args.mode == "leak":
        uplink = tunnel + [(3, frame_ipv4(args.device, args.probe, 6, 50000, 443))]
    elif args.mode == "dns-leak":
        uplink = tunnel + [(4, frame_ipv4(args.device, LAN_RESOLVER, 17, 5353, 53))]
    elif args.mode == "no-gw-probe":
        gateway = [(s, frame_ipv4(GATEWAY_TUNNEL_IP, "198.51.100.7", 6, 40000, 443)) for s in range(2, 7)]
    elif args.mode == "gw-dns-only":
        gateway = gateway_dns
    elif args.mode == "no-tunnel":
        uplink = [(s, frame_ipv4(OTHER_HOST, "198.51.100.20", 6, 50000, 443)) for s in range(10)]
    elif args.mode == "stale-gateway":
        gateway = [(s + 3600, f) for s, f in probe_hits]

    write_pcap(args.uplink, uplink)
    write_pcap(args.gateway, gateway)
    return 0


if __name__ == "__main__":
    sys.exit(main())
