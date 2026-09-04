package engine

import (
	"bytes"
	"encoding/binary"
	"net/netip"
	"testing"
)

func writeEthernetIPv4(dst netip.Addr) []byte {
	ip := make([]byte, 20)
	ip[0] = 0x45
	binary.BigEndian.PutUint16(ip[2:4], 20)
	ip[8] = 64
	ip[9] = 17
	copy(ip[12:16], netip.MustParseAddr("10.0.0.2").AsSlice())
	copy(ip[16:20], dst.AsSlice())
	eth := make([]byte, 14+len(ip))
	eth[12] = 0x08
	eth[13] = 0x00
	copy(eth[14:], ip)
	return eth
}

func writePCAPEthernet(frames ...[]byte) []byte {
	var buf bytes.Buffer
	var hdr [24]byte
	binary.LittleEndian.PutUint32(hdr[0:4], pcapMagicMicroseconds)
	binary.LittleEndian.PutUint16(hdr[4:6], 2)
	binary.LittleEndian.PutUint16(hdr[6:8], 4)
	binary.LittleEndian.PutUint32(hdr[16:20], 65535)
	binary.LittleEndian.PutUint32(hdr[20:24], dltEN10MB)
	buf.Write(hdr[:])
	for _, f := range frames {
		var ph [16]byte
		binary.LittleEndian.PutUint32(ph[8:12], uint32(len(f)))
		binary.LittleEndian.PutUint32(ph[12:16], uint32(len(f)))
		buf.Write(ph[:])
		buf.Write(f)
	}
	return buf.Bytes()
}

func TestProbeIPsOnUplinkDetectsLeak(t *testing.T) {
	probe := netip.MustParseAddr("8.8.8.8")
	pcap := writePCAPEthernet(writeEthernetIPv4(probe))
	leaked, err := ProbeIPsOnUplink(bytes.NewReader(pcap), []netip.Addr{probe})
	if err != nil {
		t.Fatal(err)
	}
	if len(leaked) != 1 || leaked[0] != probe {
		t.Fatalf("expected leak of %v, got %v", probe, leaked)
	}
}

func TestProbeIPsOnUplinkCleanWireGuardLike(t *testing.T) {
	probe := netip.MustParseAddr("8.8.8.8")
	peer := netip.MustParseAddr("203.0.113.9")
	pcap := writePCAPEthernet(writeEthernetIPv4(peer))
	leaked, err := ProbeIPsOnUplink(bytes.NewReader(pcap), []netip.Addr{probe})
	if err != nil {
		t.Fatal(err)
	}
	if len(leaked) != 0 {
		t.Fatalf("clean uplink flagged %v", leaked)
	}
}

func TestProbeIPsPresentOnGateway(t *testing.T) {
	probe := netip.MustParseAddr("8.8.8.8")
	pcap := writePCAPEthernet(writeEthernetIPv4(probe))
	found, err := ProbeIPsPresent(bytes.NewReader(pcap), []netip.Addr{probe})
	if err != nil {
		t.Fatal(err)
	}
	if !found[probe] {
		t.Fatal("gateway capture missing probe destination")
	}
}