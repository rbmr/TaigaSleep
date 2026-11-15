package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"

	"github.com/rbmr/TaigaSleep/backend/api/internal/results"
)

// The result is the one large part of an analysis, so it lives beside the queue
// rather than in it. These tests keep the limits and the read paths honest: one
// limit for the request and the stored value, a result too large fails the analysis
// once, and listing or claiming never chooses the result column.

func TestAResultOverTheLimitFailsTheAnalysisOnce(t *testing.T) {
	s := newServer(t)
	alice, worker := s.tokens["alice"], s.tokens["worker"]
	id := "rec-result-limit"
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})
	claimed := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil))
	if claimed.ID != analysis.ID {
		t.Fatalf("claimed %s, want %s", claimed.ID, analysis.ID)
	}

	tooLarge := validResult("baseline", "1.0.0")
	tooLarge["padding"] = strings.Repeat("x", results.MaxBytes)
	status, _, body := s.call(http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker,
		CompleteRequest{Result: mustJSON(t, tooLarge)}, nil)
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("oversized result = %d %s", status, body)
	}
	failure := decode[ErrorResponse](t, body)
	if failure.Data["code"] != "invalid_result" || !strings.Contains(failure.Message, "larger than") {
		t.Fatalf("oversized result error = %+v", failure)
	}

	_, failed := getAnalysis(s, alice, analysis.ID)
	if failed.Status != statusFailed || failed.Attempts != 1 || !strings.Contains(failed.Error, "larger than") {
		t.Fatalf("analysis after an oversized result = %+v", failed)
	}
	if count := analysisResultCount(t, s.app); count != 0 {
		t.Fatalf("an oversized result stored %d rows", count)
	}
	// A failed analysis is terminal: the queue has nothing to hand out again.
	s.expect(http.StatusNoContent, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	_, still := getAnalysis(s, alice, analysis.ID)
	if still.Status != statusFailed || still.Attempts != 1 {
		t.Fatalf("the analysis was retried: %+v", still)
	}
}

func TestListAndClaimNeverReadAResult(t *testing.T) {
	s := newServer(t)
	alice, worker := s.tokens["alice"], s.tokens["worker"]
	id := "rec-result-reads"
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	done := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline", "params": map[string]any{"n": 1}})
	mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline", "params": map[string]any{"n": 2}})
	claimed := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil))
	if claimed.ID != done.ID {
		t.Fatalf("claimed %s, want %s", claimed.ID, done.ID)
	}
	s.expect(200, http.MethodPost, "/v1/analyses/"+done.ID+"/complete", worker,
		CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.0.0"))}, nil)
	if count := analysisResultCount(t, s.app); count != 1 {
		t.Fatalf("stored %d results, want 1", count)
	}

	statements := captureResultStatements(t, s, func() {
		list := listAnalyses(s, alice, "")
		if list.TotalItems != 2 {
			t.Fatalf("list = %+v", list)
		}
		for _, item := range list.Items {
			if item.Result != nil {
				t.Fatalf("a list carried a result: %+v", item)
			}
		}
		next := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil))
		if next.Result != nil {
			t.Fatalf("a claim carried a result: %+v", next)
		}
	})
	if len(statements) > 0 {
		t.Fatalf("a list or claim read the result table: %v", statements)
	}
}

func TestDeletingAnAnalysisDeletesItsResult(t *testing.T) {
	s := newServer(t)
	alice, worker := s.tokens["alice"], s.tokens["worker"]
	id := "rec-result-cascade"
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	s.expect(200, http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker,
		CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.0.0"))}, nil)
	if count := analysisResultCount(t, s.app); count != 1 {
		t.Fatalf("stored %d results, want 1", count)
	}

	s.expect(204, http.MethodDelete, analysesAPI+"/"+analysis.ID, alice, nil, nil)
	if count := analysisResultCount(t, s.app); count != 0 {
		t.Fatalf("%d results survived their analysis", count)
	}
}

// TestAnalysisResultsMoveToTheirOwnCollection seeds the old queue column, runs the
// migration and reads every result back through the API, so a moved result is the
// same result a client sees.
func TestAnalysisResultsMoveToTheirOwnCollection(t *testing.T) {
	s := newServer(t)
	migration := migrationByFile(t, "1795219200_")
	if migration.Down == nil || migration.Up == nil {
		t.Fatal("the analysis results migration has no down or up")
	}
	if err := migration.Down(s.app); err != nil {
		t.Fatalf("migration down: %v", err)
	}

	owner := s.accountID(t, "alice@example.com")
	const seeded = 12 // more than one migration batch
	seedNights(t, s.app, owner, "mig", seeded)
	want := map[string]any{}
	for i := 0; i < seeded; i++ {
		analysisID := fmt.Sprintf("an_mig%04d", i)
		raw := fmt.Sprintf(`{"contract_version":7,"model":"baseline","model_version":"1.0.0","note":"result-%d","items":[{"kind":"number","id":"n","value":%d}]}`, i, i)
		if _, err := s.app.DB().NewQuery("UPDATE analyses SET result = {:result} WHERE id = {:id}").
			Bind(dbx.Params{"result": raw, "id": analysisID}).Execute(); err != nil {
			t.Fatalf("seed %s: %v", analysisID, err)
		}
		var value any
		if err := json.Unmarshal([]byte(raw), &value); err != nil {
			t.Fatal(err)
		}
		want[analysisID] = value
	}

	if err := migration.Up(s.app); err != nil {
		t.Fatalf("migration up: %v", err)
	}
	analyses, err := s.app.FindCollectionByNameOrId("analyses")
	if err != nil {
		t.Fatal(err)
	}
	if analyses.Fields.GetByName("result") != nil {
		t.Fatal("the migration left result on the queue table")
	}
	if count := analysisResultCount(t, s.app); count != seeded {
		t.Fatalf("migrated %d results, want %d", count, seeded)
	}
	for analysisID, expected := range want {
		_, got := getAnalysis(s, s.tokens["alice"], analysisID)
		if !reflect.DeepEqual(got.Result, expected) {
			t.Fatalf("%s result = %#v, want %#v", analysisID, got.Result, expected)
		}
	}
}

func analysisResultCount(t *testing.T, app core.App) int {
	t.Helper()
	row := struct {
		Count int `db:"count"`
	}{}
	if err := app.DB().NewQuery("SELECT COUNT(*) AS count FROM analysis_results").One(&row); err != nil {
		t.Fatalf("count results: %v", err)
	}
	return row.Count
}

// captureResultStatements runs fn and returns the statements it ran against the
// result table, as the SQLite query log sees them.
func captureResultStatements(t *testing.T, s *server, fn func()) []string {
	t.Helper()
	statements := []string{}
	s.app.ConcurrentDB().(*dbx.DB).QueryLogFunc = func(ctx context.Context, d time.Duration, sqlText string, rows *sql.Rows, err error) {
		if strings.Contains(sqlText, "analysis_results") {
			statements = append(statements, sqlText)
		}
	}
	defer func() { s.app.ConcurrentDB().(*dbx.DB).QueryLogFunc = nil }()
	fn()
	return statements
}

func migrationByFile(t *testing.T, prefix string) *core.Migration {
	t.Helper()
	for _, migration := range core.AppMigrations.Items() {
		if strings.HasPrefix(migration.File, prefix) {
			return migration
		}
	}
	t.Fatalf("no migration file starts with %s", prefix)
	return nil
}
