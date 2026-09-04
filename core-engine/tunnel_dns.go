package engine

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net/netip"
	"strings"
)

const androidUserAgent = "OpenTailcat-Android/1.2.0"

func tunnelLookupA(ctx context.Context, client TunnelClient, name string) (netip.Addr, error) {
	query, err := encodeDNSQueryA(name)
	if err != nil {
		return netip.Addr{}, err
	}
	conn, err := client.DialTCP(ctx, netip.MustParseAddrPort("1.1.1.1:53"))
	if err != nil {
		return netip.Addr{}, fmt.Errorf("dial DNS through Tailcat: %w", err)
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	var hdr [2]byte
	binary.BigEndian.PutUint16(hdr[:], uint16(len(query)))
	if _, err := conn.Write(append(hdr[:], query...)); err != nil {
		return netip.Addr{}, fmt.Errorf("write DNS query: %w", err)
	}
	if _, err := io.ReadFull(conn, hdr[:]); err != nil {
		return netip.Addr{}, fmt.Errorf("read DNS length: %w", err)
	}
	n := int(binary.BigEndian.Uint16(hdr[:]))
	if n < 12 || n > 4096 {
		return netip.Addr{}, fmt.Errorf("invalid DNS response length %d", n)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return netip.Addr{}, fmt.Errorf("read DNS body: %w", err)
	}
	return parseDNSA(buf)
}

func encodeDNSQueryA(name string) ([]byte, error) {
	labels := strings.Split(strings.TrimSuffix(name, "."), ".")
	var payload []byte
	for _, label := range labels {
		if label == "" || len(label) > 63 {
			return nil, fmt.Errorf("invalid DNS label %q", label)
		}
		payload = append(payload, byte(len(label)))
		payload = append(payload, label...)
	}
	payload = append(payload, 0)
	buf := make([]byte, 12+len(payload)+4)
	binary.BigEndian.PutUint16(buf[0:2], 0x1111)
	binary.BigEndian.PutUint16(buf[2:4], 0x0100)
	binary.BigEndian.PutUint16(buf[4:6], 1)
	copy(buf[12:], payload)
	off := 12 + len(payload)
	binary.BigEndian.PutUint16(buf[off:off+2], 1)
	binary.BigEndian.PutUint16(buf[off+2:off+4], 1)
	return buf, nil
}

func parseDNSA(msg []byte) (netip.Addr, error) {
	if len(msg) < 12 {
		return netip.Addr{}, fmt.Errorf("DNS response too short")
	}
	if msg[3]&0x0f != 0 {
		return netip.Addr{}, fmt.Errorf("DNS rcode %d", msg[3]&0x0f)
	}
	qd := binary.BigEndian.Uint16(msg[4:6])
	an := binary.BigEndian.Uint16(msg[6:8])
	off := 12
	for i := 0; i < int(qd); i++ {
		var err error
		off, err = skipDNSName(msg, off)
		if err != nil {
			return netip.Addr{}, err
		}
		if off+4 > len(msg) {
			return netip.Addr{}, fmt.Errorf("truncated DNS question")
		}
		off += 4
	}
	for i := 0; i < int(an); i++ {
		var err error
		off, err = skipDNSName(msg, off)
		if err != nil {
			return netip.Addr{}, err
		}
		if off+10 > len(msg) {
			return netip.Addr{}, fmt.Errorf("truncated DNS answer")
		}
		typ := binary.BigEndian.Uint16(msg[off : off+2])
		class := binary.BigEndian.Uint16(msg[off+2 : off+4])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8 : off+10]))
		off += 10
		if off+rdlen > len(msg) {
			return netip.Addr{}, fmt.Errorf("truncated DNS rdata")
		}
		if typ == 1 && class == 1 && rdlen == 4 {
			addr, ok := netip.AddrFromSlice(msg[off : off+4])
			if !ok || !addr.Is4() {
				return netip.Addr{}, fmt.Errorf("invalid A rdata")
			}
			return addr, nil
		}
		off += rdlen
	}
	return netip.Addr{}, fmt.Errorf("no A record")
}

func skipDNSName(msg []byte, off int) (int, error) {
	jumps := 0
	end := off
	advanced := false
	for {
		if off >= len(msg) {
			return 0, fmt.Errorf("DNS name out of range")
		}
		l := int(msg[off])
		if l == 0 {
			off++
			if !advanced {
				end = off
			}
			return end, nil
		}
		if l&0xc0 == 0xc0 {
			if off+1 >= len(msg) {
				return 0, fmt.Errorf("truncated DNS pointer")
			}
			if !advanced {
				end = off + 2
				advanced = true
			}
			off = int(binary.BigEndian.Uint16(msg[off:off+2]) & 0x3fff)
			jumps++
			if jumps > 10 {
				return 0, fmt.Errorf("DNS pointer loop")
			}
			continue
		}
		if l&0xc0 != 0 {
			return 0, fmt.Errorf("invalid DNS label")
		}
		off += 1 + l
		if !advanced {
			end = off
		}
	}
}
