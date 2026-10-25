package api

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
)

type JobView struct {
	ID           string         `json:"id"`
	Session      string         `json:"session"`
	Analysis     string         `json:"analysis"`
	Kind         string         `json:"kind"`
	Model        string         `json:"model"`
	ModelVersion string         `json:"model_version"`
	ParamsHash   string         `json:"params_hash"`
	Params       map[string]any `json:"params"`
	Status       string         `json:"status"`
	Attempts     int            `json:"attempts"`
	AvailableAt  string         `json:"available_at"`
	LeaseUntil   string         `json:"lease_until"`
	Worker       string         `json:"worker"`
	Error        string         `json:"error"`
	Result       any            `json:"result"`
	Created      string         `json:"created"`
	Updated      string         `json:"updated"`
}

type ClaimRequest struct {
	Worker       string   `json:"worker"`
	LeaseSeconds int      `json:"lease_seconds"`
	Algorithms   []string `json:"algorithms"`
}

type JobResultPayload struct {
	Analysis *AnalysisResultWire `json:"analysis"`
}

type JobResultRequest struct {
	Worker    string            `json:"worker"`
	Status    string            `json:"status"`
	Result    *JobResultPayload `json:"result"`
	Error     string            `json:"error"`
	Retryable *bool             `json:"retryable"`
}

func serializeJob(record *core.Record) JobView {
	return JobView{
		ID:           record.Id,
		Session:      record.GetString("session"),
		Analysis:     record.GetString("analysis"),
		Kind:         record.GetString("kind"),
		Model:        record.GetString("model"),
		ModelVersion: record.GetString("model_version"),
		ParamsHash:   record.GetString("params_hash"),
		Status:       record.GetString("status"),
		Attempts:     record.GetInt("attempts"),
		AvailableAt:  record.GetString("available_at"),
		LeaseUntil:   record.GetString("lease_until"),
		Worker:       record.GetString("worker"),
		Error:        record.GetString("error"),
		Result:       readAny(record, "result"),
		Created:      record.GetString("created"),
		Updated:      record.GetString("updated"),
	}
}

func claimJob(txApp core.App, worker string, leaseSeconds int, algorithms []string) (*JobView, error) {
	now := nowString()
	params := dbx.Params{"now": now}
	filter := "status = 'queued' AND available_at <= {:now}"
	var clean []string
	for _, id := range algorithms {
		if modelIDRE.MatchString(id) {
			clean = append(clean, id)
		}
	}
	if len(clean) > 0 {
		placeholders := make([]string, 0, len(clean))
		for index, id := range clean {
			key := fmt.Sprintf("m%d", index)
			params[key] = id
			placeholders = append(placeholders, "{:"+key+"}")
		}
		filter += " AND model IN (" + join(placeholders, ",") + ")"
	}

	rows := []struct {
		Id string `db:"id"`
	}{}
	err := txApp.DB().NewQuery(
		"SELECT id FROM jobs WHERE " + filter + " ORDER BY available_at ASC, created ASC LIMIT 1",
	).Bind(params).All(&rows)
	if err != nil || len(rows) == 0 {
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		return nil, nil
	}

	result, err := txApp.DB().NewQuery(
		"UPDATE jobs SET status = 'running', attempts = attempts + 1, " +
			"lease_until = {:lease}, worker = {:worker}, error = '', updated = {:now} " +
			"WHERE id = {:id} AND status = 'queued'",
	).Bind(dbx.Params{
		"lease":  afterSeconds(leaseSeconds),
		"worker": worker,
		"id":     rows[0].Id,
		"now":    now,
	}).Execute()
	if err != nil {
		return nil, err
	}
	affected, err := result.RowsAffected()
	if err != nil || affected != 1 {
		return nil, nil
	}

	job, err := txApp.FindRecordById("jobs", rows[0].Id)
	if err != nil {
		return nil, err
	}
	view := serializeJob(job)
	if view.Analysis != "" {
		if analysis, err := txApp.FindRecordById("analyses", view.Analysis); err == nil {
			var jobParams map[string]any
			readJSONField(analysis, "params", &jobParams)
			view.Params = jobParams
			view.ModelVersion = analysis.GetString("model_version")
			markAnalysisRunning(txApp, job)
		}
	}
	return &view, nil
}

func ClaimJobs(e *core.RequestEvent) error {
	if _, err := workerNameFromAuth(e); err != nil {
		return err
	}
	var body ClaimRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("invalid request body", err)
	}
	worker := strings.TrimSpace(body.Worker)
	if worker == "" {
		return e.BadRequestError("worker is required", nil)
	}
	if len(worker) > 120 {
		return e.BadRequestError("worker is too long", nil)
	}
	leaseSeconds := body.LeaseSeconds
	if leaseSeconds < 1 {
		leaseSeconds = int(envInt("TAIGA_JOB_LEASE_S", 1800))
	}
	if leaseSeconds > 86400 {
		leaseSeconds = 86400
	}

	var claimed *JobView
	err := e.App.RunInTransaction(func(txApp core.App) error {
		var err error
		claimed, err = claimJob(txApp, worker, leaseSeconds, body.Algorithms)
		return err
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, map[string]any{"job": claimed})
}

func ResultJob(e *core.RequestEvent) error {
	if _, err := workerNameFromAuth(e); err != nil {
		return err
	}
	var body JobResultRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("invalid request body", err)
	}
	worker := strings.TrimSpace(body.Worker)
	if worker == "" {
		return e.BadRequestError("worker is required", nil)
	}
	if body.Status != "done" && body.Status != "failed" {
		return e.BadRequestError("status must be 'done' or 'failed'", nil)
	}

	var resultJob *JobView
	err := e.App.RunInTransaction(func(txApp core.App) error {
		job, err := txApp.FindRecordById("jobs", e.Request.PathValue("id"))
		if err != nil {
			return apis.NewNotFoundError("job not found", nil)
		}
		if job.GetString("worker") != worker {
			return apis.NewForbiddenError("job is leased by another worker", nil)
		}
		current := job.GetString("status")
		if current == "done" || current == "failed" {
			view := serializeJob(job)
			resultJob = &view
			return nil
		}
		if current != "running" {
			return e.BadRequestError("job is not running", nil)
		}

		if body.Status == "done" {
			if body.Result == nil || body.Result.Analysis == nil {
				if err := failJob(txApp, job, "worker returned no analysis result", false); err != nil {
					return err
				}
			} else if err := completeAnalysis(txApp, job, body.Result.Analysis); err != nil {
				return err
			}
		} else {
			retryable := body.Retryable != nil && *body.Retryable
			if err := failJob(txApp, job, body.Error, retryable); err != nil {
				return err
			}
		}
		view := serializeJob(job)
		resultJob = &view
		return nil
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, map[string]any{"job": resultJob})
}

func RequeueJobs(e *core.RequestEvent) error {
	if _, err := workerNameFromAuth(e); err != nil {
		return err
	}
	var requeued int64
	err := e.App.RunInTransaction(func(txApp core.App) error {
		var err error
		requeued, err = requeueExpired(txApp)
		return err
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, map[string]any{"requeued": requeued})
}

func requeueExpired(txApp core.App) (int64, error) {
	now := nowString()
	if _, err := txApp.DB().NewQuery(
		"UPDATE analyses SET status = 'queued', error = 'lease expired', updated = {:now} " +
			"WHERE status = 'running' AND id IN (" +
			"SELECT analysis FROM jobs WHERE status = 'running' " +
			"AND lease_until != '' AND lease_until < {:now})",
	).Bind(dbx.Params{"now": now}).Execute(); err != nil {
		return 0, err
	}
	result, err := txApp.DB().NewQuery(
		"UPDATE jobs SET status = 'queued', worker = '', lease_until = '', " +
			"available_at = {:now}, error = 'lease expired', updated = {:now} " +
			"WHERE status = 'running' AND lease_until != '' AND lease_until < {:now}",
	).Bind(dbx.Params{"now": now}).Execute()
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func workerNameFromAuth(e *core.RequestEvent) (string, error) {
	if e.Auth == nil || e.Auth.Collection().Name != "workers" {
		return "", e.ForbiddenError("worker account required", nil)
	}
	return e.Auth.Id, nil
}

func join(items []string, separator string) string {
	return strings.Join(items, separator)
}
