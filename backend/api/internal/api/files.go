package api

import (
	"compress/gzip"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

const (
	encodingIdentity = "identity"
	encodingGzip     = "gzip"

	headerUploadOffset = "Upload-Offset"
)

// dataFiles are the raw files of a recording. meta.json is not one of them: it lives
// in the recording itself.
var dataFiles = []string{"hr.log", "ecg.log", "acc.log", "events.jsonl"}

var streamFiles = []string{"hr.log", "ecg.log", "acc.log"}

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
	for _, candidate := range dataFiles {
		if candidate == name {
			return true
		}
	}
	return false
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

func fileSize(path string) int64 {
	info, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return info.Size()
}

// fileViews lists the files that exist, in a stable order. The size comes from the
// disk, so it is right even after a crash between writing a chunk and recording it.
func fileViews(app core.App, record *core.Record) []FileView {
	entries := readFiles(record)
	dir := recordingDir(app, record.Id)
	views := []FileView{}
	for _, name := range dataFiles {
		entry, ok := entries[name]
		if !ok {
			continue
		}
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

func streamBytes(app core.App, record *core.Record) int64 {
	var total int64
	for _, view := range fileViews(app, record) {
		for _, name := range streamFiles {
			if view.Name == name {
				total += view.Size
			}
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

	var newSize int64
	err = e.App.RunInTransaction(func(txApp core.App) error {
		record, err := ownRecording(txApp, e, e.Request.PathValue("id"))
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
		dir := recordingDir(txApp, record.Id)
		path := filepath.Join(dir, storedName(name, encoding))
		current := fileSize(path)
		if offset != current {
			apiErr := conflict("offset_mismatch", fmt.Sprintf("the file is %d bytes long, not %d", current, offset))
			apiErr.Headers = map[string]string{headerUploadOffset: strconv.FormatInt(current, 10)}
			return apiErr
		}
		if current+int64(len(data)) > maxFileBytes() {
			return tooLarge("file_too_large", fmt.Sprintf("a file is at most %d bytes", maxFileBytes()))
		}
		if recordingSize(txApp, record)+int64(len(data)) > maxTotalBytes() {
			return tooLarge("recording_too_large", fmt.Sprintf("a recording is at most %d bytes", maxTotalBytes()))
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
			if totalUncompressed(txApp, record)+member.Uncompressed > maxUncompressedBytes() {
				return tooLarge("recording_too_large", "the recording expands beyond the limit")
			}
			entry.Members++
			entry.UncompressedSize += member.Uncompressed
		}
		if err := os.MkdirAll(dir, 0o750); err != nil {
			return err
		}
		if err := appendFile(path, data); err != nil {
			return err
		}
		newSize = current + int64(len(data))
		entries[name] = entry
		record.Set("files", entries)
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}
	e.Response.Header().Set(headerUploadOffset, strconv.FormatInt(newSize, 10))
	return e.NoContent(http.StatusNoContent)
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
	_, err = io.Copy(e.Response, reader)
	return err
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
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := ownRecording(txApp, e, e.Request.PathValue("id"))
		if err != nil {
			return err
		}
		if record.GetBool("complete") {
			return conflict("recording_complete", "the recording is complete and can no longer change")
		}
		entries := readFiles(record)
		if _, ok := entries[name]; !ok {
			return notFound("file")
		}
		dir := recordingDir(txApp, record.Id)
		_ = os.Remove(filepath.Join(dir, storedName(name, encodingIdentity)))
		_ = os.Remove(filepath.Join(dir, storedName(name, encodingGzip)))
		delete(entries, name)
		record.Set("files", entries)
		return txApp.Save(record)
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
