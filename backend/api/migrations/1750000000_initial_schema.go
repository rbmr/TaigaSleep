// Package migrations contains the embedded schema for the Taiga Sleep API.
//
// The app has not been deployed yet, so the first migration creates the final
// schema directly. Future schema changes should add a new migration file.
package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"
)

const (
	sessionsCollectionID = "pbc_3660498186"
	analysesCollectionID = "pbc_1904815773"
	jobsCollectionID     = "pbc_2409499253"
	workersCollectionID  = "pbc_1639792033"
)

func init() {
	m.Register(up, down)
}

func up(app core.App) error {
	users, err := app.FindCollectionByNameOrId("_pb_users_auth_")
	if err != nil {
		return err
	}

	sessions := core.NewBaseCollection("sessions", sessionsCollectionID)
	sessions.ListRule = types.Pointer("owner = @request.auth.id")
	sessions.ViewRule = types.Pointer("owner = @request.auth.id")
	sessions.CreateRule = types.Pointer(`@request.auth.id != "" && owner = @request.auth.id`)
	sessions.UpdateRule = types.Pointer("owner = @request.auth.id")
	sessions.DeleteRule = types.Pointer("owner = @request.auth.id")
	sessions.Fields.Add(
		&core.RelationField{
			Name:          "owner",
			Required:      true,
			CollectionId:  users.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		&core.TextField{Name: "client_session_id", Max: 40},
		&core.TextField{Name: "device", Max: 120},
		&core.DateField{Name: "started_at", Required: true},
		&core.DateField{Name: "ended_at"},
		&core.NumberField{Name: "duration_s"},
		&core.SelectField{
			Name:      "state",
			Required:  true,
			MaxSelect: 1,
			Values:    []string{"recording", "stopped", "forced_stop", "interrupted"},
		},
		&core.SelectField{
			Name:      "upload_status",
			MaxSelect: 1,
			Values:    []string{"none", "pending", "uploaded", "failed"},
		},
		&core.JSONField{Name: "upload", MaxSize: 65536},
		&core.DateField{Name: "uploaded_at"},
		&core.TextField{Name: "upload_error", Max: 2000},
		&core.AutodateField{Name: "created", OnCreate: true},
		&core.AutodateField{Name: "updated", OnCreate: true, OnUpdate: true},
	)
	sessions.AddIndex("idx_sessions_owner_started", false, "owner, started_at", "")
	sessions.AddIndex("idx_sessions_upload_status", false, "upload_status", "")
	sessions.AddIndex("idx_sessions_client_id", true, "owner, client_session_id", "client_session_id != ''")
	if err := app.Save(sessions); err != nil {
		return err
	}

	analyses := core.NewBaseCollection("analyses", analysesCollectionID)
	analyses.ListRule = types.Pointer("session.owner = @request.auth.id")
	analyses.ViewRule = types.Pointer("session.owner = @request.auth.id")
	analyses.CreateRule = nil
	analyses.UpdateRule = nil
	analyses.DeleteRule = nil
	analyses.Fields.Add(
		&core.RelationField{
			Name:          "session",
			Required:      true,
			CollectionId:  sessions.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		&core.TextField{Name: "model", Required: true, Max: 100},
		&core.TextField{Name: "model_version", Required: true, Max: 100},
		&core.JSONField{Name: "params", MaxSize: 16384},
		&core.TextField{Name: "params_hash", Required: true, Max: 32},
		&core.SelectField{
			Name:      "status",
			Required:  true,
			MaxSelect: 1,
			Values:    []string{"queued", "running", "done", "failed"},
		},
		&core.NumberField{Name: "contract_version"},
		&core.JSONField{Name: "stages", MaxSize: 1048576},
		&core.JSONField{Name: "metrics", MaxSize: 1048576},
		&core.JSONField{Name: "metric_support", MaxSize: 1048576},
		&core.JSONField{Name: "quality", MaxSize: 262144},
		&core.JSONField{Name: "capabilities", MaxSize: 65536},
		&core.JSONField{Name: "provenance", MaxSize: 262144},
		&core.JSONField{Name: "extras", MaxSize: 1048576},
		&core.JSONField{Name: "warnings", MaxSize: 65536},
		&core.TextField{Name: "error", Max: 4000},
		&core.AutodateField{Name: "created", OnCreate: true},
		&core.AutodateField{Name: "updated", OnCreate: true, OnUpdate: true},
	)
	analyses.AddIndex(
		"idx_analyses_key",
		true,
		"session, model, model_version, params_hash",
		"",
	)
	analyses.AddIndex("idx_analyses_session_status", false, "session, status", "")
	analyses.AddIndex("idx_analyses_status_updated", false, "status, updated", "")
	if err := app.Save(analyses); err != nil {
		return err
	}

	jobs := core.NewBaseCollection("jobs", jobsCollectionID)
	jobs.ListRule = types.Pointer("session.owner = @request.auth.id")
	jobs.ViewRule = types.Pointer("session.owner = @request.auth.id")
	jobs.CreateRule = types.Pointer(`@request.auth.id != "" && session.owner = @request.auth.id`)
	jobs.UpdateRule = nil
	jobs.DeleteRule = nil
	jobs.Fields.Add(
		&core.RelationField{
			Name:          "session",
			Required:      true,
			CollectionId:  sessions.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		&core.RelationField{
			Name:          "analysis",
			CollectionId:  analyses.Id,
			CascadeDelete: true,
			MaxSelect:     1,
		},
		&core.SelectField{
			Name:      "kind",
			Required:  true,
			MaxSelect: 1,
			Values:    []string{"analysis"},
		},
		&core.TextField{Name: "model", Required: true, Max: 100},
		&core.TextField{Name: "model_version", Max: 100},
		&core.TextField{Name: "params_hash", Max: 32},
		&core.SelectField{
			Name:      "status",
			Required:  true,
			MaxSelect: 1,
			Values:    []string{"queued", "running", "done", "failed"},
		},
		&core.NumberField{Name: "attempts", Min: types.Pointer(0.0)},
		&core.DateField{Name: "available_at", Required: true},
		&core.DateField{Name: "lease_until"},
		&core.TextField{Name: "worker", Max: 120},
		&core.TextField{Name: "error", Max: 4000},
		&core.JSONField{Name: "result", MaxSize: 1048576},
		&core.AutodateField{Name: "created", OnCreate: true},
		&core.AutodateField{Name: "updated", OnCreate: true, OnUpdate: true},
	)
	jobs.AddIndex("idx_jobs_analysis", true, "analysis", "analysis != ''")
	jobs.AddIndex("idx_jobs_claim", false, "status, available_at", "")
	jobs.AddIndex("idx_jobs_lease", false, "status, lease_until", "")
	if err := app.Save(jobs); err != nil {
		return err
	}

	workers := core.NewAuthCollection("workers", workersCollectionID)
	workers.ListRule = nil
	workers.ViewRule = nil
	workers.CreateRule = nil
	workers.UpdateRule = nil
	workers.DeleteRule = nil
	workers.ManageRule = nil
	workers.AuthAlert.Enabled = false
	workers.PasswordAuth.Enabled = true
	workers.PasswordAuth.IdentityFields = []string{"email"}
	workers.OTP.Enabled = false
	workers.MFA.Enabled = false
	workers.AuthToken.Duration = 2592000
	workers.Fields.Add(
		&core.TextField{Name: "name", Max: 100},
		&core.TextField{Name: "version", Max: 100},
		&core.JSONField{Name: "algorithms", MaxSize: 262144},
		&core.DateField{Name: "last_seen"},
	)
	if err := app.Save(workers); err != nil {
		return err
	}

	users.CreateRule = nil
	users.AuthAlert.Enabled = false
	users.AuthToken.Duration = 2592000
	if err := app.Save(users); err != nil {
		return err
	}

	settings := app.Settings()
	settings.Meta.AppName = "Taiga Sleep"
	settings.Logs.MaxDays = 7
	settings.Logs.LogAuthId = true
	return app.Save(settings)
}

func down(app core.App) error {
	for _, name := range []string{"jobs", "analyses", "sessions", "workers"} {
		collection, err := app.FindCollectionByNameOrId(name)
		if err == nil {
			if err := app.Delete(collection); err != nil {
				return err
			}
		}
	}
	users, err := app.FindCollectionByNameOrId("_pb_users_auth_")
	if err == nil {
		users.CreateRule = types.Pointer("")
		users.AuthAlert.Enabled = true
		users.AuthToken.Duration = 432000
		if err := app.Save(users); err != nil {
			return err
		}
	}
	return nil
}
