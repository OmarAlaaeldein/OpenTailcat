#!/usr/bin/env python3
"""Write classic Ethernet pcap fixtures for phase8-analyze self-checks.

Modes:
  --mode pass     clean uplink (peer only) + gateway with probe present
  --mode leak     uplink contains probe dest (FAIL path)
  --mode no-gw-probe  gateway lacks probe dest (FAIL path)

Synthetic frames only — never live traffic. Not Phase 8 acceptance.
"""
from __future__ import annotations

import argparse
import struct
import sys


def frame_ipv4(src: str, dst: str, proto: int = 17) -> bytes:
    def ip4(s: str) -> bytes:
        return bytes(int(p) for p in s.split("."))

    ip = bytearray(20)
    ip[0] = 0x45
    ip[2:4] = struct.pack("!H", 20)
    ip[8] = 64
    ip[9] = proto
    ip[12:16] = ip4(src)
    ip[16:20] = ip4(dst)
    eth = bytearray(14)
    eth[12:14] = b"\x08\x00"
    return bytes(eth) + bytes(ip)


def write_pcap(path: str, frames: list[bytes]) -> None:
    hdr = struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 0x40000, 1)
    body = b"".join(struct.pack("<IIII", 0, 0, len(f), len(f)) + f for f in frames)
    with open(path, "wb") as f:
        f.write(hdr + body)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--mode", choices=("pass", "leak", "no-gw-probe"), required=True)
    p.add_argument("--uplink", required=True)
    p.add_argument("--gateway", required=True)
    p.add_argument("--probe", default="1.1.1.1")
    p.add_argument("--peer", default="203.0.113.9")
    args = p.parse_args()

    probe_frame = frame_ipv4("10.0.0.2", args.probe)
    peer_frame = frame_ipv4("10.0.0.2", args.peer)

    if args.mode == "pass":
        write_pcap(args.uplink, [peer_frame])
        write_pcap(args.gateway, [probe_frame])
    elif args.mode == "leak":
        write_pcap(args.uplink, [probe_frame])
        write_pcap(args.gateway, [probe_frame])
    else:  # no-gw-probe
        write_pcap(args.uplink, [peer_frame])
        write_pcap(args.gateway, [peer_frame])
    return 0


if __name__ == "__main__":
    sys.exit(main())
