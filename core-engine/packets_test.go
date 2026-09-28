package engine

import (
	"encoding/binary"
	"net/netip"
)

// Test packet builders for IPv4/IPv6 UDP datagrams with valid checksums.

func buildIPv4UDPPacket(srcAP, dstAP netip.AddrPort, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv4 Header
	pkt[0] = 0x45 // Version 4, IHL 5 (20 bytes)
	pkt[1] = 0x00 // DSCP / ECN
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(pkt[4:6], 0x1234) // Identification
	pkt[6] = 0x40                                // Don't fragment
	pkt[7] = 0x00
	pkt[8] = 64 // TTL
	pkt[9] = 17 // Protocol UDP

	srcBytes := srcAP.Addr().As4()
	dstBytes := dstAP.Addr().As4()
	copy(pkt[12:16], srcBytes[:])
	copy(pkt[16:20], dstBytes[:])

	ipChk := checksum(pkt[:20])
	binary.BigEndian.PutUint16(pkt[10:12], ipChk)

	// UDP Header
	binary.BigEndian.PutUint16(pkt[20:22], srcAP.Port())
	binary.BigEndian.PutUint16(pkt[22:24], dstAP.Port())
	binary.BigEndian.PutUint16(pkt[24:26], uint16(8+len(payload)))

	// UDP Payload
	copy(pkt[28:], payload)

	// UDP Checksum with Pseudo-header
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], srcBytes[:])
	copy(pseudo[4:8], dstBytes[:])
	pseudo[9] = 17
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(8+len(payload)))

	udpChk := udpChecksum(pseudo, pkt[20:])
	binary.BigEndian.PutUint16(pkt[26:28], udpChk)

	return pkt
}

func buildIPv6UDPPacket(srcAP, dstAP netip.AddrPort, payload []byte) []byte {
	totalLen := 40 + 8 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv6 Header
	pkt[0] = 0x60 // Version 6
	binary.BigEndian.PutUint16(pkt[4:6], uint16(8+len(payload)))
	pkt[6] = 17 // Next header UDP
	pkt[7] = 64 // Hop limit

	srcBytes := srcAP.Addr().As16()
	dstBytes := dstAP.Addr().As16()
	copy(pkt[8:24], srcBytes[:])
	copy(pkt[24:40], dstBytes[:])

	// UDP Header
	binary.BigEndian.PutUint16(pkt[40:42], srcAP.Port())
	binary.BigEndian.PutUint16(pkt[42:44], dstAP.Port())
	binary.BigEndian.PutUint16(pkt[44:46], uint16(8+len(payload)))

	// UDP Payload
	copy(pkt[48:], payload)

	// UDP Checksum with IPv6 Pseudo-header
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], srcBytes[:])
	copy(pseudo[16:32], dstBytes[:])
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(8+len(payload)))
	pseudo[39] = 17

	udpChk := udpChecksum(pseudo, pkt[40:])
	binary.BigEndian.PutUint16(pkt[46:48], udpChk)

	return pkt
}

// udpChecksum sends a computed 0 as 0xffff: in UDP a zero checksum means
// none (IPv4) or is invalid (IPv6).
func udpChecksum(pseudo, udp []byte) uint16 {
	if c := checksum(pseudo, udp); c != 0 {
		return c
	}
	return 0xffff
}
