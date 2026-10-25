package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
)

type metaRequest struct {
	Meta json.RawMessage `json:"meta"`
}

// AppendResult reports the state of one appended chunk.
type AppendResult struct {
	File            string `json:"file"`
	Offset          int64  `json:"offset"`
	Written         int    `json:"written"`
	Length          int64  `json:"length"`
	ValidatedLength int64  `json:"validated_length"`
	Frames          int    `json:"frames"`
	Lines           int    `json:"lines"`
	State           string `json:"state"`
	Error           string `json:"error"`
	Truncated       bool   `json:"truncated"`
}

func CreateSession(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	var body metaRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("invalid request body", err)
	}
	if len(body.Meta) == 0 {
		return e.BadRequestError("meta is required", nil)
	}
	if int64(len(body.Meta)) > maxMetaBytes() {
		return e.BadRequestError("meta.json is too large", nil)
	}
	meta, err := ValidateMeta(body.Meta)
	if err != nil {
		return e.BadRequestError(err.Error(), nil)
	}

	created := false
	var record *core.Record
	err = e.App.RunInTransaction(func(txApp core.App) error {
		existing, findErr := txApp.FindFirstRecordByFilter(
			"sessions",
			"owner = {:owner} && client_session_id = {:client}",
			dbx.Params{"owner": e.Auth.Id, "client": meta.SessionID},
		)
		if findErr == nil {
			record = existing
			if record.GetString("upload_status") == "uploaded" {
				return nil
			}
			dir, err := recordingDir(txApp, record.Id)
			if err != nil {
				return err
			}
			if err := writeMeta(dir, body.Meta); err != nil {
				return err
			}
			upload := readUpload(record)
			markMetaEntry(&upload, dir)
			record.Set("upload", upload)
			applyMeta(record, meta)
			return txApp.Save(record)
		}
		if !errors.Is(findErr, sql.ErrNoRows) {
			return findErr
		}

		collection, err := txApp.FindCollectionByNameOrId("sessions")
		if err != nil {
			return err
		}
		record = core.NewRecord(collection)
		record.Set("owner", e.Auth.Id)
		record.Set("client_session_id", meta.SessionID)
		record.Set("upload", Upload{Version: 1, Files: map[string]*UploadFile{}})
		record.Set("upload_error", "")
		applyMeta(record, meta)
		if err := txApp.Save(record); err != nil {
			return err
		}
		dir, err := recordingDir(txApp, record.Id)
		if err != nil {
			return err
		}
		if err := writeMeta(dir, body.Meta); err != nil {
			return err
		}
		upload := readUpload(record)
		markMetaEntry(&upload, dir)
		record.Set("upload", upload)
		created = true
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}

	record, err = e.App.FindRecordById("sessions", record.Id)
	if err != nil {
		return err
	}
	m, err := manifest(e.App, record)
	if err != nil {
		return err
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	return e.JSON(status, SessionUploadResponse{Session: serializeSession(record), Upload: m})
}

func GetUpload(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	record, err := findSessionForUser(e.App, e.Request.PathValue("id"), e.Auth)
	if err != nil {
		return err
	}
	m, err := manifest(e.App, record)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, SessionUploadResponse{Session: serializeSession(record), Upload: m})
}

func PutMeta(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	var body metaRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("invalid request body", err)
	}
	if len(body.Meta) == 0 {
		return e.BadRequestError("meta is required", nil)
	}
	if int64(len(body.Meta)) > maxMetaBytes() {
		return e.BadRequestError("meta.json is too large", nil)
	}
	meta, err := ValidateMeta(body.Meta)
	if err != nil {
		return e.BadRequestError(err.Error(), nil)
	}

	sessionID := e.Request.PathValue("id")
	err = e.App.RunInTransaction(func(txApp core.App) error {
		record, err := findSessionForUser(txApp, sessionID, e.Auth)
		if err != nil {
			return err
		}
		if record.GetString("upload_status") == "uploaded" {
			return apis.NewApiError(409, "session is already uploaded", nil)
		}
		if ownerClientID := record.GetString("client_session_id"); ownerClientID != "" && ownerClientID != meta.SessionID {
			return apis.NewBadRequestError("meta.session_id does not match this session", nil)
		}
		dir, err := recordingDir(txApp, record.Id)
		if err != nil {
			return err
		}
		if err := writeMeta(dir, body.Meta); err != nil {
			return err
		}
		upload := readUpload(record)
		markMetaEntry(&upload, dir)
		record.Set("upload", upload)
		applyMeta(record, meta)
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}
	record, err := e.App.FindRecordById("sessions", sessionID)
	if err != nil {
		return err
	}
	m, err := manifest(e.App, record)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, SessionUploadResponse{Session: serializeSession(record), Upload: m})
}

func PutFile(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	name := e.Request.PathValue("file")
	if !contains(allFiles, name) || name == metaFileName {
		return e.BadRequestError("unknown recording file", nil)
	}
	offset, err := strconv.ParseInt(strings.TrimSpace(e.Request.URL.Query().Get("offset")), 10, 64)
	if err != nil || offset < 0 {
		return e.BadRequestError("offset must be a non-negative integer", nil)
	}
	data, err := io.ReadAll(io.LimitReader(e.Request.Body, maxChunkBytes()+1))
	if err != nil {
		return e.BadRequestError("cannot read request body", err)
	}
	if len(data) == 0 {
		return e.BadRequestError("request body is empty", nil)
	}
	if int64(len(data)) > maxChunkBytes() {
		return e.Error(http.StatusRequestEntityTooLarge, "chunk is too large", nil)
	}

	sessionID := e.Request.PathValue("id")
	var result AppendResult
	err = e.App.RunInTransaction(func(txApp core.App) error {
		record, err := findSessionForUser(txApp, sessionID, e.Auth)
		if err != nil {
			return err
		}
		if record.GetString("upload_status") == "uploaded" {
			return apis.NewApiError(409, "session is already uploaded", nil)
		}
		path, err := recordingFile(txApp, record.Id, name)
		if err != nil {
			return err
		}
		current := fileSize(path)
		if offset != current {
			return apis.NewApiError(409, fmt.Sprintf("offset mismatch: expected %d, got %d", current, offset), nil)
		}
		if current+int64(len(data)) > maxFileBytes() {
			return apis.NewApiError(http.StatusRequestEntityTooLarge, fmt.Sprintf("recording file is too large (max %d bytes)", maxFileBytes()), nil)
		}
		if totalRecordingBytes(txApp, record.Id)+int64(len(data)) > maxTotalBytes() {
			return apis.NewApiError(http.StatusRequestEntityTooLarge, fmt.Sprintf("recording is too large (max %d bytes)", maxTotalBytes()), nil)
		}
		if err := appendFile(path, data); err != nil {
			return err
		}

		upload := readUpload(record)
		entry := uploadEntry(&upload, name)
		if name == eventsFileName {
			if result := validateEventsNow(path, entry); result != nil {
				return result
			}
		} else {
			stream := streamByFile[name]
			frameResult, err := validateLog(path, stream, entry.ValidatedLength)
			if err != nil {
				return err
			}
			entry.Frames += frameResult.Frames
			if frameResult.State == "corrupt" {
				_ = truncateFile(path, frameResult.ValidEnd)
				entry.Length = frameResult.ValidEnd
				entry.ValidatedLength = frameResult.ValidEnd
				entry.State = "corrupt"
				entry.Error = fmt.Sprintf("%s at byte %d", frameResult.Error, frameResult.CorruptAt)
				entry.CorruptAt = frameResult.CorruptAt
				entry.Truncated = true
			} else {
				entry.Length = frameResult.Length
				entry.ValidatedLength = frameResult.ValidEnd
				entry.State = frameResult.State
				entry.PendingBytes = frameResult.Pending
				entry.Error = ""
				entry.CorruptAt = -1
			}
			entry.Updated = nowString()
		}

		upload.Files[name] = entry
		record.Set("upload", upload)
		if record.GetString("upload_status") == "" || record.GetString("upload_status") == "none" {
			record.Set("upload_status", "pending")
		}
		if err := txApp.Save(record); err != nil {
			return err
		}
		result = AppendResult{
			File:            name,
			Offset:          offset,
			Written:         len(data),
			Length:          entry.Length,
			ValidatedLength: entry.ValidatedLength,
			Frames:          entry.Frames,
			Lines:           entry.Lines,
			State:           entry.State,
			Error:           entry.Error,
			Truncated:       entry.Truncated,
		}
		return nil
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, result)
}

func validateEventsNow(path string, entry *UploadFile) error {
	result, err := validateEvents(path, entry.ValidatedLength)
	if err != nil {
		return err
	}
	entry.Lines += result.Lines
	if result.State == "corrupt" {
		_ = truncateFile(path, result.ValidEnd)
		entry.Length = result.ValidEnd
		entry.ValidatedLength = result.ValidEnd
		entry.State = "corrupt"
		entry.Error = fmt.Sprintf("%s at byte %d", result.Error, result.CorruptAt)
		entry.CorruptAt = result.CorruptAt
		entry.Truncated = true
	} else {
		entry.Length = result.Length
		entry.ValidatedLength = result.ValidEnd
		entry.State = result.State
		entry.Error = ""
		entry.CorruptAt = -1
	}
	entry.Updated = nowString()
	return nil
}

func CompleteUpload(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	sessionID := e.Request.PathValue("id")
	var warnings []string
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := findSessionForUser(txApp, sessionID, e.Auth)
		if err != nil {
			return err
		}
		dir, err := recordingDir(txApp, record.Id)
		if err != nil {
			return err
		}
		meta, metaRaw, err := readMeta(dir)
		if err != nil {
			return apis.NewBadRequestError("meta.json is missing", nil)
		}
		if _, err := ValidateMeta(metaRaw); err != nil {
			return apis.NewBadRequestError(err.Error(), nil)
		}
		if meta.State == "recording" {
			return apis.NewApiError(409, "recording is still in progress", nil)
		}
		if record.GetString("upload_status") == "uploaded" {
			return nil
		}

		upload := readUpload(record)
		validFrames := 0
		for _, name := range allFiles {
			if name == metaFileName {
				continue
			}
			path, err := recordingFile(txApp, record.Id, name)
			if err != nil {
				return err
			}
			if fileSize(path) == 0 {
				continue
			}
			entry := uploadEntry(&upload, name)
			if name == eventsFileName {
				if err := finalizeEvents(path, entry); err != nil {
					return err
				}
			} else {
				if err := finalizeLog(path, streamByFile[name], entry); err != nil {
					return err
				}
				validFrames += entry.Frames
			}
			if entry.Truncated {
				warnings = append(warnings, name+": kept the valid prefix and truncated the bad tail")
			}
		}
		if validFrames == 0 {
			return apis.NewApiError(422, "recording has no valid frames", nil)
		}
		markMetaEntry(&upload, dir)
		upload.Completed = true
		upload.CompletedAt = nowString()
		record.Set("upload", upload)
		record.Set("upload_status", "uploaded")
		record.Set("uploaded_at", nowString())
		record.Set("upload_error", strings.Join(warnings, "; "))
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}
	record, err := e.App.FindRecordById("sessions", sessionID)
	if err != nil {
		return err
	}
	m, err := manifest(e.App, record)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, SessionUploadResponse{Session: serializeSession(record), Upload: m})
}

func finalizeLog(path string, stream byte, entry *UploadFile) error {
	size := fileSize(path)
	if size == 0 {
		entry.Length = 0
		entry.ValidatedLength = 0
		entry.State = "ok"
		return nil
	}
	result, err := validateLog(path, stream, entry.ValidatedLength)
	if err != nil {
		return err
	}
	entry.Frames += result.Frames
	if result.State == "corrupt" || result.State == "partial" {
		_ = truncateFile(path, result.ValidEnd)
		entry.Length = result.ValidEnd
		entry.ValidatedLength = result.ValidEnd
		entry.Truncated = true
		if result.State == "corrupt" {
			entry.State = "corrupt"
			entry.Error = fmt.Sprintf("%s at byte %d", result.Error, result.CorruptAt)
			entry.CorruptAt = result.CorruptAt
		} else {
			entry.State = "ok"
		}
	} else {
		entry.Length = result.Length
		entry.ValidatedLength = result.ValidEnd
		entry.State = "ok"
		entry.Error = ""
		entry.CorruptAt = -1
	}
	entry.Updated = nowString()
	return nil
}

func finalizeEvents(path string, entry *UploadFile) error {
	size := fileSize(path)
	if size == 0 {
		entry.Length = 0
		entry.ValidatedLength = 0
		entry.State = "ok"
		return nil
	}
	result, err := validateEvents(path, entry.ValidatedLength)
	if err != nil {
		return err
	}
	entry.Lines += result.Lines
	if result.State == "corrupt" || result.State == "partial" {
		_ = truncateFile(path, result.ValidEnd)
		entry.Length = result.ValidEnd
		entry.ValidatedLength = result.ValidEnd
		entry.Truncated = true
		if result.State == "corrupt" {
			entry.State = "corrupt"
			entry.Error = fmt.Sprintf("%s at byte %d", result.Error, result.CorruptAt)
			entry.CorruptAt = result.CorruptAt
		} else {
			entry.State = "ok"
		}
	} else {
		entry.Length = result.Length
		entry.ValidatedLength = result.ValidEnd
		entry.State = "ok"
		entry.Error = ""
		entry.CorruptAt = -1
	}
	entry.Updated = nowString()
	return nil
}

func markMetaEntry(upload *Upload, dir string) {
	size := fileSize(filepath.Join(dir, metaFileName))
	if size == 0 {
		return
	}
	entry := uploadEntry(upload, metaFileName)
	entry.Length = size
	entry.ValidatedLength = size
	entry.State = "ok"
	entry.Error = ""
	entry.Truncated = false
	entry.Updated = nowString()
}
