package api

import (
	"encoding/binary"
	"hash/crc32"
	"os"
	"path/filepath"
	"testing"
)

func writeFrameLog(t *testing.T, stream byte, payloads [][]byte, tail []byte) string {
	t.Helper()
	data := []byte{'T', 'S', 'L', 'G', 1, stream, 0, 0}
	for _, payload := range payloads {
		frame := make([]byte, 4+len(payload)+4)
		binary.LittleEndian.PutUint32(frame, uint32(len(payload)))
		copy(frame[4:], payload)
		binary.LittleEndian.PutUint32(frame[4+len(payload):], crc32.ChecksumIEEE(payload))
		data = append(data, frame...)
	}
	data = append(data, tail...)
	path := filepath.Join(t.TempDir(), "stream.log")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func hrPayload() []byte {
	payload := make([]byte, 20+2)
	binary.LittleEndian.PutUint64(payload[0:], 1792798200000000000)
	binary.LittleEndian.PutUint64(payload[8:], 1000000000)
	binary.LittleEndian.PutUint16(payload[16:], 60)
	payload[18] = 3
	payload[19] = 1
	binary.LittleEndian.PutUint16(payload[20:], 1000)
	return payload
}

func TestValidateLogAcceptsAValidFrame(t *testing.T) {
	path := writeFrameLog(t, 1, [][]byte{hrPayload()}, nil)
	result, err := validateLog(path, 1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != "ok" || result.Frames != 1 || result.ValidEnd != result.Length {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestValidateLogRejectsBadCRC(t *testing.T) {
	payload := hrPayload()
	frame := make([]byte, 4+len(payload)+4)
	binary.LittleEndian.PutUint32(frame, uint32(len(payload)))
	copy(frame[4:], payload)
	binary.LittleEndian.PutUint32(frame[4+len(payload):], 0)
	data := append([]byte{'T', 'S', 'L', 'G', 1, 1, 0, 0}, frame...)
	path := filepath.Join(t.TempDir(), "bad.log")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := validateLog(path, 1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != "corrupt" || result.ValidEnd != 8 || result.Frames != 0 {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestValidateLogKeepsATruncatedTailPartial(t *testing.T) {
	path := writeFrameLog(t, 1, [][]byte{hrPayload()}, []byte{0x10, 0, 0, 0, 1})
	result, err := validateLog(path, 1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != "partial" || result.Frames != 1 || result.Pending == 0 {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestValidateEvents(t *testing.T) {
	valid := []byte(`{"phone_ns":1,"elapsed_ns":2,"type":"start"}` + "\n")
	path := filepath.Join(t.TempDir(), "events.jsonl")
	if err := os.WriteFile(path, valid, 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := validateEvents(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != "ok" || result.Lines != 1 || result.ValidEnd != int64(len(valid)) {
		t.Fatalf("unexpected result: %+v", result)
	}

	if err := os.WriteFile(path, []byte("not json\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result, err = validateEvents(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.State != "corrupt" {
		t.Fatalf("expected corrupt, got %+v", result)
	}
}

func TestParamsHashIsStable(t *testing.T) {
	hash, err := paramsHash(map[string]any{})
	if err != nil {
		t.Fatal(err)
	}
	if hash != "44136fa355b3678a" {
		t.Fatalf("unexpected empty params hash: %s", hash)
	}
	first, _ := paramsHash(map[string]any{"deep_hr_ratio": 0.8, "wake_movement_g": 0.05})
	second, _ := paramsHash(map[string]any{"wake_movement_g": 0.05, "deep_hr_ratio": 0.8})
	if first != second {
		t.Fatalf("params hash depends on map order: %s != %s", first, second)
	}
}

func TestValidateMeta(t *testing.T) {
	valid := []byte(`{
		"format": "taiga-recording",
		"version": 1,
		"session_id": "20261023_223000",
		"state": "stopped",
		"start_utc_ms": 1792798200000,
		"end_utc_ms": 1792828200000,
		"timezone": "Europe/Amsterdam",
		"sensor": {"kind": "polar-h10"},
		"config": {"hr": true, "ecg": true, "acc": true, "ecg_rate_hz": 130, "acc_rate_hz": 50}
	}`)
	meta, err := ValidateMeta(valid)
	if err != nil {
		t.Fatal(err)
	}
	if meta.SessionID != "20261023_223000" || meta.DurationSeconds() != 30000 {
		t.Fatalf("unexpected meta: %+v", meta)
	}

	bad := []byte(`{"format":"wrong"}`)
	if _, err := ValidateMeta(bad); err == nil {
		t.Fatal("expected invalid meta error")
	}
}
