// Package phase8 checks Phase 8 dual captures (phone uplink + gateway) for
// traffic that left the phone outside the tunnel. It is host tooling for
// cmd/phase8-analyze and is not part of the Android library.
package phase8

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net/netip"
	"time"
)

const (
	pcapMagicMicroseconds = 0xa1b2c3d4
	pcapMagicNanoseconds  = 0xa1b23c4d
	dltNULL               = 0
	dltEN10MB             = 1
	dltRAW                = 12
	dltRAW2               = 101
	dltLOOP               = 108
	dltLINUXSLL           = 113
	dltLINUXSLL2          = 276
)

var errUnsupportedLinkType = errors.New("unsupported pcap link type")

// Packet is the IP header summary of one captured frame.
type Packet struct {
	Time     time.Time
	Src, Dst netip.Addr
	Proto    uint8 // transport protocol number
	// SrcPort and DstPort are zero unless the packet is TCP or UDP and
	// carries the transport header (not a later fragment).
	SrcPort, DstPort uint16
}

// IsDNS reports plain DNS: TCP or UDP with port 53 on either side.
func (p Packet) IsDNS() bool {
	return (p.Proto == 6 || p.Proto == 17) && (p.SrcPort == 53 || p.DstPort == 53)
}

// Capture is a classic pcap file reduced to its IPv4/IPv6 packets, in file
// order. Frames of other protocols are skipped.
type Capture struct {
	LinkType uint32
	Packets  []Packet
}

// ReadCapture reads a classic pcap file (microsecond or nanosecond, either
// byte order). pcapng, unsupported link types, an empty capture, and a
// capture with no decodable IP packet are errors.
func ReadCapture(r io.Reader) (*Capture, error) {
	var hdr [24]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, fmt.Errorf("pcap header: %w", err)
	}
	var u32 func([]byte) uint32
	var fracUnit time.Duration
	switch le, be := binary.LittleEndian.Uint32(hdr[0:4]), binary.BigEndian.Uint32(hdr[0:4]); {
	case le == pcapMagicMicroseconds:
		u32, fracUnit = binary.LittleEndian.Uint32, time.Microsecond
	case be == pcapMagicMicroseconds:
		u32, fracUnit = binary.BigEndian.Uint32, time.Microsecond
	case le == pcapMagicNanoseconds:
		u32, fracUnit = binary.LittleEndian.Uint32, time.Nanosecond
	case be == pcapMagicNanoseconds:
		u32, fracUnit = binary.BigEndian.Uint32, time.Nanosecond
	default:
		return nil, fmt.Errorf("unsupported pcap magic %08x (need classic pcap, not pcapng)", le)
	}
	linkType := u32(hdr[20:24])
	if !supportedLinkType(linkType) {
		return nil, fmt.Errorf("%w: %d", errUnsupportedLinkType, linkType)
	}
	c := &Capture{LinkType: linkType}
	frames := 0
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
		frames++
		if p, ok := decodeFrame(linkType, buf); ok {
			p.Time = time.Unix(int64(u32(ph[0:4])), int64(u32(ph[4:8]))*int64(fracUnit))
			c.Packets = append(c.Packets, p)
		}
	}
	if frames == 0 {
		return nil, errors.New("empty pcap capture: no packets (not valid Phase 8 evidence)")
	}
	if len(c.Packets) == 0 {
		return nil, errors.New("pcap contained packets but none could be decoded as IPv4/IPv6 (unsupported or corrupt framing)")
	}
	return c, nil
}

func supportedLinkType(linkType uint32) bool {
	switch linkType {
	case dltNULL, dltEN10MB, dltRAW, dltRAW2, dltLOOP, dltLINUXSLL, dltLINUXSLL2:
		return true
	default:
		return false
	}
}

// decodeFrame strips the link header and decodes the IP header. ethertype 0
// means raw IP (the version nibble decides).
func decodeFrame(linkType uint32, f []byte) (Packet, bool) {
	var ethertype uint16
	var pkt []byte
	switch linkType {
	case dltEN10MB:
		if len(f) < 14 {
			return Packet{}, false
		}
		ethertype, pkt = binary.BigEndian.Uint16(f[12:14]), f[14:]
		if ethertype == 0x8100 && len(f) >= 18 {
			ethertype, pkt = binary.BigEndian.Uint16(f[16:18]), f[18:]
		}
	case dltNULL, dltLOOP:
		if len(f) < 4 {
			return Packet{}, false
		}
		af := binary.LittleEndian.Uint32(f[0:4])
		if linkType == dltLOOP {
			af = binary.BigEndian.Uint32(f[0:4])
		}
		switch af {
		case 2:
			ethertype = 0x0800
		case 24, 28, 30:
			ethertype = 0x86dd
		default:
			return Packet{}, false
		}
		pkt = f[4:]
	case dltRAW, dltRAW2:
		pkt = f
	case dltLINUXSLL:
		if len(f) < 16 {
			return Packet{}, false
		}
		ethertype, pkt = binary.BigEndian.Uint16(f[14:16]), f[16:]
	case dltLINUXSLL2:
		// Linux SLL2 is a fixed 20-byte header (addr is always 8 bytes at
		// 12:20); the addr_len field does not change the payload offset.
		if len(f) < 20 {
			return Packet{}, false
		}
		ethertype, pkt = binary.BigEndian.Uint16(f[0:2]), f[20:]
	default:
		return Packet{}, false
	}
	return decodeIP(ethertype, pkt)
}

func decodeIP(ethertype uint16, pkt []byte) (Packet, bool) {
	if len(pkt) < 1 {
		return Packet{}, false
	}
	if ethertype == 0 {
		switch pkt[0] >> 4 {
		case 4:
			ethertype = 0x0800
		case 6:
			ethertype = 0x86dd
		}
	}
	var p Packet
	switch ethertype {
	case 0x0800:
		if len(pkt) < 20 || pkt[0]>>4 != 4 {
			return Packet{}, false
		}
		p.Src = netip.AddrFrom4([4]byte(pkt[12:16]))
		p.Dst = netip.AddrFrom4([4]byte(pkt[16:20]))
		p.Proto = pkt[9]
		ihl := int(pkt[0]&0x0f) * 4
		if ihl >= 20 && binary.BigEndian.Uint16(pkt[6:8])&0x1fff == 0 && len(pkt) >= ihl {
			p.setPorts(pkt[ihl:])
		}
	case 0x86dd:
		if len(pkt) < 40 || pkt[0]>>4 != 6 {
			return Packet{}, false
		}
		p.Src = netip.AddrFrom16([16]byte(pkt[8:24]))
		p.Dst = netip.AddrFrom16([16]byte(pkt[24:40]))
		nh, off := pkt[6], 40
		// Walk hop-by-hop, routing, destination-options and fragment headers.
	walk:
		for i := 0; i < 8 && len(pkt) >= off+8; i++ {
			switch nh {
			case 0, 43, 60:
				nh, off = pkt[off], off+(int(pkt[off+1])+1)*8
			case 44:
				if binary.BigEndian.Uint16(pkt[off+2:off+4])&0xfff8 != 0 {
					p.Proto = pkt[off] // a later fragment has no transport header
					return p, true
				}
				nh, off = pkt[off], off+8
			default:
				break walk
			}
		}
		p.Proto = nh
		if off <= len(pkt) {
			p.setPorts(pkt[off:])
		}
	default:
		return Packet{}, false
	}
	return p, true
}

func (p *Packet) setPorts(l4 []byte) {
	if (p.Proto == 6 || p.Proto == 17) && len(l4) >= 4 {
		p.SrcPort = binary.BigEndian.Uint16(l4[0:2])
		p.DstPort = binary.BigEndian.Uint16(l4[2:4])
	}
}
