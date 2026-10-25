package api

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
)

const (
	defaultMaxRecordingMB     = 256
	defaultMaxRecordingFileMB = 64
	defaultMaxUploadChunkMB   = 8
	defaultMaxMetaKB          = 64
	maxEventsBytes            = 8 * 1024 * 1024
)

type UploadFile struct {
	Length          int64  `json:"length"`
	ValidatedLength int64  `json:"validated_length"`
	Frames          int    `json:"frames"`
	Lines           int    `json:"lines"`
	State           string `json:"state"`
	Error           string `json:"error"`
	Truncated       bool   `json:"truncated"`
	CorruptAt       int64  `json:"corrupt_at,omitempty"`
	PendingBytes    int64  `json:"pending_bytes,omitempty"`
	Updated         string `json:"updated"`
}

type Upload struct {
	Version     int                    `json:"version"`
	Files       map[string]*UploadFile `json:"files"`
	Completed   bool                   `json:"completed"`
	CompletedAt string                 `json:"completed_at"`
}

type UploadLimits struct {
	MaxTotalBytes int64 `json:"max_total_bytes"`
	MaxFileBytes  int64 `json:"max_file_bytes"`
	MaxChunkBytes int64 `json:"max_chunk_bytes"`
}

type Manifest struct {
	Session      string                 `json:"session"`
	UploadStatus string                 `json:"upload_status"`
	UploadedAt   string                 `json:"uploaded_at"`
	UploadError  string                 `json:"upload_error"`
	Completed    bool                   `json:"completed"`
	CompletedAt  string                 `json:"completed_at"`
	TotalBytes   int64                  `json:"total_bytes"`
	Limits       UploadLimits           `json:"limits"`
	Meta         *RecordingMeta         `json:"meta"`
	Files        map[string]*UploadFile `json:"files"`
}

type SessionView struct {
	ID              string  `json:"id"`
	Owner           string  `json:"owner"`
	ClientSessionID string  `json:"client_session_id"`
	Device          string  `json:"device"`
	StartedAt       string  `json:"started_at"`
	EndedAt         string  `json:"ended_at"`
	DurationS       float64 `json:"duration_s"`
	State           string  `json:"state"`
	UploadStatus    string  `json:"upload_status"`
	UploadedAt      string  `json:"uploaded_at"`
	UploadError     string  `json:"upload_error"`
	Created         string  `json:"created"`
	Updated         string  `json:"updated"`
}

func envInt(name string, fallback int64) int64 {
	value := os.Getenv(name)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func maxTotalBytes() int64 {
	return envInt("TAIGA_MAX_RECORDING_MB", defaultMaxRecordingMB) * 1024 * 1024
}
func maxFileBytes() int64 {
	return envInt("TAIGA_MAX_RECORDING_FILE_MB", defaultMaxRecordingFileMB) * 1024 * 1024
}
func maxChunkBytes() int64 {
	return envInt("TAIGA_MAX_UPLOAD_CHUNK_MB", defaultMaxUploadChunkMB) * 1024 * 1024
}
func maxMetaBytes() int64 { return envInt("TAIGA_MAX_META_KB", defaultMaxMetaKB) * 1024 }

func nowString() string {
	return types.NowDateTime().String()
}

func pbDateMillis(ms int64) string {
	return time.UnixMilli(ms).UTC().Format("2006-01-02 15:04:05.000Z")
}

func afterSeconds(seconds int) string {
	return pbDateMillis(time.Now().Add(time.Duration(seconds) * time.Second).UnixMilli())
}

func recordingDir(app core.App, sessionID string) (string, error) {
	if !sessionIDRE.MatchString(sessionID) {
		return "", apis.NewBadRequestError("invalid session id", nil)
	}
	base := filepath.Join(app.DataDir(), "recordings")
	if err := os.MkdirAll(base, 0o750); err != nil {
		return "", err
	}
	dir := filepath.Join(base, sessionID)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return "", err
	}
	return dir, nil
}

func recordingFile(app core.App, sessionID, name string) (string, error) {
	if !contains(allFiles, name) {
		return "", apis.NewBadRequestError("unknown recording file", nil)
	}
	dir, err := recordingDir(app, sessionID)
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, name), nil
}

func removeRecordingDir(app core.App, sessionID string) {
	if !sessionIDRE.MatchString(sessionID) {
		return
	}
	_ = os.RemoveAll(filepath.Join(app.DataDir(), "recordings", sessionID))
}

func fileSize(path string) int64 {
	info, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return info.Size()
}

func totalRecordingBytes(app core.App, sessionID string) int64 {
	var total int64
	for _, name := range allFiles {
		path, err := recordingFile(app, sessionID, name)
		if err != nil {
			continue
		}
		total += fileSize(path)
	}
	return total
}

func readMeta(dir string) (RecordingMeta, []byte, error) {
	path := filepath.Join(dir, metaFileName)
	raw, err := os.ReadFile(path)
	if err != nil {
		return RecordingMeta{}, nil, err
	}
	var meta RecordingMeta
	if err := json.Unmarshal(raw, &meta); err != nil {
		return RecordingMeta{}, raw, err
	}
	return meta, raw, nil
}

func writeMeta(dir string, raw []byte) error {
	path := filepath.Join(dir, metaFileName)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o640); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func appendFile(path string, data []byte) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o640)
	if err != nil {
		return err
	}
	defer file.Close()
	written, err := file.Write(data)
	if err != nil {
		return err
	}
	if written != len(data) {
		return fmt.Errorf("short write: %d/%d", written, len(data))
	}
	return file.Sync()
}

func truncateFile(path string, size int64) error {
	file, err := os.OpenFile(path, os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	if err := file.Truncate(size); err != nil {
		return err
	}
	return file.Sync()
}

func readUpload(record *core.Record) Upload {
	upload := Upload{Version: 1, Files: map[string]*UploadFile{}}
	_ = record.UnmarshalJSONField("upload", &upload)
	if upload.Version == 0 {
		upload.Version = 1
	}
	if upload.Files == nil {
		upload.Files = map[string]*UploadFile{}
	}
	return upload
}

func uploadEntry(upload *Upload, name string) *UploadFile {
	entry := upload.Files[name]
	if entry == nil {
		entry = &UploadFile{State: "unvalidated"}
		upload.Files[name] = entry
	}
	if entry.State == "" {
		entry.State = "unvalidated"
	}
	return entry
}

func manifest(app core.App, record *core.Record) (Manifest, error) {
	dir, err := recordingDir(app, record.Id)
	if err != nil {
		return Manifest{}, err
	}
	upload := readUpload(record)
	files := map[string]*UploadFile{}
	var total int64

	for _, name := range allFiles {
		size := fileSize(filepath.Join(dir, name))
		existing := upload.Files[name]
		if size == 0 && existing == nil {
			continue
		}
		entry := uploadEntry(&upload, name)
		entry.Length = size
		if entry.ValidatedLength > size {
			entry.ValidatedLength = 0
			entry.State = "unvalidated"
			entry.Error = ""
		}
		files[name] = entry
		total += size
	}

	var meta *RecordingMeta
	if parsed, _, err := readMeta(dir); err == nil {
		meta = &parsed
	}

	return Manifest{
		Session:      record.Id,
		UploadStatus: record.GetString("upload_status"),
		UploadedAt:   record.GetString("uploaded_at"),
		UploadError:  record.GetString("upload_error"),
		Completed:    upload.Completed,
		CompletedAt:  upload.CompletedAt,
		TotalBytes:   total,
		Limits: UploadLimits{
			MaxTotalBytes: maxTotalBytes(),
			MaxFileBytes:  maxFileBytes(),
			MaxChunkBytes: maxChunkBytes(),
		},
		Meta:  meta,
		Files: files,
	}, nil
}

func serializeSession(record *core.Record) SessionView {
	return SessionView{
		ID:              record.Id,
		Owner:           record.GetString("owner"),
		ClientSessionID: record.GetString("client_session_id"),
		Device:          record.GetString("device"),
		StartedAt:       record.GetString("started_at"),
		EndedAt:         record.GetString("ended_at"),
		DurationS:       record.GetFloat("duration_s"),
		State:           record.GetString("state"),
		UploadStatus:    record.GetString("upload_status"),
		UploadedAt:      record.GetString("uploaded_at"),
		UploadError:     record.GetString("upload_error"),
		Created:         record.GetString("created"),
		Updated:         record.GetString("updated"),
	}
}

func applyMeta(record *core.Record, meta RecordingMeta) {
	record.Set("device", meta.Device())
	record.Set("started_at", pbDateMillis(meta.StartUTC))
	if meta.EndUTC == nil {
		record.Set("ended_at", "")
	} else {
		record.Set("ended_at", pbDateMillis(*meta.EndUTC))
	}
	record.Set("duration_s", float64(meta.DurationSeconds()))
	record.Set("state", meta.State)
	if record.GetString("client_session_id") == "" {
		record.Set("client_session_id", meta.SessionID)
	}
	if record.GetString("upload_status") == "" || record.GetString("upload_status") == "none" {
		record.Set("upload_status", "pending")
	}
}

func getSession(app core.App, sessionID, owner string) (*core.Record, error) {
	record, err := app.FindRecordById("sessions", sessionID)
	if err != nil || record.GetString("owner") != owner {
		return nil, apis.NewNotFoundError("session not found", nil)
	}
	return record, nil
}

func contains(items []string, value string) bool {
	for _, item := range items {
		if item == value {
			return true
		}
	}
	return false
}
