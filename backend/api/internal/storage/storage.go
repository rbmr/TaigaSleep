// Package storage keeps the byte totals the per-account quota is enforced from: what
// each recording holds and what an account holds across its recordings. They are kept
// with the rows that change them, so accepting a chunk reads one number instead of
// scanning every recording of the account and every file on disk.
package storage

import (
	"os"
	"path/filepath"

	"github.com/pocketbase/pocketbase/core"
)

// DataFiles are the raw files of a recording. meta.json is not one of them: it lives
// in the recording itself.
var DataFiles = []string{"hr.log", "ecg.log", "acc.log", "events.jsonl"}

// RecordingBytes is the stored size of a recording's files on disk, which is the
// compressed size for a gzip file. It reads the directory, so it is also right after a
// crash between writing a chunk and recording it in the row.
func RecordingBytes(app core.App, record *core.Record) int64 {
	dir := filepath.Join(app.DataDir(), "recordings", record.Id)
	var total int64
	for _, name := range DataFiles {
		for _, stored := range []string{name, name + ".gz"} {
			if info, err := os.Stat(filepath.Join(dir, stored)); err == nil {
				total += info.Size()
			}
		}
	}
	return total
}

// Reconcile compares the stored totals with the files on disk and corrects them. It
// returns how many recording and account rows it changed. The operator runs it as the
// `storage-check` command; the API keeps the totals correct on its own.
func Reconcile(app core.App) (recordings, accounts int, err error) {
	rows, err := app.FindAllRecords("recordings")
	if err != nil {
		return 0, 0, err
	}
	totals := map[string]int64{}
	for _, record := range rows {
		want := RecordingBytes(app, record)
		totals[record.GetString("owner")] += want
		if int64(record.GetInt("stored_bytes")) == want {
			continue
		}
		record.Set("stored_bytes", want)
		if err := app.Save(record); err != nil {
			return recordings, accounts, err
		}
		recordings++
	}

	users, err := app.FindAllRecords("users")
	if err != nil {
		return recordings, accounts, err
	}
	for _, user := range users {
		want := totals[user.Id]
		if int64(user.GetInt("storage_bytes")) == want {
			continue
		}
		user.Set("storage_bytes", want)
		if err := app.Save(user); err != nil {
			return recordings, accounts, err
		}
		accounts++
	}
	return recordings, accounts, nil
}
