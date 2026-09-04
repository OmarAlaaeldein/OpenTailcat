package engine

import (
	"encoding/binary"
	"errors"
	"fmt"
)

// strictCborReader provides deterministic, bounds-checked CBOR decoding with
// zero tolerance for duplicate keys, trailing data, floats, or indefinite-length structures.
type strictCborReader struct {
	data []byte
	off  int
}

func newStrictCborReader(data []byte) *strictCborReader {
	return &strictCborReader{data: data, off: 0}
}

func (r *strictCborReader) remaining() int {
	return len(r.data) - r.off
}

func (r *strictCborReader) readByte() (byte, error) {
	if r.off >= len(r.data) {
		return 0, errors.New("unexpected EOF reading CBOR byte")
	}
	b := r.data[r.off]
	r.off++
	return b, nil
}

func (r *strictCborReader) readBytes(n int) ([]byte, error) {
	if n < 0 || r.remaining() < n {
		return nil, errors.New("unexpected EOF reading CBOR byte array")
	}
	b := r.data[r.off : r.off+n]
	r.off += n
	return b, nil
}

func (r *strictCborReader) readLength(info byte) (uint64, error) {
	switch {
	case info < 24:
		return uint64(info), nil
	case info == 24:
		b, err := r.readByte()
		return uint64(b), err
	case info == 25:
		b, err := r.readBytes(2)
		if err != nil {
			return 0, err
		}
		return uint64(binary.BigEndian.Uint16(b)), nil
	case info == 26:
		b, err := r.readBytes(4)
		if err != nil {
			return 0, err
		}
		return uint64(binary.BigEndian.Uint32(b)), nil
	case info == 27:
		b, err := r.readBytes(8)
		if err != nil {
			return 0, err
		}
		return binary.BigEndian.Uint64(b), nil
	case info == 31:
		return 0, errors.New("indefinite-length CBOR is forbidden")
	default:
		return 0, fmt.Errorf("reserved or invalid CBOR length info: %d", info)
	}
}

var allowedCanonicalFields = map[string]bool{
	"p":   true,
	"k":   true,
	"q":   true,
	"i":   true,
	"r":   true,
	"exp": true,
	"iat": true,
}

// parseStrictTokenMap parses the top-level token CBOR map with strict validation.
func parseStrictTokenMap(cborBytes []byte) (map[string]any, TokenErrorCode, error) {
	if len(cborBytes) == 0 {
		return nil, ErrCborMalformed, errors.New("empty CBOR payload")
	}
	if len(cborBytes) > MaxCborPayloadBytes {
		return nil, ErrCborTooLarge, errors.New("CBOR payload exceeds maximum size")
	}

	r := newStrictCborReader(cborBytes)
	first, err := r.readByte()
	if err != nil {
		return nil, ErrCborMalformed, err
	}

	major := first >> 5
	info := first & 0x1f
	if major != 5 { // Major type 5 is map
		return nil, ErrNotMap, fmt.Errorf("expected top-level CBOR map (major 5), got major %d", major)
	}
	if info == 31 {
		return nil, ErrCborMalformed, errors.New("indefinite-length map is forbidden")
	}

	numPairs, err := r.readLength(info)
	if err != nil {
		return nil, ErrCborMalformed, err
	}
	if numPairs > 32 {
		return nil, ErrCborMalformed, fmt.Errorf("too many map entries: %d", numPairs)
	}

	result := make(map[string]any, numPairs)
	seenExactKeys := make(map[string]bool, numPairs)

	for i := uint64(0); i < numPairs; i++ {
		keyHdr, err := r.readByte()
		if err != nil {
			return nil, ErrCborMalformed, err
		}
		kMajor := keyHdr >> 5
		kInfo := keyHdr & 0x1f
		if kMajor != 3 { // Must be text string
			return nil, ErrCborMalformed, fmt.Errorf("map key must be text string, got major %d", kMajor)
		}
		kLen, err := r.readLength(kInfo)
		if err != nil {
			return nil, ErrCborMalformed, err
		}
		if kLen > 128 {
			return nil, ErrCborMalformed, errors.New("map key length exceeds limit")
		}
		kBytes, err := r.readBytes(int(kLen))
		if err != nil {
			return nil, ErrCborMalformed, err
		}
		keyStr := string(kBytes)

		// Check duplicate exact keys
		if seenExactKeys[keyStr] {
			return nil, ErrDuplicateKey, fmt.Errorf("duplicate map key: %q", keyStr)
		}
		seenExactKeys[keyStr] = true

		// Check that key is a permitted canonical field name (reject aliases and mixed-case)
		if !allowedCanonicalFields[keyStr] {
			return nil, ErrUnknownField, fmt.Errorf("unknown or alias token field %q", keyStr)
		}

		// Parse value strictly
		val, err := r.readStrictValue(0)
		if err != nil {
			return nil, ErrCborMalformed, err
		}

		result[keyStr] = val
	}

	if r.remaining() > 0 {
		return nil, ErrTrailingData, fmt.Errorf("trailing %d bytes after CBOR map", r.remaining())
	}

	return result, ErrNone, nil
}

func (r *strictCborReader) readStrictValue(depth int) (any, error) {
	if depth > 16 {
		return nil, errors.New("CBOR structure exceeds maximum recursion depth")
	}

	hdr, err := r.readByte()
	if err != nil {
		return nil, err
	}
	major := hdr >> 5
	info := hdr & 0x1f

	if info == 31 {
		return nil, errors.New("indefinite-length CBOR item is forbidden")
	}

	switch major {
	case 0: // Unsigned integer
		return r.readLength(info)
	case 1: // Negative integer
		val, err := r.readLength(info)
		if err != nil {
			return nil, err
		}
		if val > 0x7fffffffffffffff {
			return nil, errors.New("negative integer overflow")
		}
		return -1 - int64(val), nil
	case 2: // Byte string
		lenBytes, err := r.readLength(info)
		if err != nil {
			return nil, err
		}
		if lenBytes > 65536 {
			return nil, errors.New("byte string exceeds maximum length")
		}
		return r.readBytes(int(lenBytes))
	case 3: // Text string
		lenStr, err := r.readLength(info)
		if err != nil {
			return nil, err
		}
		if lenStr > 65536 {
			return nil, errors.New("text string exceeds maximum length")
		}
		b, err := r.readBytes(int(lenStr))
		if err != nil {
			return nil, err
		}
		return string(b), nil
	case 4: // Array
		numItems, err := r.readLength(info)
		if err != nil {
			return nil, err
		}
		if numItems > 256 {
			return nil, errors.New("array exceeds maximum element count")
		}
		items := make([]any, numItems)
		for i := uint64(0); i < numItems; i++ {
			elem, err := r.readStrictValue(depth + 1)
			if err != nil {
				return nil, err
			}
			items[i] = elem
		}
		return items, nil
	case 5: // Map
		numPairs, err := r.readLength(info)
		if err != nil {
			return nil, err
		}
		if numPairs > 256 {
			return nil, errors.New("nested map exceeds maximum entries")
		}
		m := make(map[string]any, numPairs)
		seen := make(map[string]bool, numPairs)
		for i := uint64(0); i < numPairs; i++ {
			kHdr, err := r.readByte()
			if err != nil {
				return nil, err
			}
			kMajor := kHdr >> 5
			kInfo := kHdr & 0x1f
			if kMajor != 3 {
				return nil, fmt.Errorf("nested map key must be text string, got major %d", kMajor)
			}
			kLen, err := r.readLength(kInfo)
			if err != nil {
				return nil, err
			}
			kB, err := r.readBytes(int(kLen))
			if err != nil {
				return nil, err
			}
			kStr := string(kB)
			if seen[kStr] {
				return nil, fmt.Errorf("duplicate key in nested map: %q", kStr)
			}
			seen[kStr] = true

			val, err := r.readStrictValue(depth + 1)
			if err != nil {
				return nil, err
			}
			m[kStr] = val
		}
		return m, nil
	case 7: // Simple value / float
		if info == 20 {
			return false, nil
		}
		if info == 21 {
			return true, nil
		}
		if info == 22 {
			return nil, nil
		}
		if info == 25 || info == 26 || info == 27 {
			return nil, errors.New("floating-point numbers are forbidden in token CBOR")
		}
		return nil, fmt.Errorf("unsupported simple CBOR value: %d", info)
	default:
		return nil, fmt.Errorf("unsupported CBOR major type: %d", major)
	}
}
