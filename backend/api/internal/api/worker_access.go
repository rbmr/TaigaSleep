package api

import (
	"net/http"
	"os"
	"strings"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
)

func assertWorkerJob(e *core.RequestEvent, sessionID, jobID, worker string) (*core.Record, error) {
	if jobID == "" {
		return nil, e.BadRequestError("job is required", nil)
	}
	if worker == "" {
		return nil, e.BadRequestError("worker is required", nil)
	}
	job, err := e.App.FindRecordById("jobs", jobID)
	if err != nil {
		return nil, apis.NewNotFoundError("job not found", nil)
	}
	if job.GetString("worker") != worker {
		return nil, apis.NewForbiddenError("job is leased by another worker", nil)
	}
	if job.GetString("session") != sessionID {
		return nil, apis.NewForbiddenError("job belongs to another session", nil)
	}
	if job.GetString("status") != "running" {
		return nil, apis.NewForbiddenError("job is not running", nil)
	}
	lease := job.GetString("lease_until")
	if lease == "" || lease < nowString() {
		return nil, apis.NewForbiddenError("job lease has expired", nil)
	}
	return job, nil
}

func WorkerManifest(e *core.RequestEvent) error {
	if _, err := workerNameFromAuth(e); err != nil {
		return err
	}
	sessionID := e.Request.PathValue("id")
	jobID := strings.TrimSpace(e.Request.URL.Query().Get("job"))
	worker := strings.TrimSpace(e.Request.URL.Query().Get("worker"))
	if _, err := assertWorkerJob(e, sessionID, jobID, worker); err != nil {
		return err
	}
	session, err := e.App.FindRecordById("sessions", sessionID)
	if err != nil {
		return apis.NewNotFoundError("session not found", nil)
	}
	m, err := manifest(e.App, session)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, WorkerManifestResponse{
		Job:    jobID,
		Worker: worker,
		Upload: m,
	})
}

func WorkerFile(e *core.RequestEvent) error {
	if _, err := workerNameFromAuth(e); err != nil {
		return err
	}
	sessionID := e.Request.PathValue("id")
	name := e.Request.PathValue("file")
	if !contains(allFiles, name) {
		return e.BadRequestError("unknown recording file", nil)
	}
	jobID := strings.TrimSpace(e.Request.URL.Query().Get("job"))
	worker := strings.TrimSpace(e.Request.URL.Query().Get("worker"))
	if _, err := assertWorkerJob(e, sessionID, jobID, worker); err != nil {
		return err
	}
	dir, err := recordingDir(e.App, sessionID)
	if err != nil {
		return err
	}
	return e.FileFS(os.DirFS(dir), name)
}
