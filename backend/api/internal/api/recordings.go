package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
)

// RecordingView is a recording as the API shows it. Meta is the recording's
// meta.json; the other fields are derived from it, so a list does not need to read
// the files.
type RecordingView struct {
	ID        string          `json:"id"`
	State     string          `json:"state"`
	Device    string          `json:"device"`
	StartedAt string          `json:"started_at"`
	EndedAt   *string         `json:"ended_at"`
	DurationS float64         `json:"duration_s"`
	Complete  bool            `json:"complete"`
	SizeBytes int64           `json:"size_bytes"`
	Meta      json.RawMessage `json:"meta" schema:"RecordingMeta"`
	Created   string          `json:"created"`
	Updated   string          `json:"updated"`
}

type RecordingList struct {
	Items  []RecordingView `json:"items"`
	Limit  int             `json:"limit"`
	Offset int             `json:"offset"`
	Total  int64           `json:"total"`
}

// RecordingPatch is the only change a recording accepts after it is created.
type RecordingPatch struct {
	Complete *bool `json:"complete"`
}

var recordingSorts = map[string]string{
	"started_at": "started_at ASC", "-started_at": "started_at DESC",
	"created": "created ASC", "-created": "created DESC",
	"duration_s": "duration_s ASC", "-duration_s": "duration_s DESC",
}

func serializeRecording(app core.App, record *core.Record) RecordingView {
	meta := json.RawMessage(record.GetString("meta"))
	if len(meta) == 0 {
		meta = json.RawMessage("null")
	}
	started := rfc3339(record.GetString("started_at"))
	view := RecordingView{
		ID:        record.Id,
		State:     record.GetString("state"),
		Device:    record.GetString("device"),
		EndedAt:   rfc3339(record.GetString("ended_at")),
		DurationS: record.GetFloat("duration_s"),
		Complete:  record.GetBool("complete"),
		SizeBytes: recordingSize(app, record),
		Meta:      meta,
		Created:   derefString(rfc3339(record.GetString("created"))),
		Updated:   derefString(rfc3339(record.GetString("updated"))),
	}
	if started != nil {
		view.StartedAt = *started
	}
	return view
}

func derefString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

// applyMeta copies what a recording's meta.json says into the derived columns.
func applyMeta(record *core.Record, meta RecordingMeta, raw []byte) {
	record.Set("state", meta.State)
	record.Set("device", meta.Device())
	record.Set("started_at", pbDateMillis(meta.StartUTC))
	if meta.EndUTC == nil {
		record.Set("ended_at", "")
	} else {
		record.Set("ended_at", pbDateMillis(*meta.EndUTC))
	}
	record.Set("duration_s", float64(meta.DurationSeconds()))
	record.Set("meta", types.JSONRaw(raw))
}

// recordingFor finds a recording the caller may read: a user's own, or, for a
// worker, one it holds the lease of a running analysis for. Anything else is 404 so
// ids do not leak.
func recordingFor(e *core.RequestEvent, id string) (*core.Record, error) {
	record, err := e.App.FindRecordById("recordings", id)
	if err != nil {
		return nil, notFound("recording")
	}
	switch roleOf(e) {
	case roleUser:
		if record.GetString("owner") != e.Auth.Id {
			return nil, notFound("recording")
		}
	case roleWorker:
		if !holdsLease(e.App, e.Auth.Id, id) {
			return nil, notFound("recording")
		}
	default:
		return nil, notFound("recording")
	}
	return record, nil
}

// ownRecording is recordingFor for endpoints only a user may call.
func ownRecording(app core.App, e *core.RequestEvent, id string) (*core.Record, error) {
	record, err := app.FindRecordById("recordings", id)
	if err != nil || record.GetString("owner") != e.Auth.Id {
		return nil, notFound("recording")
	}
	return record, nil
}

func holdsLease(app core.App, worker, recording string) bool {
	_, err := app.FindFirstRecordByFilter(
		"analyses",
		"recording = {:recording} && status = 'running' && worker = {:worker} && lease_until > {:now}",
		dbx.Params{"recording": recording, "worker": worker, "now": nowString()},
	)
	return err == nil
}

func ListRecordings(e *core.RequestEvent) error {
	query := e.Request.URL.Query()
	limit, offset, err := pageParams(query)
	if err != nil {
		return err
	}
	conditions := []dbx.Expression{dbx.HashExp{"owner": e.Auth.Id}}
	if state := strings.TrimSpace(query.Get("state")); state != "" {
		if !states[state] {
			return badRequest("invalid_query", "state is not a recording state")
		}
		conditions = append(conditions, dbx.HashExp{"state": state})
	}
	switch strings.TrimSpace(query.Get("complete")) {
	case "":
	case "true":
		conditions = append(conditions, dbx.HashExp{"complete": true})
	case "false":
		conditions = append(conditions, dbx.HashExp{"complete": false})
	default:
		return badRequest("invalid_query", "complete must be true or false")
	}
	if from := strings.TrimSpace(query.Get("from")); from != "" {
		value, err := parseQueryDate(from)
		if err != nil {
			return badRequest("invalid_query", "from is not a date")
		}
		conditions = append(conditions, dbx.NewExp("started_at >= {:from}", dbx.Params{"from": value}))
	}
	if to := strings.TrimSpace(query.Get("to")); to != "" {
		value, err := parseQueryDate(to)
		if err != nil {
			return badRequest("invalid_query", "to is not a date")
		}
		conditions = append(conditions, dbx.NewExp("started_at <= {:to}", dbx.Params{"to": value}))
	}
	sortKey := strings.TrimSpace(query.Get("sort"))
	if sortKey == "" {
		sortKey = "-started_at"
	}
	order, ok := recordingSorts[sortKey]
	if !ok {
		return badRequest("invalid_query", "unknown sort")
	}

	total, err := e.App.CountRecords("recordings", conditions...)
	if err != nil {
		return err
	}
	records := []*core.Record{}
	q := e.App.RecordQuery("recordings").OrderBy(order, "id ASC").Limit(int64(limit)).Offset(int64(offset))
	for _, condition := range conditions {
		q = q.AndWhere(condition)
	}
	if err := q.All(&records); err != nil {
		return err
	}
	items := make([]RecordingView, 0, len(records))
	for _, record := range records {
		items = append(items, serializeRecording(e.App, record))
	}
	return e.JSON(http.StatusOK, RecordingList{Items: items, Limit: limit, Offset: offset, Total: total})
}

func PutRecording(e *core.RequestEvent) error {
	id := e.Request.PathValue("id")
	if !recordingIDRE.MatchString(id) {
		return badRequest("invalid_id", "a recording id is 1 to 40 letters, digits, '_' or '-'")
	}
	raw, err := io.ReadAll(io.LimitReader(e.Request.Body, maxMetaBytes()+1))
	if err != nil {
		return badRequest("unreadable_body", "cannot read the request body")
	}
	if int64(len(raw)) > maxMetaBytes() {
		return tooLarge("meta_too_large", "meta.json is too large")
	}
	meta, err := ValidateMeta(raw)
	if err != nil {
		return unprocessable("invalid_meta", err.Error())
	}
	if meta.SessionID != id {
		return unprocessable("invalid_meta", "meta.session_id must equal the recording id in the path")
	}

	created := false
	var saved *core.Record
	err = e.App.RunInTransaction(func(txApp core.App) error {
		existing, findErr := txApp.FindRecordById("recordings", id)
		switch {
		case findErr == nil:
			if existing.GetString("owner") != e.Auth.Id {
				return conflict("id_taken", "this recording id belongs to another account")
			}
			if existing.GetBool("complete") {
				return conflict("recording_complete", "the recording is complete and can no longer change")
			}
			applyMeta(existing, meta, raw)
			saved = existing
		case errors.Is(findErr, sql.ErrNoRows):
			collection, err := txApp.FindCollectionByNameOrId("recordings")
			if err != nil {
				return err
			}
			record := core.NewRecord(collection)
			record.Id = id
			record.Set("owner", e.Auth.Id)
			record.Set("complete", false)
			record.Set("files", map[string]FileEntry{})
			applyMeta(record, meta, raw)
			saved = record
			created = true
		default:
			return findErr
		}
		return txApp.Save(saved)
	})
	if err != nil {
		return err
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
		e.Response.Header().Set("Location", "/v1/recordings/"+id)
	}
	return e.JSON(status, serializeRecording(e.App, saved))
}

func GetRecording(e *core.RequestEvent) error {
	record, err := recordingFor(e, e.Request.PathValue("id"))
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, serializeRecording(e.App, record))
}

func PatchRecording(e *core.RequestEvent) error {
	var body RecordingPatch
	if err := json.NewDecoder(io.LimitReader(e.Request.Body, 4096)).Decode(&body); err != nil || body.Complete == nil {
		return badRequest("invalid_body", `the body must be {"complete": true}`)
	}
	var saved *core.Record
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := ownRecording(txApp, e, e.Request.PathValue("id"))
		if err != nil {
			return err
		}
		saved = record
		complete := record.GetBool("complete")
		if !*body.Complete {
			if complete {
				return conflict("recording_complete", "a complete recording cannot be reopened")
			}
			return nil
		}
		if complete {
			return nil
		}
		if record.GetString("state") == "recording" {
			return conflict("recording_in_progress", "the recording is still in progress")
		}
		if streamBytes(txApp, record) == 0 {
			return unprocessable("no_data", "the recording has no stream data")
		}
		record.Set("complete", true)
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, serializeRecording(e.App, saved))
}

func DeleteRecording(e *core.RequestEvent) error {
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := ownRecording(txApp, e, e.Request.PathValue("id"))
		if err != nil {
			return err
		}
		return txApp.Delete(record)
	})
	if err != nil {
		return err
	}
	return e.NoContent(http.StatusNoContent)
}

func recordingsBase(app core.App) string { return filepath.Join(app.DataDir(), "recordings") }

func removeRecordingDir(app core.App, id string) {
	if !recordingIDRE.MatchString(id) {
		return
	}
	_ = os.RemoveAll(filepath.Join(recordingsBase(app), id))
}

func pageParams(query map[string][]string) (limit, offset int, err error) {
	limit, offset = 50, 0
	if values := query["limit"]; len(values) > 0 && values[0] != "" {
		limit = atoiOr(values[0], -1)
		if limit < 1 || limit > 200 {
			return 0, 0, badRequest("invalid_query", "limit must be between 1 and 200")
		}
	}
	if values := query["offset"]; len(values) > 0 && values[0] != "" {
		offset = atoiOr(values[0], -1)
		if offset < 0 {
			return 0, 0, badRequest("invalid_query", "offset must be 0 or more")
		}
	}
	return limit, offset, nil
}

func parseQueryDate(value string) (string, error) {
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, pbDateLayout, "2006-01-02"} {
		if parsed, err := time.Parse(layout, value); err == nil {
			return pbDateMillis(parsed.UnixMilli()), nil
		}
	}
	return "", &time.ParseError{Value: value, Layout: time.RFC3339}
}

func atoiOr(value string, fallback int) int {
	parsed, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil {
		return fallback
	}
	return parsed
}
