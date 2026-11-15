package api

import (
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/pocketbase/pocketbase/core"
)

// storedFileSize is the size of one of a recording's files on disk.
func storedFileSize(t *testing.T, s *server, id, name string) (int64, error) {
	t.Helper()
	info, err := os.Stat(filepath.Join(recordingDir(s.app, id), name))
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
}

// TestFailedChunkWriteLeavesTheCommittedSize covers a write that fails after part of
// the chunk reached the file: the chunk is cut off again, so the file still ends at the
// last committed size and the same offset can be sent again.
func TestFailedChunkWriteLeavesTheCommittedSize(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	id := "rec1"
	createRecording(s, alice, id, testMeta(id, "recording"))
	if st, _, body := appendChunk(s, alice, id, "hr.log", 0, []byte("hello"), nil); st != 204 {
		t.Fatalf("first chunk = %d %s", st, body)
	}

	original := writeFileChunk
	writeFileChunk = func(path string, data []byte) error {
		if err := appendFile(path, data[:len(data)/2]); err != nil {
			return err
		}
		return errors.New("the disk is full")
	}
	t.Cleanup(func() { writeFileChunk = original })

	if st, _, _ := appendChunk(s, alice, id, "hr.log", 5, []byte("world"), nil); st != http.StatusInternalServerError {
		t.Fatalf("a chunk whose write failed = %d, want 500", st)
	}
	if size, err := storedFileSize(t, s, id, "hr.log"); err != nil || size != 5 {
		t.Fatalf("the file is %d bytes (%v) after the failed write, want the committed 5", size, err)
	}
	if recording, account := storedTotals(t, s, id, "alice@example.com"); recording != 5 || account != 5 {
		t.Fatalf("totals = %d recording, %d account, want 5 and 5", recording, account)
	}
	if status, header, _ := s.call(http.MethodHead, "/v1/recordings/"+id+"/files/hr.log", alice, nil, nil); status != 200 || header.Get(headerUploadOffset) != "5" {
		t.Fatalf("HEAD after the failed write = %d %v", status, header)
	}

	writeFileChunk = original
	if st, _, body := appendChunk(s, alice, id, "hr.log", 5, []byte("world"), nil); st != 204 {
		t.Fatalf("the chunk at the committed offset = %d %s", st, body)
	}
	if data, err := os.ReadFile(filepath.Join(recordingDir(s.app, id), "hr.log")); err != nil || string(data) != "helloworld" {
		t.Fatalf("file = %q (%v), want helloworld", data, err)
	}
}

// TestFailedCommitRollsTheChunkBack covers a chunk whose bytes are written but whose
// rows cannot be saved: the file goes back to the last committed size, or disappears
// when the chunk created it, and the next chunk at that offset succeeds.
func TestFailedCommitRollsTheChunkBack(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	id := "rec1"
	createRecording(s, alice, id, testMeta(id, "recording"))

	fail := false
	s.app.OnRecordUpdate("recordings").BindFunc(func(e *core.RecordEvent) error {
		if fail && e.Record.Id == id {
			return errors.New("the database refused the update")
		}
		return e.Next()
	})

	// The chunk that creates a file is undone by removing the file.
	fail = true
	if st, _, _ := appendChunk(s, alice, id, "ecg.log", 0, []byte("chunk"), nil); st != http.StatusInternalServerError {
		t.Fatalf("a chunk whose commit failed = %d, want 500", st)
	}
	if _, err := storedFileSize(t, s, id, "ecg.log"); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("the file the failed chunk created is still there (%v)", err)
	}
	if status, _, _ := s.call(http.MethodHead, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, nil); status != http.StatusNotFound {
		t.Fatalf("HEAD for a file whose first chunk failed = %d, want 404", status)
	}
	if recording, account := storedTotals(t, s, id, "alice@example.com"); recording != 0 || account != 0 {
		t.Fatalf("totals after the failed commit = %d recording, %d account, want 0 and 0", recording, account)
	}
	fail = false
	if st, _, body := appendChunk(s, alice, id, "ecg.log", 0, []byte("chunk"), nil); st != 204 {
		t.Fatalf("the chunk at offset 0 after the failure = %d %s", st, body)
	}

	// A chunk that extends a file is undone by cutting it back.
	fail = true
	if st, _, _ := appendChunk(s, alice, id, "ecg.log", 5, []byte("more"), nil); st != http.StatusInternalServerError {
		t.Fatalf("an extending chunk whose commit failed = %d, want 500", st)
	}
	if size, err := storedFileSize(t, s, id, "ecg.log"); err != nil || size != 5 {
		t.Fatalf("the file is %d bytes (%v) after the failed commit, want the committed 5", size, err)
	}
	fail = false
	if st, _, body := appendChunk(s, alice, id, "ecg.log", 5, []byte("more"), nil); st != 204 {
		t.Fatalf("the chunk at the committed offset = %d %s", st, body)
	}
	if data, err := os.ReadFile(filepath.Join(recordingDir(s.app, id), "ecg.log")); err != nil || string(data) != "chunkmore" {
		t.Fatalf("file = %q (%v), want chunkmore", data, err)
	}
	if recording, account := storedTotals(t, s, id, "alice@example.com"); recording != 9 || account != 9 {
		t.Fatalf("totals = %d recording, %d account, want 9 and 9", recording, account)
	}
}

// TestConcurrentChunksForOneFileHaveOneWinner sends two chunks for the same recording
// at once. The append is serialised per recording, so one claims the offset and the
// other is told the size it committed.
func TestConcurrentChunksForOneFileHaveOneWinner(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	id := "race"
	createRecording(s, alice, id, testMeta(id, "recording"))

	chunks := [][]byte{[]byte("first"), []byte("second")}
	statuses := make([]int, len(chunks))
	headers := make([]http.Header, len(chunks))
	bodies := make([][]byte, len(chunks))
	var wait sync.WaitGroup
	start := make(chan struct{})
	for i, chunk := range chunks {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			statuses[i], headers[i], bodies[i] = appendChunk(s, alice, id, "hr.log", 0, chunk, nil)
		}()
	}
	close(start)
	wait.Wait()

	winner := -1
	for i, status := range statuses {
		switch status {
		case http.StatusNoContent:
			if winner != -1 {
				t.Fatalf("two chunks won: %v", statuses)
			}
			winner = i
		case http.StatusConflict:
			if code := decode[ErrorResponse](t, bodies[i]).Data["code"]; code != "offset_mismatch" {
				t.Fatalf("the losing chunk = %d %s, want 409 offset_mismatch", status, bodies[i])
			}
		default:
			t.Fatalf("chunk %d = %d %s", i, status, bodies[i])
		}
	}
	if winner == -1 {
		t.Fatalf("no chunk won: %v", statuses)
	}
	won := int64(len(chunks[winner]))
	size, err := storedFileSize(t, s, id, "hr.log")
	if err != nil || size != won {
		t.Fatalf("the file is %d bytes (%v), want the winning chunk's %d", size, err, won)
	}
	committed := strconv.FormatInt(won, 10)
	for i, header := range headers {
		if statuses[i] == http.StatusConflict && header.Get(headerUploadOffset) != committed {
			t.Fatalf("the losing chunk was told offset %q, want the committed %s", header.Get(headerUploadOffset), committed)
		}
	}
	if recording, account := storedTotals(t, s, id, "alice@example.com"); recording != won || account != won {
		t.Fatalf("totals = %d recording, %d account, want the winning chunk's %d", recording, account, won)
	}
}

// TestARecordWriteIsNotHeldUpByASlowChunk holds one append inside its disk write and
// writes a record in the meantime. The append has no transaction open while it writes,
// so the other write must not wait for the disk.
func TestARecordWriteIsNotHeldUpByASlowChunk(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	slow, other := "slow", "other"
	createRecording(s, alice, slow, testMeta(slow, "recording"))
	createRecording(s, alice, other, testMeta(other, "recording"))

	started := make(chan struct{})
	release := make(chan struct{})
	original := writeFileChunk
	writeFileChunk = func(path string, data []byte) error {
		close(started)
		<-release
		return appendFile(path, data)
	}
	t.Cleanup(func() {
		writeFileChunk = original
	})

	appended := make(chan int, 1)
	go func() {
		status, _, _ := appendChunk(s, alice, slow, "hr.log", 0, []byte("chunk"), nil)
		appended <- status
	}()
	select {
	case <-started:
	case <-time.After(10 * time.Second):
		close(release)
		t.Fatal("the chunk never reached its write")
	}

	type answer struct {
		status int
		body   []byte
	}
	written := make(chan answer, 1)
	go func() {
		status, _, body := s.call(http.MethodPatch, recordingsAPI+"/"+other, alice,
			map[string]any{"meta": testMeta(other, "stopped")}, nil)
		written <- answer{status, body}
	}()
	select {
	case got := <-written:
		if got.status != http.StatusOK {
			close(release)
			t.Fatalf("a record write during a slow chunk = %d %s", got.status, got.body)
		}
	case <-time.After(10 * time.Second):
		close(release)
		t.Fatal("a record write waited for a chunk's disk write")
	}

	close(release)
	if status := <-appended; status != http.StatusNoContent {
		t.Fatalf("the slow chunk = %d, want 204", status)
	}
}
