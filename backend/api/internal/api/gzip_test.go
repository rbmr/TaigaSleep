package api

import (
	"bytes"
	"compress/gzip"
	"testing"
)

func gzipMember(t *testing.T, payload []byte) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := gzip.NewWriter(&buffer)
	if _, err := writer.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func TestValidateGzipChunkAcceptsAMember(t *testing.T) {
	t.Parallel()
	data := gzipMember(t, []byte("hello taiga"))

	member, err := validateGzipChunk(data, 1<<20, 200)
	if err != nil {
		t.Fatal(err)
	}
	if member.Uncompressed != int64(len("hello taiga")) {
		t.Fatalf("uncompressed = %d", member.Uncompressed)
	}
}

func TestValidateGzipChunkRejectsMalformedMembers(t *testing.T) {
	t.Parallel()
	valid := gzipMember(t, []byte("hello taiga"))
	cases := map[string][]byte{
		"empty":          nil,
		"not gzip":       []byte("this is not a gzip member at all"),
		"unsupported":    func() []byte { copy := append([]byte(nil), valid...); copy[2] = 7; return copy }(),
		"reserved flags": func() []byte { copy := append([]byte(nil), valid...); copy[3] = 0x20; return copy }(),
		"named member":   func() []byte { copy := append([]byte(nil), valid...); copy[3] = 0x08; return copy }(),
		"empty payload":  gzipMember(t, nil),
		"too much declared": func() []byte {
			copy := append([]byte(nil), valid...)
			binaryLittleEndian(copy[len(copy)-4:], 10<<20)
			return copy
		}(),
	}
	for name, data := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := validateGzipChunk(data, 1<<20, 200); err == nil {
				t.Fatal("expected an error")
			}
		})
	}
}

func TestValidateGzipChunkEnforcesTheExpansionRatio(t *testing.T) {
	t.Parallel()
	data := gzipMember(t, make([]byte, 100_000))
	if _, err := validateGzipChunk(data, 1<<20, 2); err == nil {
		t.Fatal("expected a ratio error")
	}
}

func TestValidateGzipChunkInflatesAndVerifiesTheMember(t *testing.T) {
	t.Parallel()
	payload := bytes.Repeat([]byte("taiga"), 1000)
	data := gzipMember(t, payload)

	member, err := validateGzipChunk(data, 1<<20, 200)
	if err != nil || member.Uncompressed != int64(len(payload)) {
		t.Fatalf("member = %+v, err = %v", member, err)
	}

	// The trailer claims 100 bytes, the member really holds 5000.
	understated := append([]byte(nil), data...)
	binaryLittleEndian(understated[len(understated)-4:], 100)
	if _, err := validateGzipChunk(understated, 1<<20, 200); err == nil {
		t.Fatal("a member that inflates past its declared size was accepted")
	}

	// The CRC in the trailer does not match the inflated bytes.
	badCRC := append([]byte(nil), data...)
	badCRC[len(badCRC)-8] ^= 0xff
	if _, err := validateGzipChunk(badCRC, 1<<20, 200); err == nil {
		t.Fatal("a member with a wrong CRC was accepted")
	}
}

func binaryLittleEndian(target []byte, value uint32) {
	target[0] = byte(value)
	target[1] = byte(value >> 8)
	target[2] = byte(value >> 16)
	target[3] = byte(value >> 24)
}
