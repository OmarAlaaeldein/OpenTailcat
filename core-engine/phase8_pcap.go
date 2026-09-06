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
	dltNULL               = 0
	dltEN10MB             = 1
	dltRAW                = 12
	dltRAW2               = 101
	dltLOOP               = 108
	dltLINUXSLL           = 113
	dltLINUXSLL2          = 276
)

var errUnsupportedLinkType = errors.New("unsupported pcap link type")

type pcapPacket struct {
	payload []byte
}

type pcapCapture struct {
	linkType uint32
	packets  []pcapPacket
}

func readPCAP(r io.Reader) (linkType uint32, packets []pcapPacket, err error) {
	cap, err := readPCAPValidated(r)
	if err != nil {
		return 0, nil, err
	}
	return cap.linkType, cap.packets, nil
}

func readPCAPValidated(r io.Reader) (*pcapCapture, error) {
	var hdr [24]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, fmt.Errorf("pcap header: %w", err)
	}
	magic := binary.LittleEndian.Uint32(hdr[0:4])
	le := true
	switch magic {
	case pcapMagicMicroseconds:
		le = true
	case pcapMagicSwapped:
		le = false
	default:
		return nil, fmt.Errorf("unsupported pcap magic %08x (need classic pcap, not pcapng)", magic)
	}
	u32 := binary.LittleEndian.Uint32
	if !le {
		u32 = binary.BigEndian.Uint32
	}
	linkType := u32(hdr[20:24])
	if !supportedLinkType(linkType) {
		return nil, fmt.Errorf("%w: %d", errUnsupportedLinkType, linkType)
	}
	var packets []pcapPacket
	for {
		var ph [16]byte
		if _, err := io.ReadFull(r, ph[:]); err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}
		incl := u32(ph[8:12])
		orig := u32(ph[12:16])
		if incl > 1<<20 {
			return nil, fmt.Errorf("pcap packet too large: %d", incl)
		}
		if orig < incl {
			return nil, fmt.Errorf("pcap truncated packet: incl=%d orig=%d", incl, orig)
		}
		buf := make([]byte, incl)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		packets = append(packets, pcapPacket{payload: buf})
	}
	if len(packets) == 0 {
		return nil, errors.New("empty pcap capture: no packets (not valid Phase 8 evidence)")
	}
	return &pcapCapture{linkType: linkType, packets: packets}, nil
}

func supportedLinkType(linkType uint32) bool {
	switch linkType {
	case dltNULL, dltEN10MB, dltRAW, dltRAW2, dltLOOP, dltLINUXSLL, dltLINUXSLL2:
		return true
	default:
		return false
	}
}

func destIPsFromPacket(linkType uint32, pkt []byte) ([]netip.Addr, error) {
	switch linkType {
	case dltEN10MB:
		if len(pkt) < 14 {
			return nil, nil
		}
		et := binary.BigEndian.Uint16(pkt[12:14])
		off := 14
		if et == 0x8100 && len(pkt) >= 18 {
			et = binary.BigEndian.Uint16(pkt[16:18])
			off = 18
		}
		return destIPsFromNetwork(et, pkt[off:]), nil
	case dltNULL, dltLOOP:
		if len(pkt) < 4 {
			return nil, nil
		}
		af := binary.LittleEndian.Uint32(pkt[0:4])
		if linkType == dltLOOP {
			af = binary.BigEndian.Uint32(pkt[0:4])
		}
		switch af {
		case 2:
			return destIPsFromNetwork(0x0800, pkt[4:]), nil
		case 30, 24, 28:
			return destIPsFromNetwork(0x86dd, pkt[4:]), nil
		default:
			return nil, nil
		}
	case dltRAW, dltRAW2:
		return destIPsFromNetwork(0, pkt), nil
	case dltLINUXSLL:
		if len(pkt) < 16 {
			return nil, nil
		}
		et := binary.BigEndian.Uint16(pkt[14:16])
		return destIPsFromNetwork(et, pkt[16:]), nil
	case dltLINUXSLL2:
		// Linux SLL2: protocol at offset 0 (2 bytes), packet type at 10, address length at 11,
		// address at 12 for addr_len bytes, then network payload.
		if len(pkt) < 20 {
			return nil, nil
		}
		et := binary.BigEndian.Uint16(pkt[0:2])
		addrLen := int(pkt[11])
		off := 12 + addrLen
		if off > len(pkt) {
			return nil, nil
		}
		return destIPsFromNetwork(et, pkt[off:]), nil
	default:
		return nil, fmt.Errorf("%w: %d", errUnsupportedLinkType, linkType)
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
	if len(want) == 0 {
		return nil, errors.New("no valid probe addresses")
	}
	cap, err := readPCAPValidated(r)
	if err != nil {
		return nil, err
	}
	seen := make(map[netip.Addr]struct{})
	decoded := 0
	for _, pkt := range cap.packets {
		ips, err := destIPsFromPacket(cap.linkType, pkt.payload)
		if err != nil {
			return nil, err
		}
		if len(ips) > 0 {
			decoded++
		}
		for _, ip := range ips {
			if _, ok := want[ip]; ok {
				seen[ip] = struct{}{}
			}
		}
	}
	if decoded == 0 {
		return nil, errors.New("pcap contained packets but none could be decoded as IPv4/IPv6 (unsupported or corrupt framing)")
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
		if p.IsValid() {
			found[p] = false
		}
	}
	if len(found) == 0 {
		return nil, errors.New("no valid probe addresses")
	}
	cap, err := readPCAPValidated(r)
	if err != nil {
		return nil, err
	}
	decoded := 0
	for _, pkt := range cap.packets {
		ips, err := destIPsFromPacket(cap.linkType, pkt.payload)
		if err != nil {
			return nil, err
		}
		if len(ips) > 0 {
			decoded++
		}
		for _, ip := range ips {
			if _, ok := found[ip]; ok {
				found[ip] = true
			}
		}
	}
	if decoded == 0 {
		return nil, errors.New("gateway pcap contained packets but none could be decoded as IPv4/IPv6")
	}
	return found, nil
}
