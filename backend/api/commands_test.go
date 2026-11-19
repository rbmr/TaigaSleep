package main

import (
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
)

// gzipMember is one complete gzip member, as an upload chunk is stored.
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

// runStorageCheck runs the command as an operator does and returns what it printed.
func runStorageCheck(t *testing.T, app core.App, args ...string) string {
	t.Helper()
	command := storageCheckCommand(app)
	var output bytes.Buffer
	command.SetOut(&output)
	command.SetErr(&output)
	command.SetArgs(args)
	if err := command.Execute(); err != nil {
		t.Fatalf("storage-check %v: %v\n%s", args, err, output.String())
	}
	return output.String()
}

// TestStorageCheckReportsAndRepairsDrift covers the command: it corrects a recording's
// stored bytes, an account's total and a compressed file's member count and
// uncompressed size from the file itself, and it reports a directory without a
// recording row instead of deleting it unless asked.
func TestStorageCheckReportsAndRepairsDrift(t *testing.T) {
	app, err := tests.NewTestApp()
	if err != nil {
		t.Fatalf("test app: %v", err)
	}
	t.Cleanup(app.Cleanup)

	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		t.Fatal(err)
	}
	user := core.NewRecord(users)
	user.SetEmail("storage@example.com")
	// PocketBase's test data already holds generated usernames, so a drawn one can clash.
	user.Set("username", "storage.example.com")
	user.SetPassword("password123")
	user.SetVerified(true)
	if err := app.Save(user); err != nil {
		t.Fatalf("save user: %v", err)
	}

	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		t.Fatal(err)
	}
	record := core.NewRecord(recordings)
	record.Id = "drifted"
	record.Set("owner", user.Id)
	record.Set("state", "stopped")
	record.Set("started_at", "2026-01-01 00:00:00.000Z")
	record.Set("complete", true)
	record.Set("meta", map[string]any{"format": "taiga-recording"})
	record.Set("files", map[string]any{
		"ecg.log": map[string]any{"encoding": "gzip", "members": 99, "uncompressed_size": 1},
	})
	if err := app.Save(record); err != nil {
		t.Fatalf("save recording: %v", err)
	}

	dir := filepath.Join(app.DataDir(), "recordings", record.Id)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	stored := append(gzipMember(t, "one"), gzipMember(t, "two")...)
	if err := os.WriteFile(filepath.Join(dir, "ecg.log.gz"), stored, 0o640); err != nil {
		t.Fatal(err)
	}
	record, err = app.FindRecordById("recordings", record.Id)
	if err != nil {
		t.Fatal(err)
	}
	record.Set("stored_bytes", 999)
	if err := app.Save(record); err != nil {
		t.Fatal(err)
	}
	user, err = app.FindRecordById("users", user.Id)
	if err != nil {
		t.Fatal(err)
	}
	user.Set("storage_bytes", 999)
	if err := app.Save(user); err != nil {
		t.Fatal(err)
	}

	orphan := filepath.Join(app.DataDir(), "recordings", "left-behind")
	if err := os.MkdirAll(orphan, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(orphan, "hr.log"), []byte("frames"), 0o640); err != nil {
		t.Fatal(err)
	}

	output := runStorageCheck(t, app)
	for _, want := range []string{
		"corrected 1 recordings",
		"1 accounts",
		"1 compressed files",
		"recordings/left-behind",
	} {
		if !strings.Contains(output, want) {
			t.Errorf("storage-check output %q does not contain %q", output, want)
		}
	}

	record, err = app.FindRecordById("recordings", record.Id)
	if err != nil {
		t.Fatal(err)
	}
	if got := int64(record.GetInt("stored_bytes")); got != int64(len(stored)) {
		t.Errorf("recording stored bytes = %d, want %d", got, len(stored))
	}
	var files map[string]struct {
		Encoding         string `json:"encoding"`
		Members          int    `json:"members"`
		UncompressedSize int64  `json:"uncompressed_size"`
	}
	if err := record.UnmarshalJSONField("files", &files); err != nil {
		t.Fatal(err)
	}
	if entry := files["ecg.log"]; entry.Members != 2 || entry.UncompressedSize != 6 {
		t.Errorf("ecg.log = %+v, want 2 members and 6 uncompressed bytes", entry)
	}
	user, err = app.FindRecordById("users", user.Id)
	if err != nil {
		t.Fatal(err)
	}
	if got := int64(user.GetInt("storage_bytes")); got != int64(len(stored)) {
		t.Errorf("account storage bytes = %d, want %d", got, len(stored))
	}
	if _, err := os.Stat(orphan); err != nil {
		t.Fatalf("a directory without a recording was removed without being asked: %v", err)
	}

	output = runStorageCheck(t, app, "--remove-orphans")
	if !strings.Contains(output, "removed 1 directories without a recording") {
		t.Errorf("storage-check --remove-orphans output %q does not report the removal", output)
	}
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Errorf("the directory without a recording is still there (%v)", err)
	}
}
