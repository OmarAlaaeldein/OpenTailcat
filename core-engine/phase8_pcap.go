package engine

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net/netip"
)

const (
	pcapMagicMicroseconds = 0xa1b2c3d4
	pcapMagicSwapped      = 0xd4c3b2a1
	dltEN10MB             = 1
	dltNULL               = 0
	dltRAW                = 12
	dltRAW2               = 101
	dltLOOP               = 108
	dltLINUXSLL           = 113
)

type pcapPacket struct {
	payload []byte
}

func readPCAP(r io.Reader) (linkType uint32, packets []pcapPacket, err error) {
	var hdr [24]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, nil, fmt.Errorf("pcap header: %w", err)
	}
	magic := binary.LittleEndian.Uint32(hdr[0:4])
	le := true
	switch magic {
	case pcapMagicMicroseconds:
		le = true
	case pcapMagicSwapped:
		le = false
	default:
		return 0, nil, fmt.Errorf("unsupported pcap magic %08x (need classic pcap, not pcapng)", magic)
	}
	u32 := binary.LittleEndian.Uint32
	if !le {
		u32 = binary.BigEndian.Uint32
	}
	linkType = u32(hdr[20:24])
	for {
		var ph [16]byte
		if _, err := io.ReadFull(r, ph[:]); err != nil {
			if errors.Is(err, io.EOF) {
				return linkType, packets, nil
			}
			return 0, nil, err
		}
		incl := u32(ph[8:12])
		if incl > 1<<20 {
			return 0, nil, fmt.Errorf("pcap packet too large: %d", incl)
		}
		buf := make([]byte, incl)
		if _, err := io.ReadFull(r, buf); err != nil {
			return 0, nil, err
		}
		packets = append(packets, pcapPacket{payload: buf})
	}
}

func destIPsFromPacket(linkType uint32, pkt []byte) []netip.Addr {
	switch linkType {
	case dltEN10MB:
		if len(pkt) < 14 {
			return nil
		}
		et := binary.BigEndian.Uint16(pkt[12:14])
		off := 14
		if et == 0x8100 && len(pkt) >= 18 {
			et = binary.BigEndian.Uint16(pkt[16:18])
			off = 18
		}
		return destIPsFromNetwork(et, pkt[off:])
	case dltNULL, dltLOOP:
		if len(pkt) < 4 {
			return nil
		}
		af := binary.LittleEndian.Uint32(pkt[0:4])
		if linkType == dltLOOP {
			af = binary.BigEndian.Uint32(pkt[0:4])
		}
		switch af {
		case 2:
			return destIPsFromNetwork(0x0800, pkt[4:])
		case 30, 24, 28:
			return destIPsFromNetwork(0x86dd, pkt[4:])
		default:
			return destIPsFromNetwork(0, pkt[4:])
		}
	case dltRAW, dltRAW2:
		return destIPsFromNetwork(0, pkt)
	case dltLINUXSLL:
		if len(pkt) < 16 {
			return nil
		}
		et := binary.BigEndian.Uint16(pkt[14:16])
		return destIPsFromNetwork(et, pkt[16:])
	default:
		return destIPsFromNetwork(0, pkt)
	}
}

func destIPsFromNetwork(ethertype uint16, pkt []byte) []netip.Addr {
	if len(pkt) < 1 {
		return nil
	}
	if ethertype == 0 {
		v := pkt[0] >> 4
		if v == 4 {
			ethertype = 0x0800
		} else if v == 6 {
			ethertype = 0x86dd
		} else {
			return nil
		}
	}
	switch ethertype {
	case 0x0800:
		if len(pkt) < 20 {
			return nil
		}
		dst, ok := netip.AddrFromSlice(pkt[16:20])
		if !ok {
			return nil
		}
		return []netip.Addr{dst}
	case 0x86dd:
		if len(pkt) < 40 {
			return nil
		}
		dst, ok := netip.AddrFromSlice(pkt[24:40])
		if !ok {
			return nil
		}
		return []netip.Addr{dst}
	default:
		return nil
	}
}

func ProbeIPsOnUplink(r io.Reader, probes []netip.Addr) ([]netip.Addr, error) {
	want := make(map[netip.Addr]struct{}, len(probes))
	for _, p := range probes {
		if p.IsValid() {
			want[p] = struct{}{}
		}
	}
	linkType, packets, err := readPCAP(r)
	if err != nil {
		return nil, err
	}
	seen := make(map[netip.Addr]struct{})
	for _, pkt := range packets {
		for _, ip := range destIPsFromPacket(linkType, pkt.payload) {
			if _, ok := want[ip]; ok {
				seen[ip] = struct{}{}
			}
		}
	}
	var leaked []netip.Addr
	for ip := range seen {
		leaked = append(leaked, ip)
	}
	return leaked, nil
}

func ProbeIPsPresent(r io.Reader, probes []netip.Addr) (map[netip.Addr]bool, error) {
	found := make(map[netip.Addr]bool, len(probes))
	for _, p := range probes {
		found[p] = false
	}
	linkType, packets, err := readPCAP(r)
	if err != nil {
		return nil, err
	}
	for _, pkt := range packets {
		for _, ip := range destIPsFromPacket(linkType, pkt.payload) {
			if _, ok := found[ip]; ok {
				found[ip] = true
			}
		}
	}
	return found, nil
}