package api

import (
	"os"
	"path/filepath"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
)

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

// recordingFor finds a recording the caller may read: a user's own (or one an access
// token of theirs belongs to), or, for a worker, one it holds the lease of a running
// analysis for. Anything else is 404 so ids do not leak. The record API's rules make
// the same decision for its routes; this is what the custom file routes use.
func recordingFor(e *core.RequestEvent, id string) (*core.Record, error) {
	record, err := e.App.FindRecordById("recordings", id)
	if err != nil {
		return nil, notFound("recording")
	}
	switch roleOf(e) {
	case roleUser, roleToken:
		if record.GetString("owner") != callerID(e) {
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
	if err != nil || record.GetString("owner") != callerID(e) {
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

func recordingsBase(app core.App) string { return filepath.Join(app.DataDir(), "recordings") }

func removeRecordingDir(app core.App, id string) {
	if !recordingIDRE.MatchString(id) {
		return
	}
	_ = os.RemoveAll(filepath.Join(recordingsBase(app), id))
}
