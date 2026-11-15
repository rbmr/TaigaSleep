package api

import (
	"context"
	"database/sql"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
)

// The app lists its recordings and analyses, and opens one of each, page by page. Each
// answer is the collection rule as a filter, so the rule has to be a comparison an
// index answers: what one account asks for must not cost more because other accounts
// recorded more nights. These tests are the measurement of that: the query plan of the
// answer a real request produces, and the time the same request takes after ten times
// as much data for somebody else.

func TestListQueriesAreAnsweredFromAnIndex(t *testing.T) {
	s := newServer(t)
	alice := s.accountID(t, "alice@example.com")
	bob := s.accountID(t, "bob@example.com")
	workerRecord, err := s.app.FindFirstRecordByFilter("workers", "email = {:email}", dbx.Params{"email": "worker@example.com"})
	if err != nil {
		t.Fatal(err)
	}
	worker := workerRecord.Id

	seedNights(t, s.app, alice, "a", 120)
	seedNights(t, s.app, bob, "b", 1200)
	// A recording the worker holds a live lease of, so its list has something to answer.
	leased := "rec_a0000"
	if _, err := s.app.DB().NewQuery("UPDATE recordings SET leased_by = {:worker} WHERE id = {:id}").
		Bind(dbx.Params{"worker": worker, "id": leased}).Execute(); err != nil {
		t.Fatal(err)
	}
	if _, err := s.app.DB().NewQuery(
		"UPDATE analyses SET status = 'running', worker = {:worker}, lease_until = {:lease} WHERE recording = {:id}",
	).Bind(dbx.Params{"worker": worker, "lease": types.NowDateTime().Add(30 * time.Minute).String(), "id": leased}).Execute(); err != nil {
		t.Fatal(err)
	}

	token := createToken(s, "alice", "measurement").Token

	requests := []struct{ name, path, token string }{
		{"user", recordingsAPI + "?page=1&perPage=100&sort=-started_at&fields=id,state,device,started_at,ended_at,duration_s,complete,stored_bytes,created,updated", s.tokens["alice"]},
		{"user", analysesAPI + "?page=1&perPage=200&sort=-created&fields=id,recording,algorithm,version,params,status,error,attempts,created,updated,finished_at,summary", s.tokens["alice"]},
		{"user", fmt.Sprintf(analysesAPI+"?page=1&perPage=200&sort=-created&filter=recording%%20%%3D%%20%%22%s%%22", leased), s.tokens["alice"]},
		{"user", recordingsAPI + "/" + leased, s.tokens["alice"]},
		{"user", analysesAPI + "/an_a0000", s.tokens["alice"]},
		{"access token", recordingsAPI + "?page=1&perPage=100&sort=-started_at", token},
		{"access token", analysesAPI + "?page=1&perPage=200&sort=-created", token},
		{"worker", recordingsAPI, s.tokens["worker"]},
		{"worker", recordingsAPI + "/" + leased, s.tokens["worker"]},
		{"worker", analysesAPI, s.tokens["worker"]},
	}
	for _, request := range requests {
		name := request.name + " " + strings.SplitN(strings.TrimPrefix(request.path, "/api/collections/"), "?", 2)[0]
		status, plans := plansOf(t, s, request.path, request.token)
		if status != http.StatusOK {
			t.Errorf("%s: status %d", name, status)
			continue
		}
		if len(plans) == 0 {
			t.Errorf("%s: no query captured", name)
		}
		t.Logf("%s:\n    %s", name, strings.Join(plans, "\n    "))
		for _, step := range plans {
			if scansLargeTable(step) {
				t.Errorf("%s walks a large table: %s", name, step)
			}
		}
	}
}

// The recording list rule names the worker whose lease is live, so claiming an analysis
// is what puts a recording in a worker's list and finishing it takes it out again.
func TestARecordingNamesItsLessee(t *testing.T) {
	s := newServer(t)
	const id = "rec-lease"
	completeRecording(s, s.tokens["alice"], id)
	announce(s, "baseline", "1.2.0")
	mustRequestAnalysis(s, s.tokens["alice"], id, map[string]any{"algorithm": "baseline"})

	if got := listRecordings(s, s.tokens["worker"], ""); got.TotalItems != 0 {
		t.Fatalf("a worker lists %d recordings before it holds a lease", got.TotalItems)
	}
	claimed := decode[AnalysisView](s.t, s.expect(200, http.MethodPost, "/v1/analyses/claim", s.tokens["worker"], nil, nil))
	if got := listRecordings(s, s.tokens["worker"], ""); got.TotalItems != 1 || got.Items[0].ID != id {
		t.Fatalf("a worker lists %+v while it holds the lease of %s", got, id)
	}
	s.expect(200, http.MethodPost, "/v1/analyses/"+claimed.ID+"/complete", s.tokens["worker"],
		CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.2.0"))}, nil)
	if got := listRecordings(s, s.tokens["worker"], ""); got.TotalItems != 0 {
		t.Fatalf("a worker still lists %d recordings after finishing", got.TotalItems)
	}
}

func TestListTimeDoesNotGrowWithOtherAccounts(t *testing.T) {
	s := newServer(t)
	alice := s.accountID(t, "alice@example.com")
	bob := s.accountID(t, "bob@example.com")

	seedNights(t, s.app, alice, "a", 365)
	seedNights(t, s.app, bob, "b", 365)

	path := recordingsAPI + "?page=1&perPage=100&sort=-started_at&fields=id,state,device,started_at,ended_at,duration_s,complete,stored_bytes,created,updated"
	before := listQueryTime(t, s, path, s.tokens["alice"])

	// Ten times as many nights for somebody else. A rule the database has to walk the
	// table for would take about ten times as long; an index seek does not.
	seedNights(t, s.app, bob, "c", 3285)
	after := listQueryTime(t, s, path, s.tokens["alice"])
	t.Logf("one account's recording list costs %s with 365 other nights and %s with ten times as many", before, after)

	if after > 2*before+2*time.Millisecond {
		t.Fatalf("one account's list took %s with 365 other nights and %s with ten times as many", before, after)
	}
}

// listQueryTime is how long the list query itself takes, apart from the request around
// it: the statements the answer ran against the two large collections, executed again
// and measured. It is the part that would grow with other accounts' data, and the
// fastest of several runs is the one least disturbed by the machine.
func listQueryTime(t *testing.T, s *server, path, token string) time.Duration {
	t.Helper()
	_, statements, _ := capturePlans(t, s, path, token)
	if len(statements) == 0 {
		t.Fatal("no query captured")
	}
	// One pass first, so the pages the query reads are in the database's cache and the
	// measurement is the query's work rather than the disk's first answer.
	runQuery(t, s, statements)
	best := time.Duration(0)
	for i := 0; i < 9; i++ {
		start := time.Now()
		runQuery(t, s, statements)
		if total := time.Since(start); best == 0 || total < best {
			best = total
		}
	}
	return best
}

func runQuery(t *testing.T, s *server, statements []string) {
	t.Helper()
	for _, statement := range statements {
		rows, err := s.app.DB().NewQuery(statement).Rows()
		if err != nil {
			t.Fatalf("run %s: %v", statement, err)
		}
		for rows.Next() {
		}
		rows.Close()
	}
}

// plansOf runs one request and returns the query plan of every statement it ran against
// the two large collections, as the database explains them.
func plansOf(t *testing.T, s *server, path, token string) (int, []string) {
	t.Helper()
	status, _, plans := capturePlans(t, s, path, token)
	return status, plans
}

// capturePlans runs one request and returns the statements it ran against the two large
// collections and their query plans.
func capturePlans(t *testing.T, s *server, path, token string) (int, []string, []string) {
	t.Helper()
	statements := []string{}
	s.app.ConcurrentDB().(*dbx.DB).QueryLogFunc = func(ctx context.Context, d time.Duration, sqlText string, rows *sql.Rows, err error) {
		if strings.Contains(sqlText, "`recordings`") || strings.Contains(sqlText, "`analyses`") {
			statements = append(statements, sqlText)
		}
	}
	status, _, _ := s.call(http.MethodGet, path, token, nil, nil)
	s.app.ConcurrentDB().(*dbx.DB).QueryLogFunc = nil

	plans := []string{}
	for _, statement := range statements {
		rows, err := s.app.DB().NewQuery("EXPLAIN QUERY PLAN " + statement).Rows()
		if err != nil {
			t.Fatalf("explain %s: %v", statement, err)
		}
		for rows.Next() {
			var id, parent, notUsed int
			var detail string
			if err := rows.Scan(&id, &parent, &notUsed, &detail); err != nil {
				rows.Close()
				t.Fatal(err)
			}
			plans = append(plans, detail)
		}
		rows.Close()
	}
	return status, statements, plans
}

// scansLargeTable reports whether a plan step walks a collection that grows with every
// account, instead of reaching the rows a caller asked for through an index.
func scansLargeTable(step string) bool {
	for _, table := range []string{"recordings", "analyses"} {
		if strings.HasPrefix(step, "SCAN "+table) || strings.Contains(step, "SCAN "+table+" ") {
			return true
		}
	}
	return false
}

// accountID is the id of the test account with this address.
func (s *server) accountID(t *testing.T, email string) string {
	t.Helper()
	account, err := s.app.FindFirstRecordByFilter("users", "email = {:email}", dbx.Params{"email": email})
	if err != nil {
		t.Fatal(err)
	}
	return account.Id
}

// seedNights adds nights for one account, with one analysis each, straight into the
// tables: a measurement needs the rows, not the API's checks.
func seedNights(t *testing.T, app core.App, owner, prefix string, nights int) {
	t.Helper()
	start := time.Date(2026, 1, 1, 22, 0, 0, 0, time.UTC)
	for i := 0; i < nights; i++ {
		recordingID := fmt.Sprintf("rec_%s%04d", prefix, i)
		analysisID := fmt.Sprintf("an_%s%04d", prefix, i)
		moment := start.AddDate(0, 0, i).Format("2006-01-02 15:04:05.000Z")
		if _, err := app.DB().NewQuery(
			"INSERT INTO recordings (id, owner, state, device, started_at, ended_at, duration_s, complete, meta, files, stored_bytes, created, updated) " +
				"VALUES ({:id}, {:owner}, 'stopped', 'Polar H10', {:started}, {:ended}, 28800, TRUE, '{}', '{}', 0, {:created}, {:created})",
		).Bind(dbx.Params{
			"id": recordingID, "owner": owner, "started": moment, "ended": moment, "created": moment,
		}).Execute(); err != nil {
			t.Fatal(err)
		}
		if _, err := app.DB().NewQuery(
			"INSERT INTO analyses (id, recording, owner, algorithm, version, params, params_hash, status, attempts, available_at, worker, lease_until, error, created, updated) " +
				"VALUES ({:id}, {:recording}, {:owner}, 'baseline', '1.0.0', '{}', {:hash}, 'succeeded', 1, {:created}, '', '', '', {:created}, {:created})",
		).Bind(dbx.Params{
			"id": analysisID, "recording": recordingID, "owner": owner, "created": moment,
			"hash": fmt.Sprintf("%016x", i),
		}).Execute(); err != nil {
			t.Fatal(err)
		}
	}
}
