package api

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"fmt"
	"io"
)

// Gzip member checks.
//
// The compressed upload mode stores one complete gzip member per appended
// chunk, so the chunk boundary is the member boundary. The API validates that
// framing and inflates the member to a discard sink, bounded by the size the
// member declares in its trailer: the header, the deflate block type, the
// trailer and the declared uncompressed size, and then the CRC and the real
// size against that declaration. A member that understates what it holds is
// rejected before anything is stored, so the stored declaration stays true.

const (
	gzipHeaderSize  = 10
	gzipTrailerSize = 8
)

type gzipChunk struct {
	Uncompressed int64
}

func validateGzipChunk(data []byte, maxUncompressed int64, maxRatio float64) (gzipChunk, error) {
	minimum := gzipHeaderSize + gzipTrailerSize + 2
	if len(data) < minimum {
		return gzipChunk{}, fmt.Errorf("gzip member is too short (%d bytes)", len(data))
	}
	if data[0] != 0x1f || data[1] != 0x8b {
		return gzipChunk{}, fmt.Errorf("chunk is not a gzip member")
	}
	if data[2] != 8 {
		return gzipChunk{}, fmt.Errorf("unsupported gzip compression method %d", data[2])
	}
	flags := data[3]
	if flags&0xe0 != 0 {
		return gzipChunk{}, fmt.Errorf("gzip member uses reserved flags")
	}
	if flags&0x1e != 0 {
		return gzipChunk{}, fmt.Errorf("gzip member must not carry extra fields, a name, a comment or a header CRC")
	}

	payload := data[gzipHeaderSize : len(data)-gzipTrailerSize]
	if len(payload) < 2 {
		return gzipChunk{}, fmt.Errorf("gzip member has no deflate payload")
	}
	if (payload[0]>>1)&0x03 == 3 {
		return gzipChunk{}, fmt.Errorf("gzip member starts with an invalid deflate block")
	}

	uncompressed := int64(binary.LittleEndian.Uint32(data[len(data)-4:]))
	if uncompressed <= 0 {
		return gzipChunk{}, fmt.Errorf("gzip member declares an empty payload")
	}
	if uncompressed > maxUncompressed {
		return gzipChunk{}, fmt.Errorf("gzip member expands to %d bytes (max %d)", uncompressed, maxUncompressed)
	}
	if float64(uncompressed) > float64(len(payload))*maxRatio {
		return gzipChunk{}, fmt.Errorf("gzip member expansion ratio is too high")
	}
	if err := inflateMember(data, uncompressed); err != nil {
		return gzipChunk{}, err
	}
	return gzipChunk{Uncompressed: uncompressed}, nil
}

// inflateMember inflates one chunk to a discard sink, limited to the size its
// trailer declares plus one byte. Every member is verified, so the declared
// size is the size that was really stored: a CRC or declared size that does not
// match, a stream that ends early, or a member that keeps expanding past its
// declaration is rejected. The limit is what stops an understated member from
// inflating without bound.
func inflateMember(data []byte, declared int64) error {
	reader, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("gzip member cannot be inflated: %v", err)
	}
	defer reader.Close()
	inflated, err := io.Copy(io.Discard, io.LimitReader(reader, declared+1))
	if err != nil {
		return fmt.Errorf("gzip member fails its CRC or size check: %v", err)
	}
	if inflated != declared {
		return fmt.Errorf("gzip member inflates to %d bytes, not the declared %d", inflated, declared)
	}
	return nil
}
