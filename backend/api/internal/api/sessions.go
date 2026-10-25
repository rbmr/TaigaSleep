package api

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

var sessionSorts = map[string]bool{
	"started_at": true, "-started_at": true,
	"created": true, "-created": true,
	"updated": true, "-updated": true,
	"duration_s": true, "-duration_s": true,
}

type SessionSummaryView struct {
	Session         SessionView           `json:"session"`
	Analyses        []AnalysisSummaryView `json:"analyses"`
	AnalysisSummary AnalysisCounts        `json:"analysis_summary"`
}

type SessionListView struct {
	Page    int                  `json:"page"`
	PerPage int                  `json:"perPage"`
	HasMore bool                 `json:"hasMore"`
	Items   []SessionSummaryView `json:"items"`
}

type SessionDetailView struct {
	Session         SessionView    `json:"session"`
	Upload          Manifest       `json:"upload"`
	Analyses        []AnalysisView `json:"analyses"`
	Jobs            []JobView      `json:"jobs"`
	AnalysisSummary AnalysisCounts `json:"analysis_summary"`
}

type AnalysisRequestResponse struct {
	Analyses []AnalysisView `json:"analyses"`
	Jobs     []JobView      `json:"jobs"`
}

func RequestAnalyses(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	var body AnalysisRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("invalid request body", err)
	}
	items := body.Models
	if len(items) == 0 && body.Model != "" {
		items = []AnalysisModel{{Model: body.Model, Version: body.Version, Params: body.Params}}
	}
	if len(items) == 0 {
		return e.BadRequestError("models must be a non-empty array", nil)
	}

	sessionID := e.Request.PathValue("id")
	var analyses []*core.Record
	var jobs []*core.Record
	err := e.App.RunInTransaction(func(txApp core.App) error {
		session, err := findSessionForUser(txApp, sessionID, e.Auth)
		if err != nil {
			return err
		}
		if session.GetString("upload_status") != "uploaded" {
			return e.Error(http.StatusConflict, "session is not uploaded", nil)
		}
		if session.GetString("state") == "recording" {
			return e.Error(http.StatusConflict, "recording is still in progress", nil)
		}
		analyses, jobs, err = requestAnalyses(txApp, session, items)
		return err
	})
	if err != nil {
		return err
	}

	response := AnalysisRequestResponse{
		Analyses: make([]AnalysisView, 0, len(analyses)),
		Jobs:     make([]JobView, 0, len(jobs)),
	}
	for _, analysis := range analyses {
		response.Analyses = append(response.Analyses, serializeAnalysis(analysis))
	}
	for _, job := range jobs {
		response.Jobs = append(response.Jobs, serializeJob(job))
	}
	return e.JSON(http.StatusOK, response)
}

func ListSessions(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	query := e.Request.URL.Query()
	page := positiveInt(query.Get("page"), 1, 1000000)
	perPage := positiveInt(query.Get("perPage"), 30, 100)
	offset := (page - 1) * perPage

	where := []string{"owner = {:owner}"}
	params := dbx.Params{"owner": e.Auth.Id}

	if state := strings.TrimSpace(query.Get("state")); state != "" {
		if !states[state] {
			return e.BadRequestError("invalid state filter", nil)
		}
		where = append(where, "state = {:state}")
		params["state"] = state
	}
	if uploadStatus := strings.TrimSpace(query.Get("upload_status")); uploadStatus != "" {
		switch uploadStatus {
		case "none", "pending", "uploaded", "failed":
			where = append(where, "upload_status = {:upload_status}")
			params["upload_status"] = uploadStatus
		default:
			return e.BadRequestError("invalid upload_status filter", nil)
		}
	}
	if from := strings.TrimSpace(query.Get("from")); from != "" {
		value, err := parseQueryDate(from)
		if err != nil {
			return e.BadRequestError("invalid from date", err)
		}
		where = append(where, "started_at >= {:from}")
		params["from"] = value
	}
	if to := strings.TrimSpace(query.Get("to")); to != "" {
		value, err := parseQueryDate(to)
		if err != nil {
			return e.BadRequestError("invalid to date", err)
		}
		where = append(where, "started_at <= {:to}")
		params["to"] = value
	}

	sort := strings.TrimSpace(query.Get("sort"))
	if sort == "" {
		sort = "-started_at"
	}
	if !sessionSorts[sort] {
		return e.BadRequestError("invalid sort", nil)
	}

	records, err := e.App.FindRecordsByFilter(
		"sessions",
		strings.Join(where, " && "),
		sort,
		perPage+1,
		offset,
		params,
	)
	if err != nil {
		return err
	}
	hasMore := len(records) > perPage
	if hasMore {
		records = records[:perPage]
	}

	items := make([]SessionSummaryView, 0, len(records))
	for _, record := range records {
		analyses, err := analysesFor(e.App, record.Id)
		if err != nil {
			return err
		}
		summaries := make([]AnalysisSummaryView, 0, len(analyses))
		for _, analysis := range analyses {
			summaries = append(summaries, serializeAnalysisSummary(analysis))
		}
		items = append(items, SessionSummaryView{
			Session:         serializeSession(record),
			Analyses:        summaries,
			AnalysisSummary: analysisCounts(analyses),
		})
	}

	return e.JSON(http.StatusOK, SessionListView{
		Page:    page,
		PerPage: perPage,
		HasMore: hasMore,
		Items:   items,
	})
}

func GetSession(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	record, err := findSessionForUser(e.App, e.Request.PathValue("id"), e.Auth)
	if err != nil {
		return err
	}
	upload, err := manifest(e.App, record)
	if err != nil {
		return err
	}
	analyses, err := analysesFor(e.App, record.Id)
	if err != nil {
		return err
	}
	jobs, err := jobsFor(e.App, record.Id)
	if err != nil {
		return err
	}
	analysisViews := make([]AnalysisView, 0, len(analyses))
	for _, analysis := range analyses {
		analysisViews = append(analysisViews, serializeAnalysis(analysis))
	}
	jobViews := make([]JobView, 0, len(jobs))
	for _, job := range jobs {
		jobViews = append(jobViews, serializeJob(job))
	}
	return e.JSON(http.StatusOK, SessionDetailView{
		Session:         serializeSession(record),
		Upload:          upload,
		Analyses:        analysisViews,
		Jobs:            jobViews,
		AnalysisSummary: analysisCounts(analyses),
	})
}

func GetAnalysis(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	analysis, err := findAnalysisForUser(e.App, e.Request.PathValue("id"), e.Auth)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, serializeAnalysis(analysis))
}

func RetryJob(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	var resultJob *core.Record
	err := e.App.RunInTransaction(func(txApp core.App) error {
		job, err := findJobForUser(txApp, e.Request.PathValue("id"), e.Auth)
		if err != nil {
			return err
		}
		if err := retryJob(txApp, job); err != nil {
			return err
		}
		resultJob = job
		return nil
	})
	if err != nil {
		return err
	}
	var analysisView *AnalysisView
	if analysis, err := e.App.FindRecordById("analyses", resultJob.GetString("analysis")); err == nil {
		view := serializeAnalysis(analysis)
		analysisView = &view
	}
	return e.JSON(http.StatusOK, RetryJobResponse{
		Job:      serializeJob(resultJob),
		Analysis: analysisView,
	})
}

func positiveInt(value string, fallback, maximum int) int {
	parsed, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || parsed < 1 {
		return fallback
	}
	if parsed > maximum {
		return maximum
	}
	return parsed
}

func parseQueryDate(value string) (string, error) {
	layouts := []string{
		time.RFC3339Nano,
		time.RFC3339,
		"2006-01-02 15:04:05.000Z",
		"2006-01-02",
	}
	for _, layout := range layouts {
		if parsed, err := time.Parse(layout, value); err == nil {
			return pbDateMillis(parsed.UnixMilli()), nil
		}
	}
	return "", &time.ParseError{Value: value, Layout: time.RFC3339}
}
