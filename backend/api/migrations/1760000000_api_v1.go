package migrations

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"

	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"
)

// API v1 renames sessions to recordings, makes the client's recording id the record
// id, folds jobs into analyses and stores each result as one object. Everything is
// closed to PocketBase's own record API (rules are null, so only superusers pass);
// the API routes are the only way in.
//
// Existing recordings and analyses are carried over where that is cheap: the rows
// are read before the old collections are dropped, the recording directories are
// renamed to the new ids and the result columns are folded into one object. Rows
// that cannot be carried over (a session without its meta.json) are dropped.

const (
	recordingsCollectionID = "pbc_2105117710"
	analysesV1CollectionID = "pbc_3419055622"
)

func init() {
	m.Register(upAPIV1, downAPIV1)
}

type oldRecording struct {
	oldID    string
	clientID string
	owner    string
	complete bool
	meta     json.RawMessage
	files    map[string]map[string]any
	created  string
}

type oldAnalysis struct {
	recording string // client id
	model     string
	version   string
	params    any
	hash      string
	status    string
	errText   string
	result    map[string]any
	created   string
	updated   string
}

func upAPIV1(app core.App) error {
	users, err := app.FindCollectionByNameOrId("_pb_users_auth_")
	if err != nil {
		return err
	}

	recordings, analyses := readOldData(app)

	for _, name := range []string{"jobs", "analyses", "sessions"} {
		if collection, err := app.FindCollectionByNameOrId(name); err == nil {
			if err := app.Delete(collection); err != nil {
				return err
			}
		}
	}

	rec := core.NewBaseCollection("recordings", recordingsCollectionID)
	// The client chooses the id, so it can be created idempotently and the phone's
	// id for a recording is also the backend's.
	if id, ok := rec.Fields.GetByName("id").(*core.TextField); ok {
		id.Min = 1
		id.Max = 40
		id.Pattern = `^[A-Za-z0-9_-]+$`
		id.AutogeneratePattern = ""
	}
	rec.Fields.Add(
		&core.RelationField{
			Name:          "owner",
			Required:      true,
			CollectionId:  users.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		&core.SelectField{
			Name:      "state",
			Required:  true,
			MaxSelect: 1,
			Values:    []string{"recording", "stopped", "forced_stop", "interrupted"},
		},
		&core.TextField{Name: "device", Max: 120},
		&core.DateField{Name: "started_at", Required: true},
		&core.DateField{Name: "ended_at"},
		&core.NumberField{Name: "duration_s"},
		&core.BoolField{Name: "complete"},
		&core.JSONField{Name: "meta", MaxSize: 65536},
		// Per stored file: encoding, uncompressed size and gzip member count.
		&core.JSONField{Name: "files", MaxSize: 65536},
		&core.AutodateField{Name: "created", OnCreate: true},
		&core.AutodateField{Name: "updated", OnCreate: true, OnUpdate: true},
	)
	rec.AddIndex("idx_recordings_owner_started", false, "owner, started_at", "")
	if err := app.Save(rec); err != nil {
		return err
	}

	an := core.NewBaseCollection("analyses", analysesV1CollectionID)
	an.Fields.Add(
		&core.RelationField{
			Name:          "recording",
			Required:      true,
			CollectionId:  rec.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		&core.TextField{Name: "algorithm", Required: true, Max: 100},
		&core.TextField{Name: "version", Required: true, Max: 100},
		&core.JSONField{Name: "params", MaxSize: 16384},
		&core.TextField{Name: "params_hash", Required: true, Max: 32},
		&core.SelectField{
			Name:      "status",
			Required:  true,
			MaxSelect: 1,
			Values:    []string{"queued", "running", "succeeded", "failed"},
		},
		&core.TextField{Name: "error", Max: 4000},
		&core.NumberField{Name: "attempts", Min: types.Pointer(0.0)},
		&core.TextField{Name: "worker", Max: 120},
		&core.DateField{Name: "available_at"},
		&core.DateField{Name: "lease_until"},
		&core.DateField{Name: "finished_at"},
		&core.JSONField{Name: "summary", MaxSize: 262144},
		&core.JSONField{Name: "result", MaxSize: 4194304},
		&core.AutodateField{Name: "created", OnCreate: true},
		&core.AutodateField{Name: "updated", OnCreate: true, OnUpdate: true},
	)
	an.AddIndex("idx_analyses_key", true, "recording, algorithm, version, params_hash", "")
	an.AddIndex("idx_analyses_claim", false, "status, available_at", "")
	an.AddIndex("idx_analyses_lease", false, "status, lease_until", "")
	if err := app.Save(an); err != nil {
		return err
	}

	// Users and workers log in through the API, so PocketBase's own record and
	// password-login endpoints are closed for them too (the operator manages
	// accounts as a superuser).
	users.ListRule, users.ViewRule, users.UpdateRule, users.DeleteRule = nil, nil, nil, nil
	users.PasswordAuth.Enabled = false
	if err := app.Save(users); err != nil {
		return err
	}
	if workers, err := app.FindCollectionByNameOrId("workers"); err == nil {
		workers.PasswordAuth.Enabled = false
		if err := app.Save(workers); err != nil {
			return err
		}
	}

	if err := restoreRecordings(app, rec, an, recordings, analyses); err != nil {
		return err
	}
	return nil
}

func downAPIV1(app core.App) error {
	for _, name := range []string{"analyses", "recordings"} {
		if collection, err := app.FindCollectionByNameOrId(name); err == nil {
			if err := app.Delete(collection); err != nil {
				return err
			}
		}
	}
	return nil
}

func readOldData(app core.App) ([]oldRecording, []oldAnalysis) {
	sessions, err := app.FindAllRecords("sessions")
	if err != nil {
		return nil, nil
	}
	byOld := map[string]string{}
	var recordings []oldRecording
	for _, s := range sessions {
		clientID := s.GetString("client_session_id")
		raw, err := os.ReadFile(filepath.Join(app.DataDir(), "recordings", s.Id, "meta.json"))
		if clientID == "" || err != nil {
			continue
		}
		var upload struct {
			Files map[string]map[string]any `json:"files"`
		}
		_ = s.UnmarshalJSONField("upload", &upload)
		recordings = append(recordings, oldRecording{
			oldID:    s.Id,
			clientID: clientID,
			owner:    s.GetString("owner"),
			complete: s.GetString("upload_status") == "uploaded",
			meta:     raw,
			files:    upload.Files,
			created:  s.GetString("created"),
		})
		byOld[s.Id] = clientID
	}

	var analyses []oldAnalysis
	rows, err := app.FindAllRecords("analyses")
	if err != nil {
		return recordings, nil
	}
	for _, r := range rows {
		recordingID, ok := byOld[r.GetString("session")]
		if !ok {
			continue
		}
		a := oldAnalysis{
			recording: recordingID,
			model:     r.GetString("model"),
			version:   r.GetString("model_version"),
			hash:      r.GetString("params_hash"),
			status:    r.GetString("status"),
			errText:   r.GetString("error"),
			created:   r.GetString("created"),
			updated:   r.GetString("updated"),
		}
		_ = r.UnmarshalJSONField("params", &a.params)
		if a.status == "done" {
			a.status = "succeeded"
			a.result = map[string]any{
				"contract_version": r.GetInt("contract_version"),
				"model":            a.model,
				"model_version":    a.version,
				"params":           a.params,
				"params_hash":      a.hash,
			}
			for _, name := range []string{
				"stages", "hr", "metrics", "metric_support", "quality", "sleep_quality",
				"capabilities", "provenance", "extras", "warnings",
			} {
				var value any
				if err := r.UnmarshalJSONField(name, &value); err == nil && value != nil {
					a.result[name] = value
				}
			}
		} else if a.status == "running" {
			a.status = "queued"
		}
		analyses = append(analyses, a)
	}
	return recordings, analyses
}

func restoreRecordings(app core.App, rec, an *core.Collection, recordings []oldRecording, analyses []oldAnalysis) error {
	base := filepath.Join(app.DataDir(), "recordings")
	known := map[string]bool{}
	for _, old := range recordings {
		var meta struct {
			State    string `json:"state"`
			StartUTC int64  `json:"start_utc_ms"`
			EndUTC   *int64 `json:"end_utc_ms"`
			Sensor   struct {
				Name string `json:"name"`
				Kind string `json:"kind"`
			} `json:"sensor"`
		}
		if json.Unmarshal(old.meta, &meta) != nil || meta.StartUTC <= 0 {
			continue
		}
		if _, err := app.FindRecordById("recordings", old.clientID); err == nil {
			continue
		}

		files := map[string]any{}
		for name, entry := range old.files {
			if name == "meta.json" {
				continue
			}
			item := map[string]any{"encoding": "identity", "uncompressed_size": entry["uncompressed_length"]}
			if encoding, _ := entry["encoding"].(string); encoding == "gzip" {
				item["encoding"] = "gzip"
				item["members"] = entry["members"]
			}
			files[name] = item
		}

		r := core.NewRecord(rec)
		r.Id = old.clientID
		r.Set("owner", old.owner)
		r.Set("state", meta.State)
		device := meta.Sensor.Name
		if device == "" {
			device = meta.Sensor.Kind
		}
		r.Set("device", device)
		r.Set("started_at", pbDate(meta.StartUTC))
		duration := 0.0
		if meta.EndUTC != nil {
			r.Set("ended_at", pbDate(*meta.EndUTC))
			if d := float64(*meta.EndUTC-meta.StartUTC) / 1000; d > 0 {
				duration = d
			}
		}
		r.Set("duration_s", duration)
		r.Set("complete", old.complete)
		r.Set("meta", types.JSONRaw(old.meta))
		r.Set("files", files)
		if err := app.Save(r); err != nil {
			return err
		}
		known[old.clientID] = true

		// The directory is named after the record id; the meta now lives in the row.
		from := filepath.Join(base, old.oldID)
		to := filepath.Join(base, old.clientID)
		if from != to {
			if _, err := os.Stat(to); os.IsNotExist(err) {
				_ = os.Rename(from, to)
			}
		}
		_ = os.Remove(filepath.Join(to, "meta.json"))
	}

	for _, old := range analyses {
		if !known[old.recording] {
			continue
		}
		r := core.NewRecord(an)
		r.Set("recording", old.recording)
		r.Set("algorithm", old.model)
		r.Set("version", old.version)
		r.Set("params", old.params)
		r.Set("params_hash", old.hash)
		r.Set("status", old.status)
		r.Set("error", old.errText)
		r.Set("attempts", 1)
		r.Set("available_at", old.created)
		if old.status == "succeeded" || old.status == "failed" {
			r.Set("finished_at", old.updated)
		}
		if old.result != nil {
			r.Set("result", old.result)
			r.Set("summary", summaryOf(old.result))
		}
		if err := app.Save(r); err != nil {
			return err
		}
	}
	return nil
}

// summaryOf is the small headline kept next to a result so lists need not carry it.
// It mirrors what the API derives for a new result.
func summaryOf(result map[string]any) map[string]any {
	summary := map[string]any{}
	for _, name := range []string{"metrics", "quality", "sleep_quality"} {
		if value, ok := result[name]; ok {
			summary[name] = value
		}
	}
	if stages, ok := result["stages"].(map[string]any); ok {
		summary["stages"] = map[string]any{
			"epoch_seconds": stages["epoch_seconds"],
			"start_unix_ms": stages["start_unix_ms"],
			"count":         stages["count"],
			"segments":      stages["segments"],
		}
	}
	return summary
}

// pbDate formats milliseconds since the epoch the way PocketBase stores dates.
func pbDate(ms int64) string {
	return time.UnixMilli(ms).UTC().Format("2006-01-02 15:04:05.000Z")
}
