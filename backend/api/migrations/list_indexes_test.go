package migrations

import (
	"strings"
	"testing"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/types"
)

// The list indexes migration fills two columns the collection rules compare and can be
// run again on a database that already has them: a migration that half ran must finish,
// and one whose down ran must be applicable again.
func TestListIndexesAreFilledAndReversible(t *testing.T) {
	app, err := tests.NewTestApp()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(app.Cleanup)

	owner := newMigrationAccount(t, app, "indexed@example.com")
	worker := newMigrationWorker(t, app, "indexed-worker@example.com")
	open := saveIndexedRecording(t, app, owner, "recording-open")
	leased := saveIndexedRecording(t, app, owner, "recording-leased")
	leasedAnalysis := saveIndexedAnalysis(t, app, owner, leased, "leased")
	otherAnalysis := saveIndexedAnalysis(t, app, owner, open, "other")

	live := types.NowDateTime().Add(30 * time.Minute).String()
	setIndexedAnalysis(t, app, leasedAnalysis, "running", worker, live)
	setIndexedAnalysis(t, app, otherAnalysis, "succeeded", "", "")
	// A row whose owner was never filled, as one stored before the column existed.
	if _, err := app.DB().NewQuery("UPDATE analyses SET owner = '' WHERE recording = {:recording}").
		Bind(dbx.Params{"recording": open}).Execute(); err != nil {
		t.Fatal(err)
	}

	for attempt := 0; attempt < 2; attempt++ {
		if err := upListIndexes(app); err != nil {
			t.Fatalf("migration run %d: %v", attempt+1, err)
		}
		for _, id := range []string{leasedAnalysis, otherAnalysis} {
			if got := indexedAnalysisOwner(t, app, id); got != owner {
				t.Fatalf("run %d: analysis %s owner = %q, want %q", attempt+1, id, got, owner)
			}
		}
		if got := indexedRecordingLessee(t, app, leased); got != worker {
			t.Fatalf("run %d: recording %s lessee = %q, want %q", attempt+1, leased, got, worker)
		}
		if got := indexedRecordingLessee(t, app, open); got != "" {
			t.Fatalf("run %d: recording %s lessee = %q, want none", attempt+1, open, got)
		}
	}

	if err := downListIndexes(app); err != nil {
		t.Fatalf("down migration: %v", err)
	}
	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		t.Fatal(err)
	}
	if recordings.Fields.GetByName("leased_by") != nil {
		t.Fatal("the down migration left leased_by on recordings")
	}
	if rule := recordings.ListRule; rule == nil || *rule != recordingsListRule {
		t.Fatalf("the down migration left the recordings list rule as %v", rule)
	}
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		t.Fatal(err)
	}
	if analyses.Fields.GetByName("owner") != nil {
		t.Fatal("the down migration left owner on analyses")
	}
	if rule := analyses.ListRule; rule == nil || *rule != analysesQueueListRule {
		t.Fatalf("the down migration left the analyses list rule as %v", rule)
	}

	// And it can be applied again afterwards.
	if err := upListIndexes(app); err != nil {
		t.Fatalf("migration after its down: %v", err)
	}
	if got := indexedAnalysisOwner(t, app, otherAnalysis); got != owner {
		t.Fatalf("owner after re-applying = %q, want %q", got, owner)
	}
	if got := indexedRecordingLessee(t, app, leased); got != worker {
		t.Fatalf("lessee after re-applying = %q, want %q", got, worker)
	}
}

func newMigrationAccount(t *testing.T, app core.App, email string) string {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		t.Fatal(err)
	}
	account := core.NewRecord(users)
	account.SetEmail(email)
	// PocketBase's test data already holds generated usernames, so a drawn one can clash.
	account.Set("username", strings.ReplaceAll(email, "@", "."))
	account.SetPassword("password123")
	account.SetVerified(true)
	if err := app.Save(account); err != nil {
		t.Fatalf("save account: %v", err)
	}
	return account.Id
}

func newMigrationWorker(t *testing.T, app core.App, email string) string {
	t.Helper()
	workers, err := app.FindCollectionByNameOrId("workers")
	if err != nil {
		t.Fatal(err)
	}
	worker := core.NewRecord(workers)
	worker.SetEmail(email)
	worker.SetPassword("password123")
	worker.SetVerified(true)
	if err := app.Save(worker); err != nil {
		t.Fatalf("save worker: %v", err)
	}
	return worker.Id
}

func saveIndexedRecording(t *testing.T, app core.App, owner, id string) string {
	t.Helper()
	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		t.Fatal(err)
	}
	recording := core.NewRecord(recordings)
	recording.Id = id
	recording.Set("owner", owner)
	recording.Set("state", "stopped")
	recording.Set("started_at", "2026-01-01 00:00:00.000Z")
	recording.Set("complete", true)
	recording.Set("meta", map[string]any{"format": "taiga-recording"})
	if err := app.Save(recording); err != nil {
		t.Fatalf("save recording %s: %v", id, err)
	}
	return recording.Id
}

func saveIndexedAnalysis(t *testing.T, app core.App, owner, recording, seed string) string {
	t.Helper()
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		t.Fatal(err)
	}
	analysis := core.NewRecord(analyses)
	analysis.Set("recording", recording)
	analysis.Set("owner", owner)
	analysis.Set("algorithm", "baseline")
	analysis.Set("version", "1.0.0")
	analysis.Set("params", map[string]any{})
	analysis.Set("params_hash", migrationParamsHash(seed))
	analysis.Set("status", "succeeded")
	if err := app.Save(analysis); err != nil {
		t.Fatalf("save analysis %s: %v", seed, err)
	}
	return analysis.Id
}

func setIndexedAnalysis(t *testing.T, app core.App, id, status, worker, lease string) {
	t.Helper()
	if _, err := app.DB().NewQuery(
		"UPDATE analyses SET status = {:status}, worker = {:worker}, lease_until = {:lease} WHERE id = {:id}",
	).Bind(dbx.Params{"status": status, "worker": worker, "lease": lease, "id": id}).Execute(); err != nil {
		t.Fatalf("update analysis %s: %v", id, err)
	}
}

func indexedAnalysisOwner(t *testing.T, app core.App, id string) string {
	t.Helper()
	row := struct {
		Owner string `db:"owner"`
	}{}
	if err := app.DB().NewQuery("SELECT COALESCE(owner, '') AS owner FROM analyses WHERE id = {:id}").
		Bind(dbx.Params{"id": id}).One(&row); err != nil {
		t.Fatalf("read analysis %s: %v", id, err)
	}
	return row.Owner
}

func indexedRecordingLessee(t *testing.T, app core.App, id string) string {
	t.Helper()
	row := struct {
		Lessee string `db:"leased_by"`
	}{}
	if err := app.DB().NewQuery("SELECT COALESCE(leased_by, '') AS leased_by FROM recordings WHERE id = {:id}").
		Bind(dbx.Params{"id": id}).One(&row); err != nil {
		t.Fatalf("read recording %s: %v", id, err)
	}
	return row.Lessee
}
