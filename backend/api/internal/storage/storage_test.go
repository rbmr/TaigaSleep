package storage

import (
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

func gzipMember(t *testing.T, payload string) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := gzip.NewWriter(&buffer)
	if _, err := writer.Write([]byte(payload)); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

// TestGzipTotalsCountsMembersAndTheirBytes reads a stored compressed file one member at
// a time: the count and the total are what the file row records for each appended chunk.
func TestGzipTotalsCountsMembersAndTheirBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ecg.log.gz")
	stored := append(gzipMember(t, "one"), gzipMember(t, "two")...)
	if err := os.WriteFile(path, stored, 0o640); err != nil {
		t.Fatal(err)
	}
	members, uncompressed, err := gzipTotals(path)
	if err != nil {
		t.Fatalf("gzip totals: %v", err)
	}
	if members != 2 || uncompressed != 6 {
		t.Fatalf("totals = %d members, %d bytes, want 2 and 6", members, uncompressed)
	}
}

// TestGzipTotalsRefusesADamagedFile keeps a broken member from being counted as part of
// the next one: the numbers would be wrong and the download already fails.
func TestGzipTotalsRefusesADamagedFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ecg.log.gz")
	stored := gzipMember(t, "damaged")
	if err := os.WriteFile(path, stored[:len(stored)-6], 0o640); err != nil {
		t.Fatal(err)
	}
	if _, _, err := gzipTotals(path); err == nil {
		t.Fatal("a truncated gzip member must be an error")
	}
}
