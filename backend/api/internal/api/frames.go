package api

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"hash/crc32"
	"os"
)

const (
	frameHeaderSize = 8
	maxPayload      = 1 << 20
	maxEventLine    = 8 * 1024
)

var streamByFile = map[string]byte{
	hrFileName:  1,
	ecgFileName: 2,
	accFileName: 3,
}

type FrameResult struct {
	ValidEnd  int64
	Frames    int
	State     string
	Error     string
	CorruptAt int64
	Pending   int64
	Length    int64
}

type EventResult struct {
	ValidEnd  int64
	Lines     int
	State     string
	Error     string
	CorruptAt int64
	Length    int64
}

func validateLog(path string, expectedStream byte, fromOffset int64) (FrameResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return FrameResult{}, err
	}
	length := int64(len(data))
	result := FrameResult{ValidEnd: fromOffset, State: "ok", CorruptAt: -1, Length: length}
	if fromOffset < 0 || fromOffset > length {
		return result, fmt.Errorf("invalid validation offset")
	}
	if length > 0 && fromOffset > 0 && !headerOK(data[:frameHeaderSize], expectedStream) {
		result.ValidEnd = 0
		result.State = "corrupt"
		result.Error = "invalid frame-log header"
		return result, nil
	}

	at := int(fromOffset)
	headerDone := fromOffset > 0
	for at < len(data) {
		if !headerDone {
			if len(data)-at < frameHeaderSize {
				break
			}
			if !headerOK(data[at:at+frameHeaderSize], expectedStream) {
				result.State = "corrupt"
				result.Error = "invalid frame-log header"
				result.CorruptAt = int64(at)
				return result, nil
			}
			at += frameHeaderSize
			headerDone = true
			result.ValidEnd = int64(at)
			continue
		}

		if len(data)-at < 4 {
			break
		}
		payloadLength := int(binary.LittleEndian.Uint32(data[at:]))
		if payloadLength > maxPayload {
			result.State = "corrupt"
			result.Error = fmt.Sprintf("frame payload too large (%d bytes)", payloadLength)
			result.CorruptAt = int64(at)
			return result, nil
		}
		frameSize := 4 + payloadLength + 4
		if len(data)-at < frameSize {
			break
		}
		expected := binary.LittleEndian.Uint32(data[at+4+payloadLength:])
		actual := crc32.ChecksumIEEE(data[at+4 : at+4+payloadLength])
		if expected != actual {
			result.State = "corrupt"
			result.Error = "frame CRC mismatch"
			result.CorruptAt = int64(at)
			return result, nil
		}
		at += frameSize
		result.Frames++
		result.ValidEnd = int64(at)
	}

	result.Pending = length - result.ValidEnd
	if result.ValidEnd < length {
		result.State = "partial"
	}
	return result, nil
}

func headerOK(header []byte, expectedStream byte) bool {
	return len(header) >= frameHeaderSize &&
		header[0] == 'T' && header[1] == 'S' && header[2] == 'L' && header[3] == 'G' &&
		header[4] == 1 &&
		header[5] == expectedStream &&
		header[6] == 0 && header[7] == 0
}

func validateEvents(path string, fromOffset int64) (EventResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return EventResult{}, err
	}
	length := int64(len(data))
	result := EventResult{ValidEnd: fromOffset, State: "ok", CorruptAt: -1, Length: length}
	if fromOffset < 0 || fromOffset > length {
		return result, fmt.Errorf("invalid validation offset")
	}
	if fromOffset == length {
		return result, nil
	}

	lineStart := int(fromOffset)
	for i := int(fromOffset); i < len(data); i++ {
		if i-lineStart > maxEventLine {
			result.State = "corrupt"
			result.Error = "event line is too long"
			result.CorruptAt = int64(lineStart)
			return result, nil
		}
		if data[i] != '\n' {
			continue
		}
		line := data[lineStart:i]
		if len(line) > 0 {
			var event struct {
				PhoneNS   int64  `json:"phone_ns"`
				ElapsedNS int64  `json:"elapsed_ns"`
				Type      string `json:"type"`
			}
			if err := json.Unmarshal(line, &event); err != nil {
				result.State = "corrupt"
				result.Error = "invalid JSON event line"
				result.CorruptAt = int64(lineStart)
				return result, nil
			}
			if event.Type == "" {
				result.State = "corrupt"
				result.Error = "event line is missing required fields"
				result.CorruptAt = int64(lineStart)
				return result, nil
			}
			result.Lines++
		}
		result.ValidEnd = int64(i + 1)
		lineStart = i + 1
	}
	if result.ValidEnd < length {
		result.State = "partial"
	}
	return result, nil
}
