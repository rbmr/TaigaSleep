package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

// The record API's rules decide who may reach a record; these hooks decide what
// a request may do to it. Everything derived from a recording's meta.json is
// computed here, so a client sends its metadata and the server owns the columns
// that make a list cheap. The same goes for an analysis: the client asks for an
// algorithm, the server fills in the version, the parameter hash and the queue
// state.

func registerRecordHooks(app core.App) {
	app.OnRecordCreateRequest("recordings").BindFunc(createRecordingHook)
	app.OnRecordUpdateRequest("recordings").BindFunc(updateRecordingHook)
	app.OnRecordDeleteRequest("recordings").BindFunc(deleteRecordingHook)
	app.OnRecordCreateRequest("analyses").BindFunc(createAnalysisHook)
}

// requestError is a failure in PocketBase's error shape with the API's stable code
// under data.code.
func requestError(e *core.RecordRequestEvent, status int, code, message string) error {
	apiErr := e.Error(status, message, nil)
	apiErr.Data = map[string]any{"code": code}
	return apiErr
}

// submittedFields is the part of the record the request actually sent, after
// PocketBase resolved the modifiers, so a hook can tell an omitted field from one
// set to its zero value.
func submittedFields(e *core.RecordRequestEvent) (map[string]any, error) {
	info, err := e.RequestInfo()
	if err != nil {
		return nil, requestError(e, http.StatusBadRequest, "invalid_body", "cannot read the request body")
	}
	return info.Body, nil
}

// createRecordingHook derives a recording from the meta.json it was sent with. The
// record id is the client's, and meta.session_id has to agree with it; everything
// else (owner, state, device, times, duration, file bookkeeping) is the server's.
func createRecordingHook(e *core.RecordRequestEvent) error {
	if e.Auth == nil || e.Auth.Collection().Name != "users" {
		return requestError(e, http.StatusForbidden, "forbidden", "only a user creates a recording")
	}
	body, err := submittedFields(e)
	if err != nil {
		return err
	}
	for key := range body {
		if key != "id" && key != "meta" {
			return requestError(e, http.StatusBadRequest, "invalid_body", "a recording is created with its id and meta only")
		}
	}
	meta, raw, err := recordingMeta(e, e.Record.GetString("meta"))
	if err != nil {
		return err
	}
	if e.Record.Id == "" || meta.SessionID != e.Record.Id {
		return requestError(e, http.StatusUnprocessableEntity, "invalid_meta", "meta.session_id must equal the recording id")
	}
	// The client chooses the id, so repeating a create after a lost response is how a
	// retry is made: the metadata is replaced like an update would, or the reason it
	// cannot be is the same reason a separate update would give.
	if existing, findErr := e.App.FindRecordById("recordings", e.Record.Id); findErr == nil {
		if existing.GetString("owner") != e.Auth.Id {
			return requestError(e, http.StatusConflict, "id_taken", "this recording id belongs to another account")
		}
		if existing.GetBool("complete") {
			return requestError(e, http.StatusConflict, "recording_complete", "the recording is complete and can no longer change")
		}
		applyMeta(existing, meta, raw)
		if err := e.App.Save(existing); err != nil {
			return e.InternalServerError("cannot replace the recording's metadata", err)
		}
		return e.JSON(http.StatusOK, existing)
	} else if !errors.Is(findErr, sql.ErrNoRows) {
		return e.InternalServerError("cannot look up the recording", findErr)
	}
	applyMeta(e.Record, meta, raw)
	e.Record.Set("owner", e.Auth.Id)
	e.Record.Set("complete", false)
	e.Record.Set("files", map[string]FileEntry{})
	e.Record.Set("stored_bytes", 0)
	return e.Next()
}

// updateRecordingHook accepts a new meta.json until the recording is sealed, and the
// one change that seals it. A complete recording can no longer change, and a
// recording whose night is still on the tracker cannot be sealed.
func updateRecordingHook(e *core.RecordRequestEvent) error {
	if e.Auth == nil || e.Auth.Collection().Name != "users" {
		return requestError(e, http.StatusForbidden, "forbidden", "only the recording's owner changes it")
	}
	body, err := submittedFields(e)
	if err != nil {
		return err
	}
	for key := range body {
		if key != "meta" && key != "complete" {
			return requestError(e, http.StatusBadRequest, "invalid_body", "a recording accepts meta and complete only")
		}
	}
	_, hasMeta := body["meta"]
	_, hasComplete := body["complete"]

	original := e.Record.Original()
	if original != nil && original.GetBool("complete") {
		if hasMeta {
			return requestError(e, http.StatusConflict, "recording_complete", "the recording is complete and can no longer change")
		}
		if hasComplete && !e.Record.GetBool("complete") {
			return requestError(e, http.StatusConflict, "recording_complete", "a complete recording cannot be reopened")
		}
		return e.Next()
	}

	if hasMeta {
		meta, raw, err := recordingMeta(e, e.Record.GetString("meta"))
		if err != nil {
			return err
		}
		if meta.SessionID != e.Record.Id {
			return requestError(e, http.StatusUnprocessableEntity, "invalid_meta", "meta.session_id must equal the recording id")
		}
		applyMeta(e.Record, meta, raw)
	}

	if hasComplete && e.Record.GetBool("complete") {
		// A recording whose rest is still on the tracker is not finished either: its
		// files may still grow once the fetch adds the missing samples.
		switch e.Record.GetString("state") {
		case "recording":
			return requestError(e, http.StatusConflict, "recording_in_progress", "the recording is still in progress")
		case "fetch_pending":
			return requestError(e, http.StatusConflict, "recording_in_progress", "the rest of the recording is still on the tracker")
		}
		if streamBytes(e.App, e.Record) == 0 {
			return requestError(e, http.StatusUnprocessableEntity, "no_data", "the recording has no stream data")
		}
		e.Record.Set("complete", true)
	}
	return e.Next()
}

// deleteRecordingHook takes the recording's bytes off the account total before the
// delete, so the quota frees up with the delete itself. If the delete fails the
// total is put back.
func deleteRecordingHook(e *core.RecordRequestEvent) error {
	owner := e.Record.GetString("owner")
	size := int64(e.Record.GetInt("stored_bytes"))
	if size == 0 {
		return e.Next()
	}
	if err := addAccountBytes(e.App, owner, -size); err != nil {
		return e.InternalServerError("cannot update the account's stored bytes", err)
	}
	if err := e.Next(); err != nil {
		_ = addAccountBytes(e.App, owner, size)
		return err
	}
	return nil
}

// createAnalysisHook turns a request for an algorithm into a queued analysis: it
// checks the recording and the algorithm against what a worker announces, fills in
// the version and the parameter hash, and returns the analysis that already exists
// for the same input instead of queueing it twice.
func createAnalysisHook(e *core.RecordRequestEvent) error {
	if e.Auth == nil || e.Auth.Collection().Name != "users" {
		return requestError(e, http.StatusForbidden, "forbidden", "only the recording's owner asks for an analysis")
	}
	body, err := submittedFields(e)
	if err != nil {
		return err
	}
	for key := range body {
		switch key {
		case "recording", "algorithm", "version", "params":
		default:
			return requestError(e, http.StatusBadRequest, "invalid_body", "an analysis is requested with recording, algorithm, version and params only")
		}
	}

	recording, err := e.App.FindRecordById("recordings", e.Record.GetString("recording"))
	if err != nil || recording.GetString("owner") != e.Auth.Id {
		return requestError(e, http.StatusNotFound, "not_found", "recording not found")
	}
	if !recording.GetBool("complete") {
		return requestError(e, http.StatusConflict, "recording_incomplete", "the recording has not been completed yet")
	}

	algorithm := e.Record.GetString("algorithm")
	if !algorithmIDRE.MatchString(algorithm) {
		return requestError(e, http.StatusBadRequest, "invalid_algorithm", "algorithm is not a valid algorithm id")
	}
	requested := strings.TrimSpace(e.Record.GetString("version"))
	if len(requested) > 100 {
		return requestError(e, http.StatusBadRequest, "invalid_version", "version is too long")
	}
	offered, err := resolveAlgorithm(e.App, algorithm, requested)
	if err != nil {
		return requestError(e, http.StatusUnprocessableEntity, "unknown_algorithm", err.Error())
	}

	params := map[string]any{}
	if raw := strings.TrimSpace(e.Record.GetString("params")); raw != "" && raw != "null" {
		if len(raw) > 16384 {
			return requestError(e, http.StatusBadRequest, "invalid_params", "params are too large")
		}
		if err := json.Unmarshal([]byte(raw), &params); err != nil || params == nil {
			return requestError(e, http.StatusBadRequest, "invalid_params", "params must be a JSON object")
		}
	}
	if err := validateParams(offered.ParamsSchema, params); err != nil {
		return requestError(e, http.StatusUnprocessableEntity, "invalid_params", err.Error())
	}
	hash, err := paramsHash(params)
	if err != nil {
		return requestError(e, http.StatusBadRequest, "invalid_params", "params are invalid")
	}

	existing, findErr := e.App.FindFirstRecordByFilter(
		"analyses",
		"recording = {:recording} && algorithm = {:algorithm} && version = {:version} && params_hash = {:hash}",
		dbx.Params{"recording": recording.Id, "algorithm": algorithm, "version": offered.Version, "hash": hash},
	)
	switch {
	case findErr == nil && existing.GetString("status") != statusFailed:
		// The same request is answered with the analysis that is already there.
		return e.JSON(http.StatusOK, existing)
	case findErr == nil:
		// A failed analysis is replaced: asking again is how a retry is made.
		if err := e.App.Delete(existing); err != nil {
			return e.InternalServerError("cannot replace the failed analysis", err)
		}
	case !errors.Is(findErr, sql.ErrNoRows):
		return e.InternalServerError("cannot look up the analysis", findErr)
	}

	e.Record.Set("version", offered.Version)
	e.Record.Set("params", params)
	e.Record.Set("params_hash", hash)
	e.Record.Set("status", statusQueued)
	e.Record.Set("error", "")
	e.Record.Set("attempts", 0)
	e.Record.Set("available_at", nowString())
	e.Record.Set("worker", "")
	e.Record.Set("lease_until", "")
	e.Record.Set("finished_at", "")
	e.Record.Set("result", nil)
	e.Record.Set("summary", nil)
	if err := e.Next(); err != nil {
		// Two identical requests can pass the lookup above before either has saved;
		// the unique index keeps one row, and the request that lost the race answers
		// with that row instead of an error. Asking twice is meant to be one analysis.
		if raced, findErr := e.App.FindFirstRecordByFilter(
			"analyses",
			"recording = {:recording} && algorithm = {:algorithm} && version = {:version} && params_hash = {:hash}",
			dbx.Params{"recording": recording.Id, "algorithm": algorithm, "version": offered.Version, "hash": hash},
		); findErr == nil && raced.GetString("status") != statusFailed {
			return e.JSON(http.StatusOK, raced)
		}
		return err
	}
	return nil
}

// recordingMeta validates the meta.json a request carries and returns it with its
// raw bytes, which are stored verbatim so a client keeps fields this build does not
// know.
func recordingMeta(e *core.RecordRequestEvent, rawJSON string) (RecordingMeta, []byte, error) {
	raw := []byte(rawJSON)
	if len(raw) == 0 || string(raw) == "null" {
		return RecordingMeta{}, nil, requestError(e, http.StatusBadRequest, "invalid_meta", "meta is required")
	}
	if int64(len(raw)) > maxMetaBytes() {
		return RecordingMeta{}, nil, requestError(e, http.StatusRequestEntityTooLarge, "meta_too_large", "meta.json is too large")
	}
	meta, err := ValidateMeta(raw)
	if err != nil {
		return RecordingMeta{}, nil, requestError(e, http.StatusBadRequest, "invalid_meta", err.Error())
	}
	return meta, raw, nil
}

// resolveAlgorithm picks the analysis version a request runs: the one asked for, or
// the newest version a worker that is still announcing offers. A worker that went
// away does not make its newer version the default; when none is announcing, the
// newest known one still beats refusing the request, because the analysis waits in
// the queue for a worker to come back.
func resolveAlgorithm(app core.App, algorithm, requested string) (AlgorithmView, error) {
	cutoff := pbDateMillis(time.Now().Add(-time.Duration(envInt("TAIGA_WORKER_TTL_S", 900)) * time.Second).UnixMilli())
	live, all := map[string]AlgorithmView{}, map[string]AlgorithmView{}
	for _, offered := range registeredAlgorithms(app) {
		if offered.ID != algorithm {
			continue
		}
		all[offered.Version] = offered
		if offered.lastSeen >= cutoff {
			live[offered.Version] = offered
		}
	}
	if len(all) == 0 {
		return AlgorithmView{}, fmt.Errorf("no worker offers the algorithm %s", algorithm)
	}
	if requested != "" {
		if offered, ok := all[requested]; ok {
			return offered, nil
		}
		return AlgorithmView{}, fmt.Errorf("no worker offers %s %s", algorithm, requested)
	}
	candidates := live
	if len(candidates) == 0 {
		candidates = all
	}
	versions := make([]string, 0, len(candidates))
	for version := range candidates {
		versions = append(versions, version)
	}
	sort.Slice(versions, func(i, j int) bool { return compareVersions(versions[i], versions[j]) < 0 })
	return candidates[versions[len(versions)-1]], nil
}
