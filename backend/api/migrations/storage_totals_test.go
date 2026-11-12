package migrations

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/pocketbase/pocketbase/core"
)

// TestStorageTotalsAreFilledFromDisk covers the migration: existing recordings and
// accounts get the totals their files on disk add up to, and a later run corrects a
// total that drifted.
func TestStorageTotalsAreFilledFromDisk(t *testing.T) {
	app := newMigrationTestApp(t)

	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		t.Fatal(err)
	}
	newUser := func(email string) string {
		user := core.NewRecord(users)
		user.SetEmail(email)
		user.SetPassword("password123")
		user.SetVerified(true)
		if err := app.Save(user); err != nil {
			t.Fatalf("save user %s: %v", email, err)
		}
		return user.Id
	}
	alice := newUser("totals@example.com")
	bob := newUser("empty@example.com")

	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		t.Fatal(err)
	}
	newRecording := func(id, owner string) {
		record := core.NewRecord(recordings)
		record.Id = id
		record.Set("owner", owner)
		record.Set("state", "stopped")
		record.Set("started_at", "2026-01-01 00:00:00.000Z")
		record.Set("complete", true)
		record.Set("meta", map[string]any{"format": "taiga-recording"})
		record.Set("files", map[string]any{"hr.log": map[string]any{"encoding": "identity"}})
		if err := app.Save(record); err != nil {
			t.Fatalf("save recording %s: %v", id, err)
		}
	}
	newRecording("with-files", alice)
	newRecording("empty", alice)

	writeStoredFile(t, app, "with-files", "hr.log", 10)
	writeStoredFile(t, app, "with-files", "ecg.log.gz", 7)
	// A file the row does not list yet is still part of what the recording holds.
	writeStoredFile(t, app, "with-files", "acc.log", 3)

	if err := upStorageTotals(app); err != nil {
		t.Fatalf("migration: %v", err)
	}
	if got := recordingStoredBytes(t, app, "with-files"); got != 20 {
		t.Fatalf("recording stored bytes = %d, want 20", got)
	}
	if got := recordingStoredBytes(t, app, "empty"); got != 0 {
		t.Fatalf("empty recording stored bytes = %d, want 0", got)
	}
	if got := accountStoredBytes(t, app, alice); got != 20 {
		t.Fatalf("account storage bytes = %d, want 20", got)
	}
	if got := accountStoredBytes(t, app, bob); got != 0 {
		t.Fatalf("an account without recordings holds %d bytes", got)
	}

	// Running it again corrects totals that drifted and changes nothing else.
	record, err := app.FindRecordById("recordings", "with-files")
	if err != nil {
		t.Fatal(err)
	}
	record.Set("stored_bytes", 99)
	if err := app.Save(record); err != nil {
		t.Fatal(err)
	}
	user, err := app.FindRecordById("users", alice)
	if err != nil {
		t.Fatal(err)
	}
	user.Set("storage_bytes", 99)
	if err := app.Save(user); err != nil {
		t.Fatal(err)
	}
	if err := upStorageTotals(app); err != nil {
		t.Fatalf("second migration: %v", err)
	}
	if got := recordingStoredBytes(t, app, "with-files"); got != 20 {
		t.Fatalf("drifted recording stored bytes = %d, want 20", got)
	}
	if got := accountStoredBytes(t, app, alice); got != 20 {
		t.Fatalf("drifted account storage bytes = %d, want 20", got)
	}
}

func writeStoredFile(t *testing.T, app core.App, id, name string, size int) {
	t.Helper()
	dir := filepath.Join(app.DataDir(), "recordings", id)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, name), make([]byte, size), 0o640); err != nil {
		t.Fatal(err)
	}
}

func recordingStoredBytes(t *testing.T, app core.App, id string) int64 {
	t.Helper()
	record, err := app.FindRecordById("recordings", id)
	if err != nil {
		t.Fatal(err)
	}
	return int64(record.GetInt("stored_bytes"))
}

func accountStoredBytes(t *testing.T, app core.App, id string) int64 {
	t.Helper()
	user, err := app.FindRecordById("users", id)
	if err != nil {
		t.Fatal(err)
	}
	return int64(user.GetInt("storage_bytes"))
}
