package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
)

type StageSegment struct {
	Stage string `json:"stage"`
	Count int    `json:"count"`
}

type StageTimeline struct {
	EpochSeconds  int             `json:"epoch_seconds"`
	StartUnixMs   int64           `json:"start_unix_ms"`
	Count         int             `json:"count"`
	Segments      []StageSegment  `json:"segments"`
	Confidence    json.RawMessage `json:"confidence"`
	Artifacts     json.RawMessage `json:"artifacts"`
	Probabilities json.RawMessage `json:"probabilities"`
}

type AnalysisResultWire struct {
	ContractVersion int            `json:"contract_version"`
	Model           string         `json:"model"`
	ModelVersion    string         `json:"model_version"`
	Params          map[string]any `json:"params"`
	ParamsHash      string         `json:"params_hash"`
	Stages          StageTimeline  `json:"stages"`
	Metrics         map[string]any `json:"metrics"`
	MetricSupport   map[string]any `json:"metric_support"`
	Quality         map[string]any `json:"quality"`
	Capabilities    map[string]any `json:"capabilities"`
	Provenance      map[string]any `json:"provenance"`
	Extras          map[string]any `json:"extras"`
	Warnings        []string       `json:"warnings"`
}

type AnalysisModel struct {
	Model   string          `json:"model"`
	Version string          `json:"version"`
	Params  json.RawMessage `json:"params"`
}

type AnalysisRequest struct {
	Models  []AnalysisModel `json:"models"`
	Model   string          `json:"model"`
	Version string          `json:"version"`
	Params  json.RawMessage `json:"params"`
}

type AnalysisView struct {
	ID              string         `json:"id"`
	Session         string         `json:"session"`
	Model           string         `json:"model"`
	ModelVersion    string         `json:"model_version"`
	Params          map[string]any `json:"params"`
	ParamsHash      string         `json:"params_hash"`
	Status          string         `json:"status"`
	ContractVersion int            `json:"contract_version"`
	Stages          any            `json:"stages"`
	Metrics         any            `json:"metrics"`
	MetricSupport   any            `json:"metric_support"`
	Quality         any            `json:"quality"`
	Capabilities    any            `json:"capabilities"`
	Provenance      any            `json:"provenance"`
	Extras          any            `json:"extras"`
	Warnings        []string       `json:"warnings"`
	Error           string         `json:"error"`
	Created         string         `json:"created"`
	Updated         string         `json:"updated"`
}

type AnalysisSummaryView struct {
	ID           string        `json:"id"`
	Session      string        `json:"session"`
	Model        string        `json:"model"`
	ModelVersion string        `json:"model_version"`
	ParamsHash   string        `json:"params_hash"`
	Status       string        `json:"status"`
	Error        string        `json:"error"`
	Metrics      any           `json:"metrics"`
	Quality      any           `json:"quality"`
	Stages       *StageSummary `json:"stages"`
	Created      string        `json:"created"`
	Updated      string        `json:"updated"`
}

type StageSummary struct {
	EpochSeconds int            `json:"epoch_seconds"`
	StartUnixMs  int64          `json:"start_unix_ms"`
	Count        int            `json:"count"`
	Segments     []StageSegment `json:"segments"`
}

type AnalysisCounts struct {
	Total   int `json:"total"`
	Queued  int `json:"queued"`
	Running int `json:"running"`
	Done    int `json:"done"`
	Failed  int `json:"failed"`
}

func readAny(record *core.Record, name string) any {
	var value any
	_ = record.UnmarshalJSONField(name, &value)
	return value
}

func readStages(record *core.Record) *StageTimeline {
	var stages StageTimeline
	if err := record.UnmarshalJSONField("stages", &stages); err != nil || stages.Count == 0 {
		return nil
	}
	return &stages
}

func serializeAnalysis(record *core.Record) AnalysisView {
	var params map[string]any
	readJSONField(record, "params", &params)
	if params == nil {
		params = map[string]any{}
	}
	var warnings []string
	readJSONField(record, "warnings", &warnings)
	return AnalysisView{
		ID:              record.Id,
		Session:         record.GetString("session"),
		Model:           record.GetString("model"),
		ModelVersion:    record.GetString("model_version"),
		Params:          params,
		ParamsHash:      record.GetString("params_hash"),
		Status:          record.GetString("status"),
		ContractVersion: record.GetInt("contract_version"),
		Stages:          readAny(record, "stages"),
		Metrics:         readAny(record, "metrics"),
		MetricSupport:   readAny(record, "metric_support"),
		Quality:         readAny(record, "quality"),
		Capabilities:    readAny(record, "capabilities"),
		Provenance:      readAny(record, "provenance"),
		Extras:          readAny(record, "extras"),
		Warnings:        warnings,
		Error:           record.GetString("error"),
		Created:         record.GetString("created"),
		Updated:         record.GetString("updated"),
	}
}

func serializeAnalysisSummary(record *core.Record) AnalysisSummaryView {
	var stages *StageSummary
	if timeline := readStages(record); timeline != nil {
		stages = &StageSummary{
			EpochSeconds: timeline.EpochSeconds,
			StartUnixMs:  timeline.StartUnixMs,
			Count:        timeline.Count,
			Segments:     timeline.Segments,
		}
	}
	return AnalysisSummaryView{
		ID:           record.Id,
		Session:      record.GetString("session"),
		Model:        record.GetString("model"),
		ModelVersion: record.GetString("model_version"),
		ParamsHash:   record.GetString("params_hash"),
		Status:       record.GetString("status"),
		Error:        record.GetString("error"),
		Metrics:      readAny(record, "metrics"),
		Quality:      readAny(record, "quality"),
		Stages:       stages,
		Created:      record.GetString("created"),
		Updated:      record.GetString("updated"),
	}
}

func analysisCounts(records []*core.Record) AnalysisCounts {
	result := AnalysisCounts{Total: len(records)}
	for _, record := range records {
		switch record.GetString("status") {
		case "queued":
			result.Queued++
		case "running":
			result.Running++
		case "done":
			result.Done++
		case "failed":
			result.Failed++
		}
	}
	return result
}

func analysesFor(app core.App, sessionID string) ([]*core.Record, error) {
	return app.FindRecordsByFilter("analyses", "session = {:session}", "created", 0, 0, dbx.Params{"session": sessionID})
}

func jobsFor(app core.App, sessionID string) ([]*core.Record, error) {
	return app.FindRecordsByFilter("jobs", "session = {:session}", "created", 0, 0, dbx.Params{"session": sessionID})
}

func findAnalysis(app core.App, sessionID, model, version, hash string) (*core.Record, error) {
	return app.FindFirstRecordByFilter(
		"analyses",
		"session = {:session} && model = {:model} && model_version = {:version} && params_hash = {:hash}",
		dbx.Params{"session": sessionID, "model": model, "version": version, "hash": hash},
	)
}

func findJob(app core.App, analysisID string) (*core.Record, error) {
	return app.FindFirstRecordByFilter("jobs", "analysis = {:analysis}", dbx.Params{"analysis": analysisID})
}

func createAnalysis(app core.App, sessionID, model, version string, params map[string]any, hash string) (*core.Record, error) {
	collection, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return nil, err
	}
	record := core.NewRecord(collection)
	record.Set("session", sessionID)
	record.Set("model", model)
	record.Set("model_version", version)
	record.Set("params", params)
	record.Set("params_hash", hash)
	record.Set("status", "queued")
	record.Set("error", "")
	if err := app.Save(record); err != nil {
		return nil, err
	}
	return record, nil
}

func createJob(app core.App, analysis *core.Record) (*core.Record, error) {
	collection, err := app.FindCollectionByNameOrId("jobs")
	if err != nil {
		return nil, err
	}
	job := core.NewRecord(collection)
	job.Set("session", analysis.GetString("session"))
	job.Set("analysis", analysis.Id)
	job.Set("kind", "analysis")
	job.Set("model", analysis.GetString("model"))
	job.Set("model_version", analysis.GetString("model_version"))
	job.Set("params_hash", analysis.GetString("params_hash"))
	job.Set("status", "queued")
	job.Set("attempts", 0)
	job.Set("available_at", nowString())
	job.Set("worker", "")
	job.Set("lease_until", "")
	job.Set("error", "")
	if err := app.Save(job); err != nil {
		return nil, err
	}
	return job, nil
}

func requestAnalyses(txApp core.App, session *core.Record, items []AnalysisModel) ([]*core.Record, []*core.Record, error) {
	if len(items) == 0 {
		return nil, nil, apis.NewBadRequestError("models must be a non-empty array", nil)
	}
	if len(items) > 8 {
		return nil, nil, apis.NewBadRequestError("at most 8 models can be requested at once", nil)
	}

	var analyses []*core.Record
	var jobs []*core.Record
	seen := map[string]bool{}

	for _, item := range items {
		model := item.Model
		if !modelIDRE.MatchString(model) {
			return nil, nil, apis.NewBadRequestError("model id is invalid", nil)
		}
		version := item.Version
		if version == "" {
			return nil, nil, apis.NewBadRequestError("version is required; read it from GET /api/taiga/algorithms", nil)
		}
		if len(version) > 100 {
			return nil, nil, apis.NewBadRequestError("model version is too long", nil)
		}

		params := map[string]any{}
		if len(item.Params) > 0 {
			decoded, err := decodeObject(item.Params)
			if err != nil {
				return nil, nil, apis.NewBadRequestError("params must be a JSON object", nil)
			}
			params = decoded
		}
		rawParams, err := json.Marshal(params)
		if err != nil {
			return nil, nil, apis.NewBadRequestError("params are invalid", nil)
		}
		if len(rawParams) > 16384 {
			return nil, nil, apis.NewBadRequestError("params are too large", nil)
		}
		hash, err := paramsHash(params)
		if err != nil {
			return nil, nil, apis.NewBadRequestError("params are invalid", nil)
		}
		key := model + "\x00" + version + "\x00" + hash
		if seen[key] {
			continue
		}
		seen[key] = true

		analysis, err := findAnalysis(txApp, session.Id, model, version, hash)
		if err != nil {
			if !errors.Is(err, sql.ErrNoRows) {
				return nil, nil, err
			}
			analysis, err = createAnalysis(txApp, session.Id, model, version, params, hash)
			if err != nil {
				return nil, nil, err
			}
		}
		job, err := findJob(txApp, analysis.Id)
		if err != nil {
			if !errors.Is(err, sql.ErrNoRows) {
				return nil, nil, err
			}
			job, err = createJob(txApp, analysis)
			if err != nil {
				return nil, nil, err
			}
		}
		analyses = append(analyses, analysis)
		jobs = append(jobs, job)
	}
	return analyses, jobs, nil
}

func validateAnalysisResult(result *AnalysisResultWire) error {
	if result.ContractVersion == 0 {
		return fmt.Errorf("analysis result is missing contract_version")
	}
	if result.Model == "" || result.ModelVersion == "" {
		return fmt.Errorf("analysis result is missing model identity")
	}
	if result.Stages.Count < 1 || len(result.Stages.Segments) == 0 {
		return fmt.Errorf("analysis result has no stage timeline")
	}
	covered := 0
	for _, segment := range result.Stages.Segments {
		if segment.Stage == "" || segment.Count < 1 {
			return fmt.Errorf("analysis result has an invalid stage segment")
		}
		covered += segment.Count
	}
	if covered != result.Stages.Count {
		return fmt.Errorf("stage segments do not cover every epoch")
	}
	if result.Metrics == nil || result.Quality == nil || result.Capabilities == nil || result.Provenance == nil {
		return fmt.Errorf("analysis result is missing metrics, quality, capabilities or provenance")
	}
	return nil
}

func completeAnalysis(txApp core.App, job *core.Record, result *AnalysisResultWire) error {
	analysis, err := txApp.FindRecordById("analyses", job.GetString("analysis"))
	if err != nil {
		return failJob(txApp, job, "analysis row is missing", false)
	}
	if err := validateAnalysisResult(result); err != nil {
		return failJob(txApp, job, err.Error(), false)
	}
	if result.Model != analysis.GetString("model") {
		return failJob(txApp, job, "worker reported a different model", false)
	}
	analysisVersion := analysis.GetString("model_version")
	if analysisVersion == "latest" || analysisVersion == "unversioned" {
		analysis.Set("model_version", result.ModelVersion)
	} else if result.ModelVersion != analysisVersion {
		return failJob(txApp, job, "worker reported a different model version", false)
	}
	if result.ParamsHash != "" && result.ParamsHash != analysis.GetString("params_hash") {
		return failJob(txApp, job, "worker reported a different params hash", false)
	}

	analysis.Set("status", "done")
	analysis.Set("error", "")
	analysis.Set("contract_version", result.ContractVersion)
	analysis.Set("stages", result.Stages)
	analysis.Set("metrics", result.Metrics)
	analysis.Set("metric_support", result.MetricSupport)
	analysis.Set("quality", result.Quality)
	analysis.Set("capabilities", result.Capabilities)
	analysis.Set("provenance", result.Provenance)
	analysis.Set("extras", result.Extras)
	analysis.Set("warnings", result.Warnings)
	if err := txApp.Save(analysis); err != nil {
		return err
	}

	job.Set("status", "done")
	job.Set("lease_until", "")
	job.Set("error", "")
	summary := map[string]any{"analysis": analysis.Id}
	if runtime, ok := result.Provenance["runtime_s"]; ok {
		summary["runtime_s"] = runtime
	}
	if peak, ok := result.Provenance["peak_rss_mb"]; ok {
		summary["peak_rss_mb"] = peak
	}
	job.Set("result", summary)
	return txApp.Save(job)
}

func failJob(txApp core.App, job *core.Record, message string, retryable bool) error {
	message = truncate(message, 4000)
	attempts := job.GetInt("attempts")
	maxAttempts := int(envInt("TAIGA_JOB_MAX_ATTEMPTS", 3))
	if maxAttempts < 1 {
		maxAttempts = 1
	}
	shouldRetry := retryable && attempts < maxAttempts
	delay := 30
	if attempts > 1 {
		delay = 30 * (1 << minInt(attempts-1, 4))
	}
	if delay > 300 {
		delay = 300
	}

	var analysis *core.Record
	analysis, _ = txApp.FindRecordById("analyses", job.GetString("analysis"))

	if shouldRetry {
		job.Set("status", "queued")
		job.Set("available_at", afterSeconds(delay))
		job.Set("worker", "")
		job.Set("lease_until", "")
		job.Set("error", message)
		if analysis != nil {
			analysis.Set("status", "queued")
			analysis.Set("error", message)
			if err := txApp.Save(analysis); err != nil {
				return err
			}
		}
	} else {
		job.Set("status", "failed")
		job.Set("lease_until", "")
		job.Set("error", message)
		if analysis != nil {
			analysis.Set("status", "failed")
			analysis.Set("error", message)
			if err := txApp.Save(analysis); err != nil {
				return err
			}
		}
	}
	return txApp.Save(job)
}

func retryJob(txApp core.App, job *core.Record) error {
	if job.GetString("status") != "failed" {
		return apis.NewApiError(http.StatusConflict, "only failed jobs can be retried", nil)
	}
	job.Set("status", "queued")
	job.Set("available_at", nowString())
	job.Set("worker", "")
	job.Set("lease_until", "")
	job.Set("error", "")
	if err := txApp.Save(job); err != nil {
		return err
	}
	if analysis, err := txApp.FindRecordById("analyses", job.GetString("analysis")); err == nil {
		analysis.Set("status", "queued")
		analysis.Set("error", "")
		if err := txApp.Save(analysis); err != nil {
			return err
		}
	}
	return nil
}

func markAnalysisRunning(txApp core.App, job *core.Record) {
	analysisID := job.GetString("analysis")
	if analysisID == "" {
		return
	}
	if analysis, err := txApp.FindRecordById("analyses", analysisID); err == nil {
		analysis.Set("status", "running")
		analysis.Set("error", "")
		_ = txApp.Save(analysis)
	}
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
