package api

import (
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/pocketbase/pocketbase/core"

	"github.com/rbmr/TaigaSleep/backend/api/internal/storage"
)

const (
	encodingIdentity = "identity"
	encodingGzip     = "gzip"

	headerUploadOffset = "Upload-Offset"
)

// FileEntry is what the backend tracks about a stored file beyond its size on disk.
type FileEntry struct {
	Encoding         string `json:"encoding"`
	UncompressedSize int64  `json:"uncompressed_size"`
	Members          int    `json:"members,omitempty"`
}

// FileView is one file of a recording. Size is the stored size, which is the
// compressed size for a gzip file.
type FileView struct {
	Name             string `json:"name"`
	Size             int64  `json:"size"`
	Encoding         string `json:"encoding"`
	UncompressedSize int64  `json:"uncompressed_size"`
}

type FileList struct {
	Items []FileView `json:"items"`
}

func isDataFile(name string) bool {
	return storage.IsDataFile(name)
}

func isStreamFile(name string) bool {
	return storage.IsStreamFile(name)
}

func storedName(name, encoding string) string {
	if encoding == encodingGzip {
		return name + ".gz"
	}
	return name
}

func recordingDir(app core.App, id string) string { return filepath.Join(recordingsBase(app), id) }

func readFiles(record *core.Record) map[string]FileEntry {
	files := map[string]FileEntry{}
	_ = record.UnmarshalJSONField("files", &files)
	if files == nil {
		files = map[string]FileEntry{}
	}
	return files
}

// storedSize is the size of a stored file and whether it is there.
func storedSize(path string) (int64, bool, error) {
	info, err := os.Stat(path)
	if errors.Is(err, os.ErrNotExist) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return info.Size(), true, nil
}

// writeFileChunk appends one chunk to a stored file and flushes it to disk. A test
// replaces it to fail a write after part of the chunk is on disk.
var writeFileChunk = appendFile

// fileViews lists the files that exist, in a stable order. The size comes from the
// disk, so it is right even after a crash between writing a chunk and recording it.
func fileViews(app core.App, record *core.Record) []FileView {
	entries := readFiles(record)
	dir := recordingDir(app, record.Id)
	views := []FileView{}
	for _, name := range storage.StoredNames(entries) {
		entry := entries[name]
		encoding := entry.Encoding
		if encoding == "" {
			encoding = encodingIdentity
		}
		path := filepath.Join(dir, storedName(name, encoding))
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		uncompressed := entry.UncompressedSize
		if encoding == encodingIdentity {
			uncompressed = info.Size()
		}
		views = append(views, FileView{Name: name, Size: info.Size(), Encoding: encoding, UncompressedSize: uncompressed})
	}
	return views
}

func recordingSize(app core.App, record *core.Record) int64 {
	var total int64
	for _, view := range fileViews(app, record) {
		total += view.Size
	}
	return total
}

// quotaCheck refuses a chunk of add bytes when the account already holds used bytes.
// It decides from the account's running total alone, so the check does not read the
// account's recordings or their files.
func quotaCheck(used, add int64) error {
	quota := userQuotaBytes()
	if quota > 0 && used+add > quota {
		return tooLarge("storage_quota_exceeded", fmt.Sprintf("this account holds at most %d GB of recordings", quota/(1024*1024*1024)))
	}
	return nil
}

// addAccountBytes moves an account's running total by delta. It is called inside the
// transaction that changes the bytes, so the total and the files change together.
func addAccountBytes(app core.App, owner string, delta int64) error {
	account, err := app.FindRecordById("users", owner)
	if err != nil {
		return err
	}
	total := int64(account.GetInt("storage_bytes")) + delta
	if total < 0 {
		total = 0
	}
	account.Set("storage_bytes", total)
	return app.Save(account)
}

func streamBytes(app core.App, record *core.Record) int64 {
	var total int64
	for _, view := range fileViews(app, record) {
		if isStreamFile(view.Name) {
			total += view.Size
		}
	}
	return total
}

func ListFiles(e *core.RequestEvent) error {
	record, err := recordingFor(e, e.Request.PathValue("id"))
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, FileList{Items: fileViews(e.App, record)})
}

func findFile(app core.App, record *core.Record, name string) (FileView, error) {
	if !isDataFile(name) {
		return FileView{}, notFound("file")
	}
	for _, view := range fileViews(app, record) {
		if view.Name == name {
			return view, nil
		}
	}
	return FileView{}, notFound("file")
}

func HeadFile(e *core.RequestEvent) error {
	record, err := recordingFor(e, e.Request.PathValue("id"))
	if err != nil {
		return err
	}
	view, err := findFile(e.App, record, e.Request.PathValue("name"))
	if err != nil {
		return err
	}
	header := e.Response.Header()
	header.Set(headerUploadOffset, strconv.FormatInt(view.Size, 10))
	if view.Encoding == encodingGzip {
		header.Set("Content-Encoding", encodingGzip)
	}
	return e.NoContent(http.StatusOK)
}

func PatchFile(e *core.RequestEvent) error {
	name := e.Request.PathValue("name")
	if !isDataFile(name) {
		return notFound("file")
	}
	offsetHeader := strings.TrimSpace(e.Request.Header.Get(headerUploadOffset))
	offset, err := strconv.ParseInt(offsetHeader, 10, 64)
	if err != nil || offset < 0 {
		return badRequest("invalid_offset", headerUploadOffset+" must be a non-negative integer")
	}
	encoding := encodingIdentity
	switch strings.ToLower(strings.TrimSpace(e.Request.Header.Get("Content-Encoding"))) {
	case "", encodingIdentity:
	case encodingGzip:
		encoding = encodingGzip
	default:
		return badRequest("unsupported_encoding", "Content-Encoding must be gzip or absent")
	}
	data, err := io.ReadAll(io.LimitReader(e.Request.Body, maxChunkBytes()+1))
	if err != nil {
		return badRequest("unreadable_body", "cannot read the request body")
	}
	if len(data) == 0 {
		return badRequest("empty_chunk", "the request body is empty")
	}
	if int64(len(data)) > maxChunkBytes() {
		return tooLarge("chunk_too_large", fmt.Sprintf("a chunk is at most %d bytes", maxChunkBytes()))
	}
	var member gzipChunk
	if encoding == encodingGzip {
		member, err = validateGzipChunk(data, maxUncompressedChunkBytes(), maxExpansionRatio)
		if err != nil {
			return badRequest("invalid_gzip", err.Error())
		}
	}

	// One append at a time per recording: the offset is read from the file, so a
	// second chunk that looked before the first was written would claim the same
	// place.
	id := e.Request.PathValue("id")
	release := recordingFileLocks.lock(id)
	defer release()

	// Decide first, write second, record third. The checks read the recording, the
	// account and the file and no transaction is open while the chunk is written, so
	// a slow disk holds up this recording instead of every write on the server.
	record, err := ownRecording(e.App, e, id)
	if err != nil {
		return err
	}
	if record.GetBool("complete") {
		return conflict("recording_complete", "the recording is complete and can no longer change")
	}
	entries := readFiles(record)
	entry, exists := entries[name]
	if exists && entry.Encoding != encoding {
		return conflict("encoding_mismatch", fmt.Sprintf("this file is already stored as %s", entry.Encoding))
	}
	dir := recordingDir(e.App, record.Id)
	path := filepath.Join(dir, storedName(name, encoding))
	current, existed, err := storedSize(path)
	if err != nil {
		return err
	}
	if offset != current {
		apiErr := conflict("offset_mismatch", fmt.Sprintf("the file is %d bytes long, not %d", current, offset))
		apiErr.Headers = map[string]string{headerUploadOffset: strconv.FormatInt(current, 10)}
		return apiErr
	}
	chunkSize := int64(len(data))
	if current+chunkSize > maxFileBytes() {
		return tooLarge("file_too_large", fmt.Sprintf("a file is at most %d bytes", maxFileBytes()))
	}
	if recordingSize(e.App, record)+chunkSize > maxTotalBytes() {
		return tooLarge("recording_too_large", fmt.Sprintf("a recording is at most %d bytes", maxTotalBytes()))
	}
	account, err := e.App.FindRecordById("users", record.GetString("owner"))
	if err != nil {
		return err
	}
	if err := quotaCheck(int64(account.GetInt("storage_bytes")), chunkSize); err != nil {
		return err
	}
	if !exists {
		entry = FileEntry{Encoding: encoding}
	}
	if encoding == encodingGzip {
		if entry.Members+1 > maxMembers() {
			return tooLarge("too_many_members", "the file has too many compressed chunks")
		}
		if entry.UncompressedSize+member.Uncompressed > maxUncompressedFileBytes() {
			return tooLarge("file_too_large", "the file expands beyond the limit")
		}
		if totalUncompressed(e.App, record)+member.Uncompressed > maxUncompressedBytes() {
			return tooLarge("recording_too_large", "the recording expands beyond the limit")
		}
	}
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return err
	}
	if err := writeFileChunk(path, data); err != nil {
		return rollbackChunk(path, current, existed, err)
	}
	newSize := current + chunkSize

	// The bookkeeping is committed once the bytes are on disk. Nothing but the two
	// rows is written, so the database holds its write lock for that alone. A failure
	// here rolls the chunk back, so the file size and the rows agree and the same
	// offset can be sent again.
	err = e.App.RunInTransaction(func(txApp core.App) error {
		record, err := ownRecording(txApp, e, id)
		if err != nil {
			return err
		}
		if record.GetBool("complete") {
			return conflict("recording_complete", "the recording is complete and can no longer change")
		}
		entries := readFiles(record)
		entry, exists := entries[name]
		if exists && entry.Encoding != encoding {
			return conflict("encoding_mismatch", fmt.Sprintf("this file is already stored as %s", entry.Encoding))
		}
		account, err := txApp.FindRecordById("users", record.GetString("owner"))
		if err != nil {
			return err
		}
		// Another recording of the account may have taken the room since the check.
		if err := quotaCheck(int64(account.GetInt("storage_bytes")), chunkSize); err != nil {
			return err
		}
		if !exists {
			entry = FileEntry{Encoding: encoding}
		}
		if encoding == encodingGzip {
			entry.Members++
			entry.UncompressedSize += member.Uncompressed
		}
		entries[name] = entry
		record.Set("files", entries)
		record.Set("stored_bytes", int64(record.GetInt("stored_bytes"))+chunkSize)
		if err := txApp.Save(record); err != nil {
			return err
		}
		account.Set("storage_bytes", int64(account.GetInt("storage_bytes"))+chunkSize)
		return txApp.Save(account)
	})
	if err != nil {
		return rollbackChunk(path, current, existed, err)
	}
	e.Response.Header().Set(headerUploadOffset, strconv.FormatInt(newSize, 10))
	return e.NoContent(http.StatusNoContent)
}

// rollbackChunk undoes a chunk that could not be recorded: the file goes back to the
// size it had before the chunk, or disappears when this chunk created it. The answer
// is the original failure, with the rollback failure added when there is one, because
// a file that is longer than its rows would answer the next offset check with a size
// no chunk starts at.
func rollbackChunk(path string, size int64, existed bool, cause error) error {
	var err error
	if !existed {
		if removeErr := os.Remove(path); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			err = removeErr
		}
	} else if file, openErr := os.OpenFile(path, os.O_WRONLY, 0); openErr != nil {
		err = openErr
	} else {
		if truncateErr := file.Truncate(size); truncateErr != nil {
			err = truncateErr
		} else if syncErr := file.Sync(); syncErr != nil {
			err = syncErr
		}
		if closeErr := file.Close(); err == nil && closeErr != nil {
			err = closeErr
		}
	}
	if err != nil {
		return fmt.Errorf("%w (the chunk could not be rolled back: %v)", cause, err)
	}
	return cause
}

func totalUncompressed(app core.App, record *core.Record) int64 {
	var total int64
	for _, view := range fileViews(app, record) {
		total += view.UncompressedSize
	}
	return total
}

// GetFile serves a file. A gzip file is sent as stored when the client says it
// accepts gzip, and inflated otherwise, so every client gets the original bytes.
// An inflated download is bounded by and checked against the recorded
// uncompressed size.
func GetFile(e *core.RequestEvent) error {
	record, err := recordingFor(e, e.Request.PathValue("id"))
	if err != nil {
		return err
	}
	name := e.Request.PathValue("name")
	view, err := findFile(e.App, record, name)
	if err != nil {
		return err
	}
	dir := recordingDir(e.App, record.Id)
	stored := storedName(name, view.Encoding)
	if view.Encoding != encodingGzip {
		e.Response.Header().Set("Content-Type", "application/octet-stream")
		return e.FileFS(os.DirFS(dir), stored)
	}
	file, err := os.Open(filepath.Join(dir, stored))
	if err != nil {
		return err
	}
	defer file.Close()
	header := e.Response.Header()
	header.Set("Content-Type", "application/octet-stream")
	header.Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", name))
	if acceptsGzip(e.Request.Header.Get("Accept-Encoding")) {
		header.Set("Content-Encoding", encodingGzip)
		header.Set("Content-Length", strconv.FormatInt(view.Size, 10))
		_, err = io.Copy(e.Response, file)
		return err
	}
	reader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer reader.Close()
	if view.UncompressedSize > 0 {
		header.Set("Content-Length", strconv.FormatInt(view.UncompressedSize, 10))
	}
	return copyInflated(e.Response, reader, view.UncompressedSize)
}

// copyInflated writes a stored gzip stream inflated, bounded by the uncompressed
// size the recording declares. A stream that does not inflate to exactly that
// size, or that fails a member's CRC, aborts the connection: the response has
// already started by then, so the client must see a failed download instead of a
// short file that ends like a complete one.
func copyInflated(destination io.Writer, reader io.Reader, declared int64) error {
	written, err := io.Copy(destination, io.LimitReader(reader, declared+1))
	if err == nil && written == declared {
		return nil
	}
	panic(http.ErrAbortHandler)
}

func acceptsGzip(header string) bool {
	for _, part := range strings.Split(header, ",") {
		token := strings.TrimSpace(strings.SplitN(part, ";", 2)[0])
		if strings.EqualFold(token, "gzip") {
			return true
		}
	}
	return false
}

func DeleteFile(e *core.RequestEvent) error {
	name := e.Request.PathValue("name")
	if !isDataFile(name) {
		return notFound("file")
	}
	// A delete takes the same lock as an append, so a chunk cannot be written to a
	// file that this request is removing.
	release := recordingFileLocks.lock(e.Request.PathValue("id"))
	defer release()
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := ownRecording(txApp, e, e.Request.PathValue("id"))
		if err != nil {
			return err
		}
		if record.GetBool("complete") {
			return conflict("recording_complete", "the recording is complete and can no longer change")
		}
		entries := readFiles(record)
		entry, ok := entries[name]
		if !ok {
			return notFound("file")
		}
		encoding := entry.Encoding
		if encoding == "" {
			encoding = encodingIdentity
		}
		dir := recordingDir(txApp, record.Id)
		removed, _, err := storedSize(filepath.Join(dir, storedName(name, encoding)))
		if err != nil {
			return err
		}
		_ = os.Remove(filepath.Join(dir, storedName(name, encodingIdentity)))
		_ = os.Remove(filepath.Join(dir, storedName(name, encodingGzip)))
		delete(entries, name)
		record.Set("files", entries)
		record.Set("stored_bytes", max(int64(record.GetInt("stored_bytes"))-removed, 0))
		if err := txApp.Save(record); err != nil {
			return err
		}
		return addAccountBytes(txApp, record.GetString("owner"), -removed)
	})
	if err != nil {
		return err
	}
	return e.NoContent(http.StatusNoContent)
}

func appendFile(path string, data []byte) error {
	file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o640)
	if err != nil {
		return err
	}
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return err
	}
	return file.Close()
}
