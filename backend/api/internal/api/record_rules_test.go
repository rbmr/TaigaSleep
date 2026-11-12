package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
)

// These are PocketBase's API scenarios: each one sends a request through the real
// router to a freshly seeded app and checks the status and body. They are the
// collection rules and the request hooks seen from outside, including the cases that
// must be refused: another account's record, an access token writing, a worker
// without a lease, a sealed recording and a parameter the algorithm did not
// announce.

const (
	ruleAliceID  = "alicealicealice"
	ruleBobID    = "bobbobbobbobbob"
	ruleWorkerID = "workerworkerwor"
	ruleTokenID  = "tokenalicealice"

	ruleAliceRecording     = "rec_alice"
	ruleBobRecording       = "rec_bob"
	rulePendingRecording   = "rec_pending"
	ruleOpenRecording      = "rec_open"
	ruleLeasedAnalysis     = "leasedanalysis1"
	ruleSucceededAnalysis  = "succeededanalys"
	ruleOtherUserAnalysis  = "bobanalysis0001"
	ruleAliceStorageBytes  = 106
	ruleAliceRecordingHold = 100
)

// seededRecordAPIApp creates an app with two users, a worker holding a lease, an
// access token for Alice, recordings in every state the rules care about and the
// analyses that make the lease visible. It returns the tokens the scenarios send.
func seededRecordAPIApp(t testing.TB) (*tests.TestApp, map[string]string) {
	t.Helper()
	app, err := tests.NewTestApp()
	if err != nil {
		t.Fatalf("test app: %v", err)
	}
	Register(app)

	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		t.Fatal(err)
	}
	newUser := func(id, email string) *core.Record {
		record := core.NewRecord(users)
		record.Id = id
		record.SetEmail(email)
		record.SetPassword("password123")
		record.SetVerified(true)
		if err := app.Save(record); err != nil {
			t.Fatalf("save user %s: %v", email, err)
		}
		return record
	}
	alice := newUser(ruleAliceID, "alice@example.com")
	newUser(ruleBobID, "bob@example.com")

	workers, err := app.FindCollectionByNameOrId("workers")
	if err != nil {
		t.Fatal(err)
	}
	worker := core.NewRecord(workers)
	worker.Id = ruleWorkerID
	worker.SetEmail("worker@example.com")
	worker.SetPassword("password123")
	worker.SetVerified(true)
	worker.Set("name", "worker-1")
	worker.Set("last_seen", nowString())
	worker.Set("algorithms", []AlgorithmView{{
		ID: "baseline", Version: "1.0.0", Name: "baseline", License: "MIT",
		ParamsSchema: map[string]any{
			"type":                 "object",
			"additionalProperties": false,
			"properties": map[string]any{
				"window": map[string]any{"type": "integer", "minimum": 1, "maximum": 21},
			},
		},
	}})
	if err := app.Save(worker); err != nil {
		t.Fatal(err)
	}

	tokens, err := app.FindCollectionByNameOrId("access_tokens")
	if err != nil {
		t.Fatal(err)
	}
	accessToken := core.NewRecord(tokens)
	accessToken.Id = ruleTokenID
	accessToken.Set("owner", alice.Id)
	accessToken.Set("name", "assistant")
	accessToken.SetRandomPassword()
	if err := app.Save(accessToken); err != nil {
		t.Fatal(err)
	}

	seedRecording := func(id string, owner string, state string, complete bool, stored int64) *core.Record {
		collection, err := app.FindCollectionByNameOrId("recordings")
		if err != nil {
			t.Fatal(err)
		}
		record := core.NewRecord(collection)
		record.Id = id
		record.Set("owner", owner)
		record.Set("state", state)
		record.Set("device", "Polar H10 X")
		record.Set("started_at", pbDateMillis(1793570400000))
		record.Set("duration_s", 0.0)
		if state != "recording" {
			record.Set("ended_at", pbDateMillis(1793570400000+3600000))
			record.Set("duration_s", 3600.0)
		}
		record.Set("complete", complete)
		record.Set("meta", testMeta(id, state))
		record.Set("files", map[string]any{})
		record.Set("stored_bytes", stored)
		if err := app.Save(record); err != nil {
			t.Fatalf("save recording %s: %v", id, err)
		}
		return record
	}
	seedRecording(ruleAliceRecording, alice.Id, "stopped", true, ruleAliceRecordingHold)
	seedRecording(ruleBobRecording, ruleBobID, "stopped", true, 0)
	seedRecording(rulePendingRecording, alice.Id, "fetch_pending", false, 0)
	open := seedRecording(ruleOpenRecording, alice.Id, "stopped", false, 6)
	// The seal check reads a stream file, not just its row.
	dir := recordingDir(app, open.Id)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "hr.log"), []byte("frames"), 0o640); err != nil {
		t.Fatal(err)
	}
	open.Set("files", map[string]any{"hr.log": map[string]any{"encoding": "identity", "uncompressed_size": 6}})
	if err := app.Save(open); err != nil {
		t.Fatal(err)
	}

	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		t.Fatal(err)
	}
	seedAnalysis := func(id, recording, algorithm, status, workerID, lease string, result map[string]any) {
		hash, _ := paramsHash(map[string]any{"seed": id})
		record := core.NewRecord(analyses)
		record.Id = id
		record.Set("recording", recording)
		record.Set("algorithm", algorithm)
		record.Set("version", "1.0.0")
		record.Set("params", map[string]any{})
		record.Set("params_hash", hash)
		record.Set("status", status)
		record.Set("worker", workerID)
		record.Set("lease_until", lease)
		record.Set("attempts", 1)
		record.Set("available_at", nowString())
		if result != nil {
			record.Set("result", result)
			record.Set("summary", map[string]any{"stages": result["stages"]})
			record.Set("finished_at", nowString())
		}
		if err := app.Save(record); err != nil {
			t.Fatalf("save analysis %s: %v", id, err)
		}
	}
	seedAnalysis(ruleLeasedAnalysis, ruleAliceRecording, "baseline", statusRunning, ruleWorkerID, afterSeconds(1800), nil)
	seedAnalysis(ruleSucceededAnalysis, ruleAliceRecording, "baseline", statusSucceeded, "", "", validResult("baseline", "1.0.0"))
	seedAnalysis(ruleOtherUserAnalysis, ruleBobRecording, "baseline", statusSucceeded, "", "", validResult("baseline", "1.0.0"))

	alice.Set("storage_bytes", ruleAliceStorageBytes)
	if err := app.Save(alice); err != nil {
		t.Fatal(err)
	}

	tokensByRole := map[string]string{}
	if tokensByRole["alice"], err = alice.NewAuthToken(); err != nil {
		t.Fatal(err)
	}
	if bob, err := app.FindRecordById("users", ruleBobID); err == nil {
		if tokensByRole["bob"], err = bob.NewAuthToken(); err != nil {
			t.Fatal(err)
		}
	}
	if tokensByRole["worker"], err = worker.NewAuthToken(); err != nil {
		t.Fatal(err)
	}
	if tokensByRole["token"], err = accessToken.NewStaticAuthToken(0); err != nil {
		t.Fatal(err)
	}
	return app, tokensByRole
}

// scenario builds one request. The Authorization header is filled in by the factory,
// from the tokens of the app that scenario actually runs against.
func scenario(name, method, path, role string, body any, status int, expected ...string) tests.ApiScenario {
	headers := map[string]string{}
	if status >= 400 && len(expected) == 0 {
		// An error body is not empty, so the scenario must say what it expects.
		expected = []string{fmt.Sprintf(`"status":%d`, status)}
	}
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			panic(err)
		}
		reader = bytes.NewReader(raw)
	}
	return tests.ApiScenario{
		Name:    name,
		Method:  method,
		URL:     path,
		Body:    reader,
		Headers: headers,
		TestAppFactory: func(t testing.TB) *tests.TestApp {
			app, tokens := seededRecordAPIApp(t)
			if role != "" {
				headers["Authorization"] = "Bearer " + tokens[role]
			}
			return app
		},
		ExpectedStatus:  status,
		ExpectedContent: expected,
	}
}

// scenarioWithout is scenario plus the fragments that must not be in the answer,
// which is how a list proves it did not include another account's records.
func scenarioWithout(name, method, path, role string, status int, forbidden []string, expected ...string) tests.ApiScenario {
	s := scenario(name, method, path, role, nil, status, expected...)
	s.NotExpectedContent = forbidden
	return s
}

func TestRecordingRules(t *testing.T) {
	aliceMeta := testMeta(ruleOpenRecording, "stopped")
	scenarios := []tests.ApiScenario{
		scenario("Alice lists her recordings", http.MethodGet, recordingsAPI, "alice", nil, 200,
			`"id":"`+ruleAliceRecording+`"`, `"id":"`+ruleOpenRecording+`"`, `"totalItems":3`),
		scenarioWithout("Alice does not list Bob's recording", http.MethodGet, recordingsAPI, "alice", 200,
			[]string{`"rec_bob"`}, `"totalItems":3`),
		scenarioWithout("Bob lists only his recording", http.MethodGet, recordingsAPI, "bob", 200,
			[]string{`"rec_alice"`}, `"id":"`+ruleBobRecording+`"`, `"totalItems":1`),
		scenario("Alice views her own recording", http.MethodGet, recordingsAPI+"/"+ruleAliceRecording, "alice", nil, 200,
			`"id":"`+ruleAliceRecording+`"`),
		scenario("Alice cannot view Bob's recording", http.MethodGet, recordingsAPI+"/"+ruleBobRecording, "alice", nil, 404),
		scenario("An access token reads the owner's recording", http.MethodGet, recordingsAPI+"/"+ruleAliceRecording, "token", nil, 200,
			`"id":"`+ruleAliceRecording+`"`),
		scenario("An access token cannot read another account's recording", http.MethodGet, recordingsAPI+"/"+ruleBobRecording, "token", nil, 404),
		scenario("A worker reads a recording it holds the lease of", http.MethodGet, recordingsAPI+"/"+ruleAliceRecording, "worker", nil, 200,
			`"id":"`+ruleAliceRecording+`"`),
		scenario("A worker cannot read a recording without a lease", http.MethodGet, recordingsAPI+"/"+ruleBobRecording, "worker", nil, 404),
		scenarioWithout("A worker does not list recordings it has no lease on", http.MethodGet, recordingsAPI, "worker", 200,
			[]string{`"rec_bob"`}, `"totalItems":1`, `"id":"`+ruleAliceRecording+`"`),

		scenario("A user creates a recording", http.MethodPost, recordingsAPI, "alice", map[string]any{
			"id": "rec_new", "meta": testMeta("rec_new", "stopped"),
		}, 200, `"id":"rec_new"`, `"state":"stopped"`, `"device":"Polar H10 X"`, `"complete":false`),
		scenario("A worker cannot create a recording", http.MethodPost, recordingsAPI, "worker", map[string]any{
			"id": "rec_worker", "meta": testMeta("rec_worker", "stopped"),
		}, 400),
		scenario("An access token cannot create a recording", http.MethodPost, recordingsAPI, "token", map[string]any{
			"id": "rec_token", "meta": testMeta("rec_token", "stopped"),
		}, 400),

		scenario("A user updates their own recording", http.MethodPatch, recordingsAPI+"/"+ruleOpenRecording, "alice",
			map[string]any{"meta": aliceMeta}, 200, `"state":"stopped"`),
		scenario("Another user cannot update a recording", http.MethodPatch, recordingsAPI+"/"+ruleAliceRecording, "bob",
			map[string]any{"meta": testMeta(ruleAliceRecording, "stopped")}, 404),
		scenario("An access token cannot update a recording", http.MethodPatch, recordingsAPI+"/"+ruleOpenRecording, "token",
			map[string]any{"meta": aliceMeta}, 404),
		scenario("A worker cannot update a recording it holds", http.MethodPatch, recordingsAPI+"/"+ruleAliceRecording, "worker",
			map[string]any{"meta": testMeta(ruleAliceRecording, "stopped")}, 404),

		scenario("A user deletes their own recording", http.MethodDelete, recordingsAPI+"/"+ruleAliceRecording, "alice", nil, 204),
		scenario("Another user cannot delete a recording", http.MethodDelete, recordingsAPI+"/"+ruleAliceRecording, "bob", nil, 404),
		scenario("An access token cannot delete a recording", http.MethodDelete, recordingsAPI+"/"+ruleOpenRecording, "token", nil, 404),
		scenario("A worker cannot delete a recording", http.MethodDelete, recordingsAPI+"/"+ruleAliceRecording, "worker", nil, 404),
	}

	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

// TestDeleteRecordingFreesTheQuota is the delete hook: a recording's bytes leave the
// account's running total with the recording, and that total is what the upload path
// enforces the quota from. Alice starts at 106, of which this recording holds 100.
func TestDeleteRecordingFreesTheQuota(t *testing.T) {
	s := scenario("Deleting a recording takes its bytes off the account", http.MethodDelete,
		recordingsAPI+"/"+ruleAliceRecording, "alice", nil, 204)
	s.AfterTestFunc = func(t testing.TB, app *tests.TestApp, _ *http.Response) {
		account, err := app.FindRecordById("users", ruleAliceID)
		if err != nil {
			t.Fatal(err)
		}
		if got := account.GetInt("storage_bytes"); got != ruleAliceStorageBytes-ruleAliceRecordingHold {
			t.Errorf("storage total after the delete = %d, want %d", got, ruleAliceStorageBytes-ruleAliceRecordingHold)
		}
	}
	s.Test(t)
}

func TestRecordingHooks(t *testing.T) {
	scenarios := []tests.ApiScenario{
		scenario("A sealed recording cannot change its metadata", http.MethodPatch,
			recordingsAPI+"/"+ruleAliceRecording, "alice",
			map[string]any{"meta": testMeta(ruleAliceRecording, "stopped")}, 409,
			`"code":"recording_complete"`),
		scenario("A sealed recording cannot be reopened", http.MethodPatch,
			recordingsAPI+"/"+ruleAliceRecording, "alice",
			map[string]bool{"complete": false}, 409, `"code":"recording_complete"`),
		scenario("A fetch_pending recording cannot be sealed", http.MethodPatch,
			recordingsAPI+"/"+rulePendingRecording, "alice",
			map[string]bool{"complete": true}, 409, `"code":"recording_in_progress"`),
		scenario("A recording with data can be sealed", http.MethodPatch,
			recordingsAPI+"/"+ruleOpenRecording, "alice",
			map[string]bool{"complete": true}, 200, `"complete":true`),
		scenario("A recording is created from its metadata only", http.MethodPost, recordingsAPI, "alice",
			map[string]any{"id": "rec_derived", "meta": testMeta("rec_derived", "stopped")}, 200,
			`"state":"stopped"`, `"duration_s":3600`, `"stored_bytes":0`, `"owner":"`+ruleAliceID+`"`),
		scenario("A create cannot carry derived fields", http.MethodPost, recordingsAPI, "alice",
			map[string]any{"id": "rec_derived", "meta": testMeta("rec_derived", "stopped"), "owner": ruleBobID}, 400,
			`"code":"invalid_body"`),
		scenario("A create with a meta.session_id that does not match is refused", http.MethodPost, recordingsAPI, "alice",
			map[string]any{"id": "rec_mismatch", "meta": testMeta("rec_other", "stopped")}, 422,
			`"code":"invalid_meta"`),
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

func TestAnalysisRules(t *testing.T) {
	scenarios := []tests.ApiScenario{
		scenarioWithout("Alice lists her analyses", http.MethodGet, analysesAPI, "alice", 200,
			[]string{`"bobanalysis0001"`}, `"id":"`+ruleLeasedAnalysis+`"`, `"id":"`+ruleSucceededAnalysis+`"`, `"totalItems":2`),
		scenarioWithout("Bob lists only his analyses", http.MethodGet, analysesAPI, "bob", 200,
			[]string{`"leasedanalysis1"`}, `"id":"`+ruleOtherUserAnalysis+`"`, `"totalItems":1`),
		scenario("An access token lists the owner's analyses", http.MethodGet, analysesAPI, "token", nil, 200,
			`"totalItems":2`, `"id":"`+ruleLeasedAnalysis+`"`),
		scenarioWithout("A worker lists only the analysis it holds", http.MethodGet, analysesAPI, "worker", 200,
			[]string{`"succeededanalys"`}, `"totalItems":1`, `"id":"`+ruleLeasedAnalysis+`"`),
		scenario("Alice views her own analysis", http.MethodGet, analysesAPI+"/"+ruleSucceededAnalysis, "alice", nil, 200,
			`"id":"`+ruleSucceededAnalysis+`"`),
		scenario("Alice cannot view Bob's analysis", http.MethodGet, analysesAPI+"/"+ruleOtherUserAnalysis, "alice", nil, 404),
		scenario("An access token reads the owner's analysis", http.MethodGet, analysesAPI+"/"+ruleSucceededAnalysis, "token", nil, 200,
			`"id":"`+ruleSucceededAnalysis+`"`),
		scenario("A worker reads the analysis it holds", http.MethodGet, analysesAPI+"/"+ruleLeasedAnalysis, "worker", nil, 200,
			`"id":"`+ruleLeasedAnalysis+`"`),
		scenario("A worker cannot read an analysis without a lease", http.MethodGet, analysesAPI+"/"+ruleSucceededAnalysis, "worker", nil, 404),

		scenario("A user asks for an analysis of their recording", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline"}, 200,
			`"status":"queued"`, `"version":"1.0.0"`, `"params_hash":`),
		scenario("A user cannot ask for an analysis of another account's recording", http.MethodPost, analysesAPI, "bob",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline"}, 400),
		scenario("A worker cannot ask for an analysis", http.MethodPost, analysesAPI, "worker",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline"}, 400),
		scenario("An access token cannot ask for an analysis", http.MethodPost, analysesAPI, "token",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline"}, 400),
		scenario("An analysis cannot be updated", http.MethodPatch, analysesAPI+"/"+ruleSucceededAnalysis, "alice",
			map[string]any{"status": "queued"}, 403),
		scenario("A user deletes their own analysis", http.MethodDelete, analysesAPI+"/"+ruleSucceededAnalysis, "alice", nil, 204),
		scenario("Another user cannot delete an analysis", http.MethodDelete, analysesAPI+"/"+ruleSucceededAnalysis, "bob", nil, 404),
		scenario("An access token cannot delete an analysis", http.MethodDelete, analysesAPI+"/"+ruleSucceededAnalysis, "token", nil, 404),
		scenario("A worker cannot delete an analysis it holds", http.MethodDelete, analysesAPI+"/"+ruleLeasedAnalysis, "worker", nil, 404),
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

func TestAnalysisHooks(t *testing.T) {
	scenarios := []tests.ApiScenario{
		scenario("An analysis request must name an announced algorithm", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "nobody-offers-this"}, 422,
			`"code":"unknown_algorithm"`),
		scenario("An analysis request must name an announced version", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline", "version": "9.9.9"}, 422,
			`"code":"unknown_algorithm"`),
		scenario("The recording must be sealed", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleOpenRecording, "algorithm": "baseline"}, 409,
			`"code":"recording_incomplete"`),
		scenario("The parameters must match the announced schema", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline", "params": map[string]any{"nope": 1}}, 422,
			`"code":"invalid_params"`),
		scenario("A parameter outside its announced bound is refused", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline", "params": map[string]any{"window": 22}}, 422,
			`"code":"invalid_params"`),
		scenario("An analysis request fills in the version and parameter hash", http.MethodPost, analysesAPI, "alice",
			map[string]any{"recording": ruleAliceRecording, "algorithm": "baseline", "params": map[string]any{"window": 5}}, 200,
			`"status":"queued"`, `"attempts":0`, `"params":{"window":5}`, `"error":""`),
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}
