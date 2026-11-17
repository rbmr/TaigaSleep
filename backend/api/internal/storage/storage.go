// Package storage keeps the byte totals the per-account quota is enforced from: what
// each recording holds and what an account holds across its recordings. They are kept
// with the rows that change them, so accepting a chunk reads one number instead of
// scanning every recording of the account and every file on disk.
//
// The API keeps the totals and the per-file bookkeeping correct as it writes. Check
// compares them with the files themselves, for a data directory that drifted, and
// repairs what it can.
package storage

import (
	"bufio"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// DataFiles are the raw files every recording used to have. A recording may add
// stream logs of its own, named by its meta.json, so IsDataFile accepts those too.
var DataFiles = []string{"hr.log", "ecg.log", "acc.log", "events.jsonl"}

// IsDataFile says whether a name may be stored as one of a recording's files.
func IsDataFile(name string) bool {
	if name == "events.jsonl" {
		return true
	}
	base, ok := strings.CutSuffix(name, ".log")
	if !ok || base == "" || len(base) > 32 {
		return false
	}
	for _, char := range base {
		if (char < 'a' || char > 'z') && (char < '0' || char > '9') && char != '_' && char != '-' {
			return false
		}
	}
	return true
}

// IsStreamFile says whether a data file is a frame log rather than the event log.
func IsStreamFile(name string) bool { return name != "events.jsonl" && IsDataFile(name) }

// StoredNames lists the data files a recording's bookkeeping names, in a stable order.
func StoredNames[T any](entries map[string]T) []string {
	names := make([]string, 0, len(entries))
	for _, known := range DataFiles {
		if _, ok := entries[known]; ok {
			names = append(names, known)
		}
	}
	extra := make([]string, 0, len(entries))
	for name := range entries {
		if slices.Contains(names, name) || !IsDataFile(name) {
			continue
		}
		extra = append(extra, name)
	}
	sort.Strings(extra)
	return append(names, extra...)
}

// fileEntry is the part of a recording's file bookkeeping that is checked against the
// file on disk. A compressed file's member count and uncompressed size are the only
// facts about a stored file that cannot be read from its size.
type fileEntry struct {
	Encoding         string `json:"encoding"`
	UncompressedSize int64  `json:"uncompressed_size"`
	Members          int    `json:"members,omitempty"`
}

// Options says what a check may change beyond reporting.
type Options struct {
	// RemoveOrphans also deletes the directories under recordings/ that have no
	// recording row.
	RemoveOrphans bool
}

// Report is what a check found and corrected.
type Report struct {
	// Recordings is the number of recording rows whose stored_bytes changed.
	Recordings int
	// Accounts is the number of account rows whose storage_bytes changed.
	Accounts int
	// Files is the number of compressed files whose member count or uncompressed
	// size was rebuilt.
	Files int
	// Orphans are the directories under recordings/ that have no recording row.
	Orphans []string
	// Removed is how many of the orphans were deleted.
	Removed int
}

// RecordingBytes is the stored size of a recording's files on disk, which is the
// compressed size for a gzip file. It reads the directory, so it is also right after a
// crash between writing a chunk and recording it in the row.
func RecordingBytes(app core.App, record *core.Record) int64 {
	return recordingBytes(filepath.Join(app.DataDir(), "recordings", record.Id))
}

// recordingBytes is the stored size of a recording's data files, compressed or not. A
// file that is not one of them, such as meta.json or a stray file, does not count.
func recordingBytes(dir string) int64 {
	var total int64
	files, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	for _, file := range files {
		if file.IsDir() {
			continue
		}
		name := strings.TrimSuffix(file.Name(), ".gz")
		if !IsDataFile(name) {
			continue
		}
		if info, err := file.Info(); err == nil {
			total += info.Size()
		}
	}
	return total
}

// Reconcile compares the stored totals with the files on disk and corrects them. It
// returns how many recording and account rows it changed. The migration that adds the
// totals fills them with it; the operator runs `storage-check`, which also rebuilds the
// compressed file bookkeeping, for a total that drifted.
func Reconcile(app core.App) (recordings, accounts int, err error) {
	report, err := reconcile(app, Options{}, false)
	return report.Recordings, report.Accounts, err
}

// Check compares the stored totals with the files on disk and corrects them, rebuilds
// a compressed file's member count and uncompressed size from the file's gzip members,
// and reports the directories under recordings/ that have no recording row. It
// returns what it found and changed.
func Check(app core.App, options Options) (Report, error) {
	return reconcile(app, options, true)
}

// reconcile compares the rows with the files. checkFiles says whether a compressed file's
// member count and uncompressed size are rebuilt as well; the migration fills only the
// totals, and must not stop on a stored file it cannot read.
func reconcile(app core.App, options Options, checkFiles bool) (Report, error) {
	var report Report
	rows, err := app.FindAllRecords("recordings")
	if err != nil {
		return report, err
	}
	known := make(map[string]bool, len(rows))
	totals := map[string]int64{}
	for _, record := range rows {
		known[record.Id] = true
		want, rebuilt, err := reconcileRecording(app, record, checkFiles)
		if err != nil {
			return report, err
		}
		report.Files += rebuilt
		totals[record.GetString("owner")] += want
		stored := int64(record.GetInt("stored_bytes"))
		if stored == want && rebuilt == 0 {
			continue
		}
		if stored != want {
			report.Recordings++
		}
		record.Set("stored_bytes", want)
		if err := app.Save(record); err != nil {
			return report, err
		}
	}

	users, err := app.FindAllRecords("users")
	if err != nil {
		return report, err
	}
	for _, user := range users {
		want := totals[user.Id]
		if int64(user.GetInt("storage_bytes")) == want {
			continue
		}
		user.Set("storage_bytes", want)
		if err := app.Save(user); err != nil {
			return report, err
		}
		report.Accounts++
	}

	base := filepath.Join(app.DataDir(), "recordings")
	entries, err := os.ReadDir(base)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return report, err
	}
	for _, entry := range entries {
		if !entry.IsDir() || known[entry.Name()] {
			continue
		}
		report.Orphans = append(report.Orphans, entry.Name())
	}
	sort.Strings(report.Orphans)
	if options.RemoveOrphans {
		for _, name := range report.Orphans {
			if err := os.RemoveAll(filepath.Join(base, name)); err != nil {
				return report, err
			}
			report.Removed++
		}
	}
	return report, nil
}

// reconcileRecording corrects the parts of one recording's bookkeeping that disagree
// with its files and returns its stored size and how many compressed files it rebuilt.
// It writes nothing: the caller saves the record.
func reconcileRecording(app core.App, record *core.Record, checkFiles bool) (int64, int, error) {
	if !checkFiles {
		return RecordingBytes(app, record), 0, nil
	}
	entries := map[string]fileEntry{}
	_ = record.UnmarshalJSONField("files", &entries)
	if entries == nil {
		entries = map[string]fileEntry{}
	}
	dir := filepath.Join(app.DataDir(), "recordings", record.Id)
	rebuilt := 0
	for _, name := range StoredNames(entries) {
		path := filepath.Join(dir, name+".gz")
		if _, err := os.Stat(path); err != nil {
			continue
		}
		entry, ok := entries[name]
		if !ok || entry.Encoding != "gzip" {
			continue
		}
		members, uncompressed, err := gzipTotals(path)
		if err != nil {
			return 0, 0, fmt.Errorf("recording %s: %s: %w", record.Id, name+".gz", err)
		}
		if entry.Members == members && entry.UncompressedSize == uncompressed {
			continue
		}
		entry.Members = members
		entry.UncompressedSize = uncompressed
		entries[name] = entry
		rebuilt++
	}
	if rebuilt > 0 {
		record.Set("files", entries)
	}
	return RecordingBytes(app, record), rebuilt, nil
}

// gzipTotals counts the gzip members of a stored file and the bytes they inflate to.
// A compressed file is one member per uploaded chunk, so the member boundaries are the
// chunk boundaries and the two numbers are what the file row would have recorded had
// every chunk committed. It reads one member at a time and stops exactly at each
// member's end, so a damaged member is an error rather than part of the next one.
func gzipTotals(path string) (members int, uncompressed int64, err error) {
	file, err := os.Open(path)
	if err != nil {
		return 0, 0, err
	}
	defer file.Close()
	buffered := bufio.NewReader(file)
	for {
		if _, err := buffered.Peek(1); err != nil {
			if errors.Is(err, io.EOF) {
				return members, uncompressed, nil
			}
			return members, uncompressed, err
		}
		reader, err := gzip.NewReader(buffered)
		if err != nil {
			return members, uncompressed, err
		}
		reader.Multistream(false)
		size, err := io.Copy(io.Discard, reader)
		closeErr := reader.Close()
		if err != nil {
			return members, uncompressed, err
		}
		if closeErr != nil {
			return members, uncompressed, closeErr
		}
		members++
		uncompressed += size
	}
}
