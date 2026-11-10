package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

// AnalysisView is an analysis. Result is left out of lists unless asked for, since
// it is the large part; Summary is the small derived headline.
type AnalysisView struct {
	ID         string         `json:"id"`
	Recording  string         `json:"recording"`
	Algorithm  string         `json:"algorithm"`
	Version    string         `json:"version"`
	Params     map[string]any `json:"params"`
	Status     string         `json:"status"`
	Error      string         `json:"error"`
	Attempts   int            `json:"attempts"`
	Created    string         `json:"created"`
	Updated    string         `json:"updated"`
	FinishedAt *string        `json:"finished_at"`
	Summary    any            `json:"summary"`
	Result     any            `json:"result,omitempty"`
}

type AnalysisList struct {
	Items  []AnalysisView `json:"items"`
	Limit  int            `json:"limit"`
	Offset int            `json:"offset"`
	Total  int64          `json:"total"`
}

// AnalysisRequest asks for one algorithm to be run on a recording. Version defaults
// to the newest one a worker offers.
type AnalysisRequest struct {
	Algorithm string          `json:"algorithm"`
	Version   string          `json:"version,omitempty"`
	Params    json.RawMessage `json:"params,omitempty"`
}

const (
	statusQueued    = "queued"
	statusRunning   = "running"
	statusSucceeded = "succeeded"
	statusFailed    = "failed"
)

var analysisStatuses = map[string]bool{statusQueued: true, statusRunning: true, statusSucceeded: true, statusFailed: true}

func readAny(record *core.Record, name string) any {
	var value any
	if err := record.UnmarshalJSONField(name, &value); err != nil {
		return nil
	}
	return value
}

func serializeAnalysis(record *core.Record, withResult bool) AnalysisView {
	params := map[string]any{}
	_ = record.UnmarshalJSONField("params", &params)
	if params == nil {
		params = map[string]any{}
	}
	view := AnalysisView{
		ID:         record.Id,
		Recording:  record.GetString("recording"),
		Algorithm:  record.GetString("algorithm"),
		Version:    record.GetString("version"),
		Params:     params,
		Status:     record.GetString("status"),
		Error:      record.GetString("error"),
		Attempts:   record.GetInt("attempts"),
		Created:    derefString(rfc3339(record.GetString("created"))),
		Updated:    derefString(rfc3339(record.GetString("updated"))),
		FinishedAt: rfc3339(record.GetString("finished_at")),
		Summary:    readAny(record, "summary"),
	}
	if withResult {
		view.Result = readAny(record, "result")
	}
	return view
}

func paramsHash(params map[string]any) (string, error) {
	raw, err := json.Marshal(params) // map keys are sorted, so equal params hash equal
	if err != nil {
		return "", err
	}
	return hashHex(raw), nil
}

// analysisFor finds an analysis the caller may read: a user's own (through the
// recording's owner) or, for a worker, one it holds the lease of. Anything else is
// 404 so ids do not leak.
func analysisFor(e *core.RequestEvent, id string) (*core.Record, error) {
	record, err := e.App.FindRecordById("analyses", id)
	if err != nil {
		return nil, notFound("analysis")
	}
	switch roleOf(e) {
	case roleUser:
		if _, err := ownRecording(e.App, e, record.GetString("recording")); err != nil {
			return nil, notFound("analysis")
		}
	case roleWorker:
		if !leasesAnalysis(e.Auth.Id, record) {
			return nil, notFound("analysis")
		}
	default:
		return nil, notFound("analysis")
	}
	return record, nil
}

func CreateAnalysis(e *core.RequestEvent) error {
	var body AnalysisRequest
	if err := json.NewDecoder(io.LimitReader(e.Request.Body, 32768)).Decode(&body); err != nil {
		return badRequest("invalid_body", "the body must be a JSON object with an algorithm")
	}
	if !algorithmIDRE.MatchString(body.Algorithm) {
		return badRequest("invalid_algorithm", "algorithm is not a valid algorithm id")
	}
	if len(body.Version) > 100 {
		return badRequest("invalid_version", "version is too long")
	}
	params := map[string]any{}
	if raw := strings.TrimSpace(string(body.Params)); raw != "" && raw != "null" {
		if len(raw) > 16384 {
			return badRequest("invalid_params", "params are too large")
		}
		if err := json.Unmarshal(body.Params, &params); err != nil || params == nil {
			return badRequest("invalid_params", "params must be a JSON object")
		}
	}
	hash, err := paramsHash(params)
	if err != nil {
		return badRequest("invalid_params", "params are invalid")
	}

	var result *core.Record
	created := false
	err = e.App.RunInTransaction(func(txApp core.App) error {
		recording, err := ownRecording(txApp, e, e.Request.PathValue("id"))
		if err != nil {
			return err
		}
		if !recording.GetBool("complete") {
			return conflict("recording_incomplete", "the recording has not been completed yet")
		}
		version, err := resolveVersion(txApp, body.Algorithm, body.Version)
		if err != nil {
			return err
		}
		existing, findErr := txApp.FindFirstRecordByFilter(
			"analyses",
			"recording = {:recording} && algorithm = {:algorithm} && version = {:version} && params_hash = {:hash}",
			dbx.Params{"recording": recording.Id, "algorithm": body.Algorithm, "version": version, "hash": hash},
		)
		switch {
		case findErr == nil && existing.GetString("status") != statusFailed:
			result = existing
			return nil
		case findErr == nil:
			// A failed analysis is replaced: asking again is how a retry is made.
			if err := txApp.Delete(existing); err != nil {
				return err
			}
		case !errors.Is(findErr, sql.ErrNoRows):
			return findErr
		}
		collection, err := txApp.FindCollectionByNameOrId("analyses")
		if err != nil {
			return err
		}
		record := core.NewRecord(collection)
		record.Set("recording", recording.Id)
		record.Set("algorithm", body.Algorithm)
		record.Set("version", version)
		record.Set("params", params)
		record.Set("params_hash", hash)
		record.Set("status", statusQueued)
		record.Set("error", "")
		record.Set("attempts", 0)
		record.Set("available_at", nowString())
		if err := txApp.Save(record); err != nil {
			return err
		}
		result = record
		created = true
		return nil
	})
	if err != nil {
		return err
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
		e.Response.Header().Set("Location", "/v1/analyses/"+result.Id)
	}
	return e.JSON(status, serializeAnalysis(result, true))
}

func listAnalyses(e *core.RequestEvent, recording string) error {
	query := e.Request.URL.Query()
	limit, offset, err := pageParams(query)
	if err != nil {
		return err
	}
	conditions := []dbx.Expression{}
	if recording == "" {
		recording = strings.TrimSpace(query.Get("recording"))
	}
	if recording != "" {
		conditions = append(conditions, dbx.HashExp{"recording": recording})
	}
	switch roleOf(e) {
	case roleUser:
		conditions = append(conditions, dbx.NewExp(
			"recording IN (SELECT id FROM recordings WHERE owner = {:owner})",
			dbx.Params{"owner": e.Auth.Id},
		))
	case roleWorker:
		// A worker only sees the analyses it holds the lease of right now. The queue
		// reaches it as an event and a claim, not as a list of everyone's work.
		conditions = append(conditions, dbx.NewExp(
			"worker = {:worker} AND status = 'running' AND lease_until > {:now}",
			dbx.Params{"worker": e.Auth.Id, "now": nowString()},
		))
	}
	if status := strings.TrimSpace(query.Get("status")); status != "" {
		if !analysisStatuses[status] {
			return badRequest("invalid_query", "status is not an analysis status")
		}
		conditions = append(conditions, dbx.HashExp{"status": status})
	}
	order := "created DESC"
	switch strings.TrimSpace(query.Get("sort")) {
	case "", "-created":
	case "created":
		order = "created ASC"
	default:
		return badRequest("invalid_query", "unknown sort")
	}
	withResult := false
	switch strings.TrimSpace(query.Get("expand")) {
	case "":
	case "result":
		withResult = true
	default:
		return badRequest("invalid_query", "expand can only be result")
	}

	total, err := e.App.CountRecords("analyses", conditions...)
	if err != nil {
		return err
	}
	records := []*core.Record{}
	q := e.App.RecordQuery("analyses").OrderBy(order, "id ASC").Limit(int64(limit)).Offset(int64(offset))
	for _, condition := range conditions {
		q = q.AndWhere(condition)
	}
	if err := q.All(&records); err != nil {
		return err
	}
	items := make([]AnalysisView, 0, len(records))
	for _, record := range records {
		items = append(items, serializeAnalysis(record, withResult))
	}
	return e.JSON(http.StatusOK, AnalysisList{Items: items, Limit: limit, Offset: offset, Total: total})
}

func ListAnalyses(e *core.RequestEvent) error { return listAnalyses(e, "") }

func ListRecordingAnalyses(e *core.RequestEvent) error {
	id := e.Request.PathValue("id")
	if _, err := ownRecording(e.App, e, id); err != nil {
		return err
	}
	return listAnalyses(e, id)
}

func GetAnalysis(e *core.RequestEvent) error {
	record, err := analysisFor(e, e.Request.PathValue("id"))
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, serializeAnalysis(record, true))
}

func DeleteAnalysis(e *core.RequestEvent) error {
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := txApp.FindRecordById("analyses", e.Request.PathValue("id"))
		if err != nil {
			return notFound("analysis")
		}
		if _, err := ownRecording(txApp, e, record.GetString("recording")); err != nil {
			return notFound("analysis")
		}
		return txApp.Delete(record)
	})
	if err != nil {
		return err
	}
	return e.NoContent(http.StatusNoContent)
}

// resolveVersion checks that some registered worker offers the algorithm and, when
// no version was asked for, picks the newest one on offer.
func resolveVersion(app core.App, algorithm, requested string) (string, error) {
	cutoff := pbDateMillis(time.Now().Add(-time.Duration(envInt("TAIGA_WORKER_TTL_S", 900)) * time.Second).UnixMilli())
	live, all := map[string]bool{}, map[string]bool{}
	for _, offered := range registeredAlgorithms(app) {
		if offered.ID != algorithm {
			continue
		}
		all[offered.Version] = true
		if offered.lastSeen >= cutoff {
			live[offered.Version] = true
		}
	}
	if len(all) == 0 {
		return "", unprocessable("unknown_algorithm", "no worker offers the algorithm "+algorithm)
	}
	if requested != "" {
		if !all[requested] {
			return "", unprocessable("unknown_algorithm", "no worker offers "+algorithm+" "+requested)
		}
		return requested, nil
	}
	// The default is the newest version a worker that is still announcing offers, so
	// a worker that went away does not make its newer version the default. When none
	// is announcing, the newest known one still beats refusing the request: the
	// analysis waits in the queue for a worker to come back.
	candidates := live
	if len(candidates) == 0 {
		candidates = all
	}
	versions := make([]string, 0, len(candidates))
	for version := range candidates {
		versions = append(versions, version)
	}
	sort.Slice(versions, func(i, j int) bool { return compareVersions(versions[i], versions[j]) < 0 })
	return versions[len(versions)-1], nil
}

// compareVersions orders dotted versions numerically where it can and falls back to
// string order for anything else.
func compareVersions(a, b string) int {
	left, right := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(left) || i < len(right); i++ {
		l, r := "0", "0" // a missing part counts as zero: 1.0 equals 1.0.0
		if i < len(left) {
			l = left[i]
		}
		if i < len(right) {
			r = right[i]
		}
		ln, lerr := strconv.Atoi(l)
		rn, rerr := strconv.Atoi(r)
		if lerr == nil && rerr == nil {
			if ln != rn {
				if ln < rn {
					return -1
				}
				return 1
			}
			continue
		}
		if c := strings.Compare(l, r); c != 0 {
			return c
		}
	}
	return 0
}
