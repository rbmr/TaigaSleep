package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

// The analyses table is the queue: an analysis is queued, a worker claims it for a
// lease and marks it running, and the worker completes or fails it. A lease that runs
// out puts it back in the queue (or fails it once its attempts are used up).

type ClaimRequest struct {
	Algorithms   []ClaimAlgorithm `json:"algorithms,omitempty"`
	LeaseSeconds int              `json:"lease_seconds,omitempty"`
}

type ClaimAlgorithm struct {
	ID      string `json:"id"`
	Version string `json:"version,omitempty"`
}

type CompleteRequest struct {
	Result json.RawMessage `json:"result"`
}

type FailRequest struct {
	Error     string `json:"error"`
	Retryable bool   `json:"retryable,omitempty"`
}

func leaseSeconds(requested int) int {
	if requested < 1 {
		requested = int(envInt("TAIGA_JOB_LEASE_S", 1800))
	}
	if requested > 86400 {
		requested = 86400
	}
	return requested
}

func maxAttempts() int {
	attempts := int(envInt("TAIGA_JOB_MAX_ATTEMPTS", 3))
	if attempts < 1 {
		attempts = 1
	}
	return attempts
}

// ClaimAnalysis hands the oldest queued analysis the worker can run to it, or 204.
func ClaimAnalysis(e *core.RequestEvent) error {
	var body ClaimRequest
	if e.Request.ContentLength != 0 {
		if err := json.NewDecoder(io.LimitReader(e.Request.Body, 65536)).Decode(&body); err != nil && !errors.Is(err, io.EOF) {
			return badRequest("invalid_body", "the body must be a JSON object")
		}
	}
	for _, algorithm := range body.Algorithms {
		if !algorithmIDRE.MatchString(algorithm.ID) {
			return badRequest("invalid_algorithm", "algorithm id is invalid")
		}
	}
	var claimed *core.Record
	err := e.App.RunInTransaction(func(txApp core.App) error {
		if _, err := requeueExpired(txApp); err != nil {
			return err
		}
		var err error
		claimed, err = claim(txApp, e.Auth.Id, leaseSeconds(body.LeaseSeconds), body.Algorithms)
		return err
	})
	if err != nil {
		return err
	}
	if claimed == nil {
		return e.NoContent(http.StatusNoContent)
	}
	return e.JSON(http.StatusOK, serializeAnalysis(claimed, false))
}

func claim(txApp core.App, worker string, lease int, algorithms []ClaimAlgorithm) (*core.Record, error) {
	now := nowString()
	params := dbx.Params{"now": now}
	filter := "status = 'queued' AND available_at <= {:now}"
	if len(algorithms) > 0 {
		clauses := make([]string, 0, len(algorithms))
		for i, algorithm := range algorithms {
			idKey, versionKey := "a"+itoa(i), "v"+itoa(i)
			params[idKey] = algorithm.ID
			if algorithm.Version != "" {
				params[versionKey] = algorithm.Version
				clauses = append(clauses, "(algorithm = {:"+idKey+"} AND version = {:"+versionKey+"})")
			} else {
				clauses = append(clauses, "(algorithm = {:"+idKey+"})")
			}
		}
		filter += " AND (" + strings.Join(clauses, " OR ") + ")"
	}
	rows := []struct {
		Id string `db:"id"`
	}{}
	err := txApp.DB().NewQuery(
		"SELECT id FROM analyses WHERE " + filter + " ORDER BY available_at ASC, created ASC LIMIT 1",
	).Bind(params).All(&rows)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	if len(rows) == 0 {
		return nil, nil
	}
	result, err := txApp.DB().NewQuery(
		"UPDATE analyses SET status = 'running', attempts = attempts + 1, " +
			"lease_until = {:lease}, worker = {:worker}, error = '', updated = {:now} " +
			"WHERE id = {:id} AND status = 'queued'",
	).Bind(dbx.Params{"lease": afterSeconds(lease), "worker": worker, "id": rows[0].Id, "now": now}).Execute()
	if err != nil {
		return nil, err
	}
	if affected, err := result.RowsAffected(); err != nil || affected != 1 {
		return nil, err
	}
	return txApp.FindRecordById("analyses", rows[0].Id)
}

// leasedAnalysis finds the analysis a worker is reporting on and checks that it still
// holds the lease. Anything else is 409: the worker lost the work, e.g. because the
// lease expired or the user deleted the analysis (404).
func leasedAnalysis(txApp core.App, id, worker string) (*core.Record, error) {
	record, err := txApp.FindRecordById("analyses", id)
	if err != nil {
		return nil, notFound("analysis")
	}
	if record.GetString("status") != statusRunning || record.GetString("worker") != worker {
		return nil, conflict("not_leased", "this worker does not hold the analysis")
	}
	if lease := record.GetString("lease_until"); lease == "" || lease < nowString() {
		return nil, conflict("not_leased", "the lease on this analysis has expired")
	}
	return record, nil
}

func CompleteAnalysis(e *core.RequestEvent) error {
	var body CompleteRequest
	if err := json.NewDecoder(io.LimitReader(e.Request.Body, 8*1024*1024)).Decode(&body); err != nil || len(body.Result) == 0 {
		return badRequest("invalid_body", `the body must be {"result": {...}}`)
	}
	var invalid error
	var saved *core.Record
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := leasedAnalysis(txApp, e.Request.PathValue("id"), e.Auth.Id)
		if err != nil {
			return err
		}
		saved = record
		if problem := checkResult(record, body.Result); problem != nil {
			// A result that is wrong stays wrong, so retrying would only repeat it.
			invalid = problem
			return finish(txApp, record, statusFailed, problem.Error())
		}
		record.Set("result", body.Result)
		record.Set("summary", summaryOf(body.Result))
		return finish(txApp, record, statusSucceeded, "")
	})
	if err != nil {
		return err
	}
	if invalid != nil {
		return unprocessable("invalid_result", invalid.Error())
	}
	return e.JSON(http.StatusOK, serializeAnalysis(saved, false))
}

func checkResult(record *core.Record, raw json.RawMessage) error {
	result, err := parseResult(raw)
	if err != nil {
		return err
	}
	if result.Model != record.GetString("algorithm") {
		return errors.New("worker reported a different algorithm")
	}
	if result.ModelVersion != record.GetString("version") {
		return errors.New("worker reported a different algorithm version")
	}
	return nil
}

func finish(txApp core.App, record *core.Record, status, message string) error {
	record.Set("status", status)
	record.Set("error", truncate(message, 4000))
	record.Set("lease_until", "")
	record.Set("finished_at", nowString())
	return txApp.Save(record)
}

func FailAnalysis(e *core.RequestEvent) error {
	var body FailRequest
	if err := json.NewDecoder(io.LimitReader(e.Request.Body, 65536)).Decode(&body); err != nil {
		return badRequest("invalid_body", `the body must be {"error": "...", "retryable": false}`)
	}
	var saved *core.Record
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := leasedAnalysis(txApp, e.Request.PathValue("id"), e.Auth.Id)
		if err != nil {
			return err
		}
		saved = record
		attempts := record.GetInt("attempts")
		if body.Retryable && attempts < maxAttempts() {
			delay := 30
			if attempts > 1 {
				delay = 30 * (1 << minInt(attempts-1, 4))
			}
			if delay > 300 {
				delay = 300
			}
			requeue(record, truncate(body.Error, 4000), delay)
			return txApp.Save(record)
		}
		return finish(txApp, record, statusFailed, body.Error)
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, serializeAnalysis(saved, false))
}

func requeue(record *core.Record, message string, delaySeconds int) {
	record.Set("status", statusQueued)
	record.Set("error", message)
	record.Set("worker", "")
	record.Set("lease_until", "")
	record.Set("available_at", afterSeconds(delaySeconds))
}

// requeueExpired puts analyses whose lease ran out back in the queue, or fails them
// once they have used up their attempts. It runs before every claim and once a minute.
func requeueExpired(txApp core.App) (int64, error) {
	now := nowString()
	expired, err := txApp.FindRecordsByFilter(
		"analyses",
		"status = 'running' && lease_until != '' && lease_until < {:now}",
		"", 0, 0, dbx.Params{"now": now},
	)
	if err != nil {
		return 0, err
	}
	for _, record := range expired {
		if record.GetInt("attempts") >= maxAttempts() {
			if err := finish(txApp, record, statusFailed, "lease expired too many times"); err != nil {
				return 0, err
			}
			continue
		}
		requeue(record, "lease expired", 0)
		if err := txApp.Save(record); err != nil {
			return 0, err
		}
	}
	return int64(len(expired)), nil
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func itoa(value int) string { return strconv.Itoa(value) }
