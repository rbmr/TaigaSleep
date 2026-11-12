package migrations

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"

	"github.com/rbmr/TaigaSleep/backend/api/internal/api"
)

func TestRebuildAnalysisSummaries(t *testing.T) {
	app := newMigrationTestApp(t)
	recording := saveMigrationRecording(t, app, "owner@example.com")

	stages := map[string]any{
		"epoch_seconds": 30,
		"start_unix_ms": 1760000000000,
		"count":         3,
		"segments": []any{
			map[string]any{"stage": "light", "count": 2},
			map[string]any{"stage": "deep", "count": 1},
		},
	}
	oldSummary := map[string]any{
		"metrics":       map[string]any{"total_sleep_s": 3600},
		"quality":       map[string]any{"efficiency": 90},
		"sleep_quality": map[string]any{"score": 80},
		"stages":        stages,
	}

	withItems := saveMigrationAnalysis(t, app, recording, "with-items", map[string]any{
		"contract_version": 2,
		"model":            "with-items",
		"model_version":    "1.0.0",
		"stages":           stages,
		"metrics":          map[string]any{"total_sleep_s": 3600},
		"quality":          map[string]any{"efficiency": 90},
		"sleep_quality":    map[string]any{"score": 80},
		"items": []any{
			map[string]any{"kind": "number", "id": "total_duration_s", "value": 3600.0},
			map[string]any{"kind": "score", "id": "sleep_quality", "value": 78.0},
			map[string]any{"kind": "score", "id": "data_quality", "value": 91.0},
		},
		"capabilities": map[string]any{},
		"provenance":   map[string]any{},
	}, oldSummary)

	withoutItems := saveMigrationAnalysis(t, app, recording, "without-items", map[string]any{
		"contract_version": 1,
		"model":            "without-items",
		"model_version":    "1.0.0",
		"stages":           stages,
		"metrics":          map[string]any{"total_sleep_s": 3600},
		"quality":          map[string]any{"efficiency": 90},
		"sleep_quality":    map[string]any{"score": 80},
		"capabilities":     map[string]any{},
		"provenance":       map[string]any{},
	}, oldSummary)

	emptyResult := saveMigrationAnalysis(t, app, recording, "empty-result", nil, oldSummary)
	brokenResult := saveMigrationAnalysis(t, app, recording, "broken-result", nil, oldSummary)
	if _, err := app.DB().NewQuery("UPDATE analyses SET result = 'not json' WHERE id = {:id}").
		Bind(dbx.Params{"id": brokenResult}).Execute(); err != nil {
		t.Fatalf("store a broken result: %v", err)
	}
	brokenBefore := storedSummary(t, app, brokenResult)

	if err := rebuildAnalysisSummaries(app); err != nil {
		t.Fatalf("migration: %v", err)
	}

	wantStages := map[string]any{
		"epoch_seconds": 30.0,
		"start_unix_ms": 1760000000000.0,
		"count":         3.0,
		"segments": []any{
			map[string]any{"stage": "light", "count": 2.0},
			map[string]any{"stage": "deep", "count": 1.0},
		},
	}
	got := decodeStoredSummary(t, app, withItems)
	want := map[string]any{
		"stages": wantStages,
		"scores": []any{
			map[string]any{"id": "sleep_quality", "value": 78.0},
			map[string]any{"id": "data_quality", "value": 91.0},
		},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("summary with items = %#v, want %#v", got, want)
	}

	got = decodeStoredSummary(t, app, withoutItems)
	if !reflect.DeepEqual(got, map[string]any{"stages": wantStages}) {
		t.Fatalf("summary without items = %#v", got)
	}

	for name, id := range map[string]string{"empty result": emptyResult, "broken result": brokenResult} {
		if got := decodeStoredSummary(t, app, id); !reflect.DeepEqual(got, normalizedSummary(t, oldSummary)) {
			t.Fatalf("%s summary changed: %#v", name, got)
		}
	}
	if after := storedSummary(t, app, brokenResult); after != brokenBefore {
		t.Fatalf("broken result summary was rewritten: %s", after)
	}

	before := map[string]string{}
	for name, id := range map[string]string{
		"with items": withItems, "without items": withoutItems,
		"empty result": emptyResult, "broken result": brokenResult,
	} {
		before[name] = storedSummary(t, app, id)
	}
	if err := rebuildAnalysisSummaries(app); err != nil {
		t.Fatalf("second migration: %v", err)
	}
	for name, id := range map[string]string{
		"with items": withItems, "without items": withoutItems,
		"empty result": emptyResult, "broken result": brokenResult,
	} {
		if after := storedSummary(t, app, id); after != before[name] {
			t.Fatalf("second run changed the %s summary: %s", name, after)
		}
	}
}

func TestRebuildAnalysisSummariesOnEmptyDatabase(t *testing.T) {
	app := newMigrationTestApp(t)
	if err := rebuildAnalysisSummaries(app); err != nil {
		t.Fatalf("migration on an empty database: %v", err)
	}
}

func TestRebuiltSummariesAreListedWithScores(t *testing.T) {
	app := newMigrationTestApp(t)
	recording := saveMigrationRecording(t, app, "list@example.com")
	analysis := saveMigrationAnalysis(t, app, recording, "list", map[string]any{
		"contract_version": 2,
		"model":            "list",
		"model_version":    "1.0.0",
		"stages": map[string]any{
			"epoch_seconds": 30,
			"start_unix_ms": 1760000000000,
			"count":         1,
			"segments":      []any{map[string]any{"stage": "deep", "count": 1}},
		},
		"items": []any{
			map[string]any{"kind": "score", "id": "sleep_quality", "value": 78.0},
		},
		"metrics":       map[string]any{"total_sleep_s": 3600},
		"quality":       map[string]any{"efficiency": 90},
		"sleep_quality": map[string]any{"score": 80},
		"capabilities":  map[string]any{},
		"provenance":    map[string]any{},
	}, map[string]any{
		"metrics":       map[string]any{"total_sleep_s": 3600},
		"quality":       map[string]any{"efficiency": 90},
		"sleep_quality": map[string]any{"score": 80},
	})
	if err := rebuildAnalysisSummaries(app); err != nil {
		t.Fatalf("migration: %v", err)
	}

	api.Register(app)
	router, err := apis.NewRouter(app)
	if err != nil {
		t.Fatalf("router: %v", err)
	}
	api.RegisterRoutes(router)
	mux, err := router.BuildMux()
	if err != nil {
		t.Fatalf("mux: %v", err)
	}

	login := httptest.NewRequest(http.MethodPost, "/api/collections/users/auth-with-password",
		strings.NewReader(`{"identity":"list@example.com","password":"password123"}`))
	login.Header.Set("Content-Type", "application/json")
	loginRecorder := httptest.NewRecorder()
	mux.ServeHTTP(loginRecorder, login)
	if loginRecorder.Code != http.StatusOK {
		t.Fatalf("login = %d %s", loginRecorder.Code, loginRecorder.Body.String())
	}
	var token struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(loginRecorder.Body.Bytes(), &token); err != nil || token.Token == "" {
		t.Fatalf("login token: %v %s", err, loginRecorder.Body.String())
	}

	request := httptest.NewRequest(http.MethodGet, "/v1/analyses", nil)
	request.Header.Set("Authorization", "Bearer "+token.Token)
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("list = %d %s", recorder.Code, recorder.Body.String())
	}
	var list struct {
		Items []struct {
			ID      string         `json:"id"`
			Summary map[string]any `json:"summary"`
		} `json:"items"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	var summary map[string]any
	for _, item := range list.Items {
		if item.ID == analysis {
			summary = item.Summary
		}
	}
	if summary == nil {
		t.Fatalf("analysis %s is not in the list: %s", analysis, recorder.Body.String())
	}
	scores, ok := summary["scores"].([]any)
	if !ok || len(scores) != 1 || scores[0].(map[string]any)["id"] != "sleep_quality" {
		t.Fatalf("listed summary = %#v", summary)
	}
}

func newMigrationTestApp(t *testing.T) *tests.TestApp {
	t.Helper()
	app, err := tests.NewTestApp()
	if err != nil {
		t.Fatalf("test app: %v", err)
	}
	t.Cleanup(app.Cleanup)
	return app
}

func saveMigrationRecording(t *testing.T, app core.App, email string) string {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		t.Fatal(err)
	}
	user := core.NewRecord(users)
	user.SetEmail(email)
	user.SetPassword("password123")
	user.SetVerified(true)
	if err := app.Save(user); err != nil {
		t.Fatalf("save user: %v", err)
	}

	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		t.Fatal(err)
	}
	recording := core.NewRecord(recordings)
	recording.Id = "recording-1"
	recording.Set("owner", user.Id)
	recording.Set("state", "stopped")
	recording.Set("started_at", "2026-01-01 00:00:00.000Z")
	recording.Set("complete", true)
	if err := app.Save(recording); err != nil {
		t.Fatalf("save recording: %v", err)
	}
	return recording.Id
}

func saveMigrationAnalysis(t *testing.T, app core.App, recording, algorithm string, result, summary any) string {
	t.Helper()
	collection, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		t.Fatal(err)
	}
	analysis := core.NewRecord(collection)
	analysis.Set("recording", recording)
	analysis.Set("algorithm", algorithm)
	analysis.Set("version", "1.0.0")
	analysis.Set("params", map[string]any{})
	analysis.Set("params_hash", algorithm)
	analysis.Set("status", "succeeded")
	if result != nil {
		analysis.Set("result", result)
	}
	if summary != nil {
		analysis.Set("summary", summary)
	}
	if err := app.Save(analysis); err != nil {
		t.Fatalf("save analysis %s: %v", algorithm, err)
	}
	return analysis.Id
}

func storedSummary(t *testing.T, app core.App, id string) string {
	t.Helper()
	var row struct {
		Summary string `db:"summary"`
	}
	if err := app.DB().NewQuery("SELECT COALESCE(summary, '') AS summary FROM analyses WHERE id = {:id}").
		Bind(dbx.Params{"id": id}).One(&row); err != nil {
		t.Fatalf("read summary %s: %v", id, err)
	}
	return row.Summary
}

func decodeStoredSummary(t *testing.T, app core.App, id string) map[string]any {
	t.Helper()
	var summary map[string]any
	if err := json.Unmarshal([]byte(storedSummary(t, app, id)), &summary); err != nil {
		t.Fatalf("decode summary %s: %v", id, err)
	}
	return summary
}

func normalizedSummary(t *testing.T, value map[string]any) map[string]any {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var normalized map[string]any
	if err := json.Unmarshal(raw, &normalized); err != nil {
		t.Fatal(err)
	}
	return normalized
}
