package api

import (
	"database/sql"
	"errors"
	"net/http"
	"os"
	"strings"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
)

func Register(app core.App) {
	app.OnServe().BindFunc(func(se *core.ServeEvent) error {
		se.Router.GET("/api/taiga/health", func(e *core.RequestEvent) error {
			return e.JSON(http.StatusOK, map[string]any{
				"status":  "ok",
				"service": "taiga-sleep",
				"time":    nowString(),
			})
		})

		se.Router.GET("/api/taiga/auth-check", func(e *core.RequestEvent) error {
			return e.JSON(http.StatusOK, map[string]any{
				"ok":         true,
				"id":         e.Auth.Id,
				"collection": e.Auth.Collection().Name,
			})
		}).Bind(apis.RequireAuth())

		se.Router.POST("/api/taiga/sessions", CreateSession).Bind(apis.RequireAuth())
		se.Router.GET("/api/taiga/sessions/{id}/upload", GetUpload).Bind(apis.RequireAuth())
		se.Router.PUT("/api/taiga/sessions/{id}/upload/meta", PutMeta).Bind(apis.RequireAuth())
		se.Router.PUT("/api/taiga/sessions/{id}/upload/file/{file}", PutFile).Bind(apis.RequireAuth())
		se.Router.POST("/api/taiga/sessions/{id}/upload/complete", CompleteUpload).Bind(apis.RequireAuth())
		se.Router.POST("/api/taiga/sessions/{id}/analysis", RequestAnalyses).Bind(apis.RequireAuth())
		se.Router.GET("/api/taiga/sessions", ListSessions).Bind(apis.RequireAuth())
		se.Router.GET("/api/taiga/sessions/{id}", GetSession).Bind(apis.RequireAuth())
		se.Router.GET("/api/taiga/analyses/{id}", GetAnalysis).Bind(apis.RequireAuth())
		se.Router.POST("/api/taiga/jobs/{id}/retry", RetryJob).Bind(apis.RequireAuth())
		se.Router.GET("/api/taiga/algorithms", ListAlgorithms).Bind(apis.RequireAuth())

		se.Router.POST("/api/taiga/worker/jobs/claim", ClaimJobs).Bind(apis.RequireAuth("workers"))
		se.Router.POST("/api/taiga/worker/jobs/{id}/result", ResultJob).Bind(apis.RequireAuth("workers"))
		se.Router.POST("/api/taiga/worker/jobs/requeue", RequeueJobs).Bind(apis.RequireAuth("workers"))
		se.Router.POST("/api/taiga/worker/capabilities", ReportCapabilities).Bind(apis.RequireAuth("workers"))
		se.Router.GET("/api/taiga/worker/sessions/{id}/manifest", WorkerManifest).Bind(apis.RequireAuth("workers"))
		se.Router.GET("/api/taiga/worker/sessions/{id}/files/{file}", WorkerFile).Bind(apis.RequireAuth("workers"))

		return se.Next()
	})

	// Direct job creation is a tooling/test path. The app uses the analysis
	// route, which creates the analysis and job together.
	app.OnRecordCreateRequest("jobs").BindFunc(func(e *core.RecordRequestEvent) error {
		sessionID := e.Record.GetString("session")
		model := strings.TrimSpace(e.Record.GetString("model"))
		if model == "" {
			return e.BadRequestError("model is required", nil)
		}
		version := strings.TrimSpace(e.Record.GetString("model_version"))
		if version == "" {
			version = "unversioned"
		}
		hash, err := paramsHash(map[string]any{})
		if err != nil {
			return err
		}
		analysis, err := findAnalysis(e.App, sessionID, model, version, hash)
		if err != nil {
			if !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			analysis, err = createAnalysis(e.App, sessionID, model, version, map[string]any{}, hash)
			if err != nil {
				return err
			}
		}
		existing, err := findJob(e.App, analysis.Id)
		if err == nil {
			return e.JSON(http.StatusOK, existing)
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		e.Record.Set("analysis", analysis.Id)
		e.Record.Set("model_version", version)
		e.Record.Set("params_hash", hash)
		e.Record.Set("status", "queued")
		e.Record.Set("attempts", 0)
		e.Record.Set("available_at", nowString())
		e.Record.Set("worker", "")
		e.Record.Set("lease_until", "")
		e.Record.Set("error", "")
		return e.Next()
	})

	app.OnRecordAfterDeleteSuccess("sessions").BindFunc(func(e *core.RecordEvent) error {
		removeRecordingDir(e.App, e.Record.Id)
		return e.Next()
	})

	app.OnBootstrap().BindFunc(func(e *core.BootstrapEvent) error {
		settings := e.App.Settings()
		changed := false
		if name := os.Getenv("TAIGA_APP_NAME"); name != "" && settings.Meta.AppName != name {
			settings.Meta.AppName = name
			changed = true
		}
		if url := os.Getenv("TAIGA_APP_URL"); url != "" && settings.Meta.AppURL != url {
			settings.Meta.AppURL = url
			changed = true
		}
		if changed {
			if err := e.App.Save(settings); err != nil {
				return err
			}
		}
		return e.Next()
	})

	app.Cron().MustAdd("taiga_requeue_expired_jobs", "* * * * *", func() {
		var requeued int64
		err := app.RunInTransaction(func(txApp core.App) error {
			var err error
			requeued, err = requeueExpired(txApp)
			return err
		})
		if err == nil && requeued > 0 {
			app.Logger().Info("taiga: requeued expired jobs", "count", requeued)
		}
	})
}
