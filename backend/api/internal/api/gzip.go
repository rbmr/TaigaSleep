package api

import (
	"encoding/binary"
	"fmt"
)

// Gzip framing checks.
//
// The compressed upload mode stores one complete gzip member per appended
// chunk, so the chunk boundary is the member boundary. The API validates that
// framing without inflating anything: the header, the deflate block type, the
// trailer and the declared uncompressed size. It deliberately does not verify
// the member CRC or the decompressed bytes; the worker decompresses and checks
// every frame, which is the only place where the full stream is needed.

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
	return gzipChunk{Uncompressed: uncompressed}, nil
}
