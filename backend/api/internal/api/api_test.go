package api

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"

	_ "github.com/rbmr/TaigaSleep/backend/api/migrations"
)

// These tests run the real routes against a real PocketBase app with the real
// migrations, in a temporary directory. They exercise the HTTP contract of
// docs/api.md; the integration suite in backend/tests adds the worker process.
//
// Recordings and analyses go through PocketBase's record API, so those tests also
// cover the collection rules: what a user, an access token and a worker may see and
// change.

const (
	recordingsAPI = "/api/collections/recordings/records"
	analysesAPI   = "/api/collections/analyses/records"
)

type server struct {
	t    *testing.T
	app  *tests.TestApp
	mux  http.Handler
	user string
	// tokens by account name
	tokens map[string]string
}

func newServer(t *testing.T) *server {
	t.Helper()
	app, err := tests.NewTestApp()
	if err != nil {
		t.Fatalf("test app: %v", err)
	}
	t.Cleanup(app.Cleanup)
	Register(app)
	router, err := apis.NewRouter(app)
	if err != nil {
		t.Fatalf("router: %v", err)
	}
	RegisterRoutes(router)
	mux, err := router.BuildMux()
	if err != nil {
		t.Fatalf("mux: %v", err)
	}
	s := &server{t: t, app: app, mux: mux, tokens: map[string]string{}}
	s.account("users", "alice@example.com", "")
	s.account("users", "bob@example.com", "")
	s.account("workers", "worker@example.com", "worker-1")
	return s
}

func (s *server) account(collection, email, name string) {
	s.t.Helper()
	s.createAccount(collection, email, name)
	status, _, body := s.call(http.MethodPost, "/api/collections/"+collection+"/auth-with-password", "",
		map[string]string{"identity": email, "password": "password123"}, nil)
	if status != 200 {
		s.t.Fatalf("login %s: %d %s", email, status, body)
	}
	var out AuthResponse
	if err := json.Unmarshal(body, &out); err != nil {
		s.t.Fatal(err)
	}
	s.tokens[strings.SplitN(email, "@", 2)[0]] = out.Token
}

// createAccount adds an account without logging it in, for tests that cannot spend
// the login budget of the address they act as.
func (s *server) createAccount(collection, email, name string) {
	s.t.Helper()
	c, err := s.app.FindCollectionByNameOrId(collection)
	if err != nil {
		s.t.Fatal(err)
	}
	record := core.NewRecord(c)
	record.SetEmail(email)
	record.SetPassword("password123")
	record.SetVerified(true)
	if name != "" {
		record.Set("name", name)
	}
	if err := s.app.Save(record); err != nil {
		s.t.Fatalf("create %s: %v", email, err)
	}
}

// call sends a request. body may be nil, raw bytes, or anything JSON-encodable.
func (s *server) call(method, path, token string, body any, headers map[string]string) (int, http.Header, []byte) {
	s.t.Helper()
	var reader io.Reader
	contentType := ""
	switch v := body.(type) {
	case nil:
	case []byte:
		reader = bytes.NewReader(v)
		contentType = "application/octet-stream"
	default:
		raw, err := json.Marshal(v)
		if err != nil {
			s.t.Fatal(err)
		}
		reader = bytes.NewReader(raw)
		contentType = "application/json"
	}
	request := httptest.NewRequest(method, path, reader)
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	recorder := httptest.NewRecorder()
	s.mux.ServeHTTP(recorder, request)
	result := recorder.Result()
	data, _ := io.ReadAll(result.Body)
	return result.StatusCode, result.Header, data
}

func (s *server) expect(want int, method, path, token string, body any, headers map[string]string) []byte {
	s.t.Helper()
	status, _, data := s.call(method, path, token, body, headers)
	if status != want {
		s.t.Fatalf("%s %s = %d, want %d: %s", method, path, status, want, data)
	}
	return data
}

// expectCode checks the API's stable code under data.code of PocketBase's error
// shape. PocketBase's own rule failures carry no code, so those tests check only the
// status.
func (s *server) expectCode(want int, code, method, path, token string, body any, headers map[string]string) {
	s.t.Helper()
	data := s.expect(want, method, path, token, body, headers)
	var failure ErrorResponse
	if err := json.Unmarshal(data, &failure); err != nil || failure.Data["code"] != code {
		s.t.Fatalf("%s %s: error = %s, want data.code %q", method, path, data, code)
	}
}

type recordingRecord struct {
	ID          string          `json:"id"`
	Owner       string          `json:"owner"`
	State       string          `json:"state"`
	Device      string          `json:"device"`
	StartedAt   string          `json:"started_at"`
	EndedAt     string          `json:"ended_at"`
	DurationS   float64         `json:"duration_s"`
	Complete    bool            `json:"complete"`
	StoredBytes int64           `json:"stored_bytes"`
	Meta        json.RawMessage `json:"meta"`
	Created     string          `json:"created"`
	Updated     string          `json:"updated"`
}

type recordPage[T any] struct {
	Items      []T `json:"items"`
	Page       int `json:"page"`
	PerPage    int `json:"perPage"`
	TotalItems int `json:"totalItems"`
}

// createRecording sends the recording through the record API, as a client does. The
// answer is 200 for a new recording and for a repeated create of an open one.
func createRecording(s *server, token, id string, meta map[string]any) recordingRecord {
	s.t.Helper()
	body := s.expect(http.StatusOK, http.MethodPost, recordingsAPI, token,
		map[string]any{"id": id, "meta": meta}, nil)
	return decode[recordingRecord](s.t, body)
}

// replaceMeta updates an open recording's metadata.
func replaceMeta(s *server, token, id string, meta map[string]any) recordingRecord {
	s.t.Helper()
	body := s.expect(http.StatusOK, http.MethodPatch, recordingsAPI+"/"+id, token, map[string]any{"meta": meta}, nil)
	return decode[recordingRecord](s.t, body)
}

// seal marks a recording complete.
func seal(s *server, token, id string) recordingRecord {
	s.t.Helper()
	body := s.expect(http.StatusOK, http.MethodPatch, recordingsAPI+"/"+id, token, map[string]bool{"complete": true}, nil)
	return decode[recordingRecord](s.t, body)
}

func listRecordings(s *server, token, query string) recordPage[recordingRecord] {
	s.t.Helper()
	return decode[recordPage[recordingRecord]](s.t, s.expect(http.StatusOK, http.MethodGet, recordingsAPI+query, token, nil, nil))
}

func requestAnalysis(s *server, token, id string, body map[string]any) (int, []byte) {
	s.t.Helper()
	payload := map[string]any{"recording": id}
	for key, value := range body {
		payload[key] = value
	}
	status, _, data := s.call(http.MethodPost, analysesAPI, token, payload, nil)
	return status, data
}

func mustRequestAnalysis(s *server, token, id string, body map[string]any) AnalysisView {
	s.t.Helper()
	status, data := requestAnalysis(s, token, id, body)
	if status != http.StatusOK {
		s.t.Fatalf("request analysis = %d %s", status, data)
	}
	return decode[AnalysisView](s.t, data)
}

func getAnalysis(s *server, token, id string) (int, AnalysisView) {
	s.t.Helper()
	status, _, data := s.call(http.MethodGet, analysesAPI+"/"+id, token, nil, nil)
	if status != http.StatusOK {
		return status, AnalysisView{}
	}
	return status, decode[AnalysisView](s.t, data)
}

func listAnalyses(s *server, token, query string) recordPage[AnalysisView] {
	s.t.Helper()
	return decode[recordPage[AnalysisView]](s.t, s.expect(http.StatusOK, http.MethodGet, analysesAPI+query, token, nil, nil))
}

func testMeta(id, state string) map[string]any {
	meta := map[string]any{
		"format": "taiga-recording", "version": 1, "session_id": id, "state": state,
		"start_utc_ms": 1793570400000, "end_utc_ms": nil, "timezone": "Europe/Amsterdam",
		"app":    map[string]any{"version": "0.5.0", "version_code": 5000},
		"phone":  map[string]any{"manufacturer": "S", "model": "M", "android": "15", "sdk": 35},
		"sensor": map[string]any{"kind": "polar-h10", "id": "X", "name": "Polar H10 X", "firmware": "3", "battery_percent_at_start": 90},
		"config": map[string]any{"hr": true, "ecg": false, "acc": false, "ecg_rate_hz": 130, "acc_rate_hz": 50, "acc_range_g": 8},
	}
	if state != "recording" {
		meta["end_utc_ms"] = 1793570400000 + 3600000
	}
	return meta
}

func decode[T any](t *testing.T, data []byte) T {
	t.Helper()
	var value T
	if err := json.Unmarshal(data, &value); err != nil {
		t.Fatalf("decode %s: %v", data, err)
	}
	return value
}

func appendChunk(s *server, token, id, name string, offset int, chunk []byte, extra map[string]string) (int, http.Header, []byte) {
	headers := map[string]string{headerUploadOffset: strconv.Itoa(offset)}
	for k, v := range extra {
		headers[k] = v
	}
	return s.call(http.MethodPatch, "/v1/recordings/"+id+"/files/"+name, token, chunk, headers)
}

func completeRecording(s *server, token, id string) {
	s.t.Helper()
	createRecording(s, token, id, testMeta(id, "stopped"))
	status, _, body := appendChunk(s, token, id, "hr.log", 0, []byte("frames"), nil)
	if status != 204 {
		s.t.Fatalf("append: %d %s", status, body)
	}
	seal(s, token, id)
}

func announce(s *server, id, version string) {
	s.t.Helper()
	s.expect(204, http.MethodPut, "/v1/workers/me", s.tokens["worker"], WorkerAnnouncement{
		Name: "worker-1", Version: "0.1.0",
		Algorithms: []AlgorithmView{{ID: id, Version: version, Name: id, License: "MIT"}},
	}, nil)
}

// announceWithSchema announces an algorithm whose parameters are typed, so a
// request can be checked against it.
func announceWithSchema(s *server, id, version string) {
	s.t.Helper()
	s.expect(204, http.MethodPut, "/v1/workers/me", s.tokens["worker"], WorkerAnnouncement{
		Name: "worker-1", Version: "0.1.0",
		Algorithms: []AlgorithmView{{
			ID: id, Version: version, Name: id, License: "MIT",
			ParamsSchema: map[string]any{
				"type":                 "object",
				"additionalProperties": false,
				"properties": map[string]any{
					"window": map[string]any{"type": "integer", "minimum": 1, "maximum": 21},
					"label":  map[string]any{"type": "string", "enum": []any{"a", "b"}},
					"weight": map[string]any{"type": []any{"number", "null"}, "minimum": 0.0},
				},
			},
		}},
	}, nil)
}

func validResult(algorithm, version string) map[string]any {
	return map[string]any{
		"contract_version": 3, "model": algorithm, "model_version": version,
		"stages":  map[string]any{"epoch_seconds": 30, "start_unix_ms": 1, "count": 2, "segments": []map[string]any{{"stage": "wake", "count": 2}}},
		"metrics": map[string]any{},
		"items": []map[string]any{
			{"kind": "number", "id": "total_duration_s", "label": "Recording length", "group": "quality", "unit": "s", "value": 60.0, "explanation": "Length of the analyzed recording.", "sources": []string{"meta"}},
			{"kind": "score", "id": "sleep_quality", "label": "Sleep score", "group": "score", "unit": "%", "value": 78.0, "explanation": "Weighted sleep figures.", "sources": []string{"stages"}, "components": []map[string]any{{"id": "sleep_efficiency", "label": "Sleep efficiency", "weight": 100.0, "value": 78.0}}},
		},
		"capabilities": map[string]any{},
		"provenance":   map[string]any{},
	}
}

func TestSystemRoutesArePublic(t *testing.T) {
	s := newServer(t)
	health := decode[HealthResponse](t, s.expect(200, http.MethodGet, "/v1/health", "", nil, nil))
	if health.Status != "ok" {
		t.Fatalf("health = %+v", health)
	}
	version := decode[VersionResponse](t, s.expect(200, http.MethodGet, "/v1/version", "", nil, nil))
	if version.Service != "taiga-sleep" || version.API < 1 {
		t.Fatalf("version = %+v", version)
	}
	s.expect(200, http.MethodGet, "/v1/openapi.json", "", nil, nil)
	s.expect(200, http.MethodGet, "/v1/docs", "", nil, nil)
}

func TestAuthentication(t *testing.T) {
	s := newServer(t)
	// PocketBase's password login answers its own error for a wrong password.
	if status, _, body := s.call(http.MethodPost, "/api/collections/users/auth-with-password", "",
		map[string]string{"identity": "alice@example.com", "password": "wrong"}, nil); status != 400 {
		t.Fatalf("wrong password = %d %s", status, body)
	}
	if status, _, body := s.call(http.MethodPost, "/api/collections/users/auth-with-password", "",
		map[string]string{"identity": "alice@example.com"}, nil); status != 400 {
		t.Fatalf("missing password = %d %s", status, body)
	}
	s.expect(401, http.MethodGet, "/v1/recordings/x/files", "", nil, nil)
	s.expect(401, http.MethodGet, "/v1/recordings/x/files", "not-a-token", nil, nil)

	// A user token reads the user's own data.
	s.expect(200, http.MethodGet, recordingsAPI, s.tokens["alice"], nil, nil)

	// Refreshing answers the token with the account it belongs to, and the new token works.
	refreshed := decode[AuthResponse](t, s.expect(200, http.MethodPost,
		"/api/collections/users/auth-refresh", s.tokens["alice"], map[string]any{}, nil))
	if refreshed.Token == "" || refreshed.Record.Email != "alice@example.com" || refreshed.Record.ID == "" {
		t.Fatalf("refreshed = %+v", refreshed)
	}
	s.expect(200, http.MethodGet, recordingsAPI, refreshed.Token, nil, nil)

	// A worker token is for the workers collection: the users route refuses it, its own
	// route refreshes it, and the worker routes work with it.
	if status, _, body := s.call(http.MethodPost, "/api/collections/users/auth-refresh", s.tokens["worker"], map[string]any{}, nil); status != 403 {
		t.Fatalf("worker token on the users refresh = %d %s", status, body)
	}
	workerSession := decode[AuthResponse](t, s.expect(200, http.MethodPost,
		"/api/collections/workers/auth-refresh", s.tokens["worker"], map[string]any{}, nil))
	if workerSession.Token == "" || workerSession.Record.Email != "worker@example.com" {
		t.Fatalf("worker refresh = %+v", workerSession)
	}
	s.expect(204, http.MethodPut, "/v1/workers/me", workerSession.Token, WorkerAnnouncement{
		Name: "worker-1", Version: "0.1.0",
		Algorithms: []AlgorithmView{{ID: "baseline", Version: "1.0.0", Name: "baseline", License: "MIT"}},
	}, nil)

	// A user cannot use worker endpoints, and a worker cannot use the user-only ones.
	s.expect(403, http.MethodPost, "/v1/analyses/claim", s.tokens["alice"], nil, nil)
	s.expect(403, http.MethodGet, "/v1/algorithms", s.tokens["worker"], nil, nil)
}

// TestRecordAPIIsOpenAndTheRestIsClosed covers both halves of the change: recordings
// and analyses are PocketBase's record API now, and every other collection's record
// endpoints stay closed.
func TestRecordAPIIsOpenAndTheRestIsClosed(t *testing.T) {
	s := newServer(t)
	createRecording(s, s.tokens["alice"], "rec1", testMeta("rec1", "recording"))

	// The rules decide, not a closed collection: a user sees their own list and can
	// fetch the record, which a closed collection answers with 403.
	s.expect(200, http.MethodGet, recordingsAPI, s.tokens["alice"], nil, nil)
	s.expect(200, http.MethodGet, recordingsAPI+"/rec1", s.tokens["alice"], nil, nil)
	s.expect(200, http.MethodGet, analysesAPI, s.tokens["alice"], nil, nil)

	for _, path := range []string{
		"/api/collections/users/records",
		"/api/collections/workers/records",
		"/api/collections/access_tokens/records",
	} {
		status, _, _ := s.call(http.MethodGet, path, s.tokens["alice"], nil, nil)
		if status != 403 && status != 404 {
			t.Errorf("GET %s = %d, want it closed", path, status)
		}
	}
	// Sign-up stays closed: an account is made by the operator, not by anyone who can
	// reach the auth route.
	for _, collection := range []string{"users", "workers"} {
		status, _, body := s.call(http.MethodPost, "/api/collections/"+collection+"/records", "",
			map[string]string{
				"email": "new@example.com", "password": "password123",
				"passwordConfirm": "password123",
			}, nil)
		if status != 403 && status != 404 {
			t.Errorf("sign-up for %s = %d %s, want it refused", collection, status, body)
		}
	}
}

// A recording that still waits for part of its night from the tracker is uploaded
// as metadata like any other, but it cannot be sealed: the missing samples may still
// arrive and the server would then keep an incomplete recording forever.
func TestFetchPendingRecordingCannotBeCompleted(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	id := "20261102_220000-cd34"

	createRecording(s, alice, id, testMeta(id, "fetch_pending"))
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	s.expectCode(409, "recording_in_progress", http.MethodPatch, recordingsAPI+"/"+id, alice, map[string]bool{"complete": true}, nil)

	// The tracker handed the rest over: the recording is finished and can be sealed.
	replaceMeta(s, alice, id, testMeta(id, "stopped"))
	done := seal(s, alice, id)
	if !done.Complete || done.State != "stopped" {
		t.Fatalf("complete = %+v", done)
	}
}

func TestRecordingLifecycle(t *testing.T) {
	s := newServer(t)
	alice, bob := s.tokens["alice"], s.tokens["bob"]
	id := "20261101_220000-ab12"

	created := createRecording(s, alice, id, testMeta(id, "recording"))
	if created.ID != id || created.State != "recording" || created.Complete || created.Device != "Polar H10 X" || created.EndedAt != "" {
		t.Fatalf("created = %+v", created)
	}
	if created.StartedAt == "" || created.DurationS != 0 {
		t.Fatalf("derived fields = %+v", created)
	}
	// meta is returned as it was sent, including fields this version does not know.
	extra := testMeta(id, "recording")
	extra["future_field"] = map[string]any{"a": 1}
	var echoed map[string]any
	if err := json.Unmarshal(replaceMeta(s, alice, id, extra).Meta, &echoed); err != nil ||
		echoed["future_field"] == nil || echoed["sensor"].(map[string]any)["kind"] != "polar-h10" {
		t.Fatalf("meta not returned verbatim: %v %v", echoed, err)
	}

	// Repeating a create replaces the metadata; another account gets no hint but id_taken.
	createRecording(s, alice, id, testMeta(id, "recording"))
	s.expectCode(409, "id_taken", http.MethodPost, recordingsAPI, bob, map[string]any{"id": id, "meta": testMeta(id, "recording")}, nil)
	s.expect(404, http.MethodGet, recordingsAPI+"/"+id, bob, nil, nil)
	s.expect(404, http.MethodDelete, recordingsAPI+"/"+id, bob, nil, nil)
	s.expect(404, http.MethodPatch, recordingsAPI+"/"+id, bob, map[string]any{"meta": testMeta(id, "stopped")}, nil)

	// Validation.
	s.expectCode(422, "invalid_meta", http.MethodPost, recordingsAPI, alice, map[string]any{"id": "other-id", "meta": testMeta(id, "recording")}, nil)
	s.expectCode(400, "invalid_meta", http.MethodPost, recordingsAPI, alice, map[string]any{"id": "x1", "meta": map[string]string{"format": "nope"}}, nil)
	s.expect(400, http.MethodPost, recordingsAPI, alice, map[string]any{"id": "bad.id", "meta": testMeta("bad.id", "recording")}, nil)
	s.expectCode(400, "invalid_body", http.MethodPost, recordingsAPI, alice, map[string]any{"id": "rec2", "meta": testMeta("rec2", "recording"), "state": "stopped"}, nil)

	// Completing needs a finished recording with data.
	s.expectCode(409, "recording_in_progress", http.MethodPatch, recordingsAPI+"/"+id, alice, map[string]bool{"complete": true}, nil)
	replaceMeta(s, alice, id, testMeta(id, "stopped"))
	s.expectCode(422, "no_data", http.MethodPatch, recordingsAPI+"/"+id, alice, map[string]bool{"complete": true}, nil)
	s.expectCode(400, "invalid_body", http.MethodPatch, recordingsAPI+"/"+id, alice, map[string]string{"x": "y"}, nil)
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	done := seal(s, alice, id)
	if !done.Complete || done.StoredBytes != 6 || done.State != "stopped" || done.EndedAt == "" || done.DurationS != 3600 {
		t.Fatalf("complete = %+v", done)
	}
	seal(s, alice, id) // idempotent
	s.expectCode(409, "recording_complete", http.MethodPatch, recordingsAPI+"/"+id, alice, map[string]bool{"complete": false}, nil)
	s.expectCode(409, "recording_complete", http.MethodPatch, recordingsAPI+"/"+id, alice, map[string]any{"meta": testMeta(id, "stopped")}, nil)
	s.expectCode(409, "recording_complete", http.MethodPost, recordingsAPI, alice, map[string]any{"id": id, "meta": testMeta(id, "stopped")}, nil)

	// Lists are scoped to the owner and filtered by PocketBase.
	list := listRecordings(s, alice, "")
	if list.TotalItems != 1 || len(list.Items) != 1 || list.PerPage != 30 {
		t.Fatalf("list = %+v", list)
	}
	if got := listRecordings(s, bob, ""); got.TotalItems != 0 {
		t.Fatalf("bob sees %d recordings", got.TotalItems)
	}
	for query, want := range map[string]int{
		"?filter=" + url.QueryEscape("complete=true"):                     1,
		"?filter=" + url.QueryEscape("complete=false"):                    0,
		"?filter=" + url.QueryEscape(`state="stopped"`):                   1,
		"?filter=" + url.QueryEscape(`state="recording"`):                 0,
		"?filter=" + url.QueryEscape(`started_at>="2026-11-01 00:00:00"`): 1,
		"?filter=" + url.QueryEscape(`started_at>="2027-01-01 00:00:00"`): 0,
		"?sort=-duration_s&perPage=1":                                     1,
	} {
		if got := listRecordings(s, alice, query); len(got.Items) != want {
			t.Errorf("%s: %d items, want %d", query, len(got.Items), want)
		}
	}
	for _, query := range []string{"?sort=bad.feld", "?filter=" + url.QueryEscape("(")} {
		s.expect(400, http.MethodGet, recordingsAPI+query, alice, nil, nil)
	}

	s.expect(204, http.MethodDelete, recordingsAPI+"/"+id, alice, nil, nil)
	s.expect(404, http.MethodGet, recordingsAPI+"/"+id, alice, nil, nil)
}

func TestFileUpload(t *testing.T) {
	s := newServer(t)
	alice, bob := s.tokens["alice"], s.tokens["bob"]
	id := "rec1"
	createRecording(s, alice, id, testMeta(id, "recording"))

	// Appending needs the right offset; a wrong one says where the file is.
	if st, h, _ := appendChunk(s, alice, id, "hr.log", 0, []byte("hello "), nil); st != 204 || h.Get(headerUploadOffset) != "6" {
		t.Fatalf("first chunk = %d %v", st, h)
	}
	st, h, body := appendChunk(s, alice, id, "hr.log", 0, []byte("again"), nil)
	if st != 409 || h.Get(headerUploadOffset) != "6" || decode[ErrorResponse](t, body).Data["code"] != "offset_mismatch" {
		t.Fatalf("mismatch = %d %v %s", st, h, body)
	}
	if st, h, _ := appendChunk(s, alice, id, "hr.log", 6, []byte("world"), nil); st != 204 || h.Get(headerUploadOffset) != "11" {
		t.Fatalf("second chunk = %d %v", st, h)
	}
	if st, _, _ := appendChunk(s, alice, id, "hr.log", -1, []byte("x"), nil); st != 400 {
		t.Fatalf("negative offset = %d", st)
	}
	if st, _, _ := appendChunk(s, alice, id, "hr.log", 11, []byte{}, nil); st != 400 {
		t.Fatalf("empty chunk = %d", st)
	}
	if st, _, _ := appendChunk(s, alice, id, "meta.json", 0, []byte("x"), nil); st != 404 {
		t.Fatalf("meta.json is not a file: %d", st)
	}
	if st, _, _ := appendChunk(s, bob, id, "hr.log", 11, []byte("x"), nil); st != 404 {
		t.Fatalf("another account appended: %d", st)
	}

	// HEAD tells where to resume.
	status, header, _ := s.call(http.MethodHead, "/v1/recordings/"+id+"/files/hr.log", alice, nil, nil)
	if status != 200 || header.Get(headerUploadOffset) != "11" {
		t.Fatalf("head = %d %v", status, header)
	}
	s.expect(404, http.MethodHead, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, nil)

	// A gzip file is a sequence of complete members and keeps its encoding.
	first, second := gzipMember(t, bytes.Repeat([]byte("ab"), 500)), gzipMember(t, bytes.Repeat([]byte("cd"), 500))
	gz := map[string]string{"Content-Encoding": "gzip"}
	if st, _, b := appendChunk(s, alice, id, "ecg.log", 0, first, gz); st != 204 {
		t.Fatalf("gzip chunk = %d %s", st, b)
	}
	if st, _, _ := appendChunk(s, alice, id, "ecg.log", len(first), second, gz); st != 204 {
		t.Fatalf("second gzip chunk = %d", st)
	}
	st, _, b := appendChunk(s, alice, id, "ecg.log", len(first)+len(second), []byte("plain"), nil)
	if st != 409 || decode[ErrorResponse](t, b).Data["code"] != "encoding_mismatch" {
		t.Fatalf("mixed encoding = %d %s", st, b)
	}
	if st, _, b := appendChunk(s, alice, id, "acc.log", 0, []byte("not gzip at all, really not"), gz); st != 400 || decode[ErrorResponse](t, b).Data["code"] != "invalid_gzip" {
		t.Fatalf("bad gzip = %d %s", st, b)
	}

	files := decode[FileList](t, s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files", alice, nil, nil)).Items
	if len(files) != 2 || files[0].Name != "hr.log" || files[0].Size != 11 || files[0].Encoding != "identity" ||
		files[1].Name != "ecg.log" || files[1].Encoding != "gzip" || files[1].UncompressedSize != 2000 || files[1].Size != int64(len(first)+len(second)) {
		t.Fatalf("files = %+v", files)
	}

	// Downloads: identity as is; gzip inflated unless the client accepts gzip.
	if got := s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files/hr.log", alice, nil, nil); string(got) != "hello world" {
		t.Fatalf("hr.log = %q", got)
	}
	inflated := s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, nil)
	if want := append(bytes.Repeat([]byte("ab"), 500), bytes.Repeat([]byte("cd"), 500)...); !bytes.Equal(inflated, want) {
		t.Fatalf("inflated ecg.log has %d bytes", len(inflated))
	}
	status, header, stored := s.call(http.MethodGet, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, map[string]string{"Accept-Encoding": "gzip"})
	if status != 200 || header.Get("Content-Encoding") != "gzip" || !bytes.Equal(stored, append(append([]byte{}, first...), second...)) {
		t.Fatalf("stored gzip = %d %v %d bytes", status, header, len(stored))
	}
	if st, _, _ := s.call(http.MethodGet, "/v1/recordings/"+id+"/files/hr.log", bob, nil, nil); st != 404 {
		t.Fatalf("another account downloaded: %d", st)
	}

	// A file can be restarted, and nothing changes once the recording is complete.
	s.expect(204, http.MethodDelete, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, nil)
	s.expect(404, http.MethodDelete, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, nil)
	replaceMeta(s, alice, id, testMeta(id, "stopped"))
	seal(s, alice, id)
	if st, _, b := appendChunk(s, alice, id, "hr.log", 11, []byte("more"), nil); st != 409 || decode[ErrorResponse](t, b).Data["code"] != "recording_complete" {
		t.Fatalf("append after complete = %d %s", st, b)
	}
	s.expectCode(409, "recording_complete", http.MethodDelete, "/v1/recordings/"+id+"/files/hr.log", alice, nil, nil)
}

func TestChunkLimits(t *testing.T) {
	t.Setenv("TAIGA_MAX_UPLOAD_CHUNK_MB", "1")
	s := newServer(t)
	alice := s.tokens["alice"]
	createRecording(s, alice, "big", testMeta("big", "recording"))
	if st, _, b := appendChunk(s, alice, "big", "hr.log", 0, make([]byte, 1024*1024+1), nil); st != 413 || decode[ErrorResponse](t, b).Data["code"] != "chunk_too_large" {
		t.Fatalf("oversized chunk = %d %s", st, b)
	}
}

// seedStoredBytes puts a file of size bytes into a recording without uploading it, so
// a test can reach a quota without sending gigabytes through the API. It keeps the
// stored totals in step, as a chunk would.
func seedStoredBytes(t *testing.T, s *server, id string, size int64) {
	t.Helper()
	dir := recordingDir(s.app, id)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(filepath.Join(dir, "hr.log"))
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Truncate(size); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	record, err := s.app.FindRecordById("recordings", id)
	if err != nil {
		t.Fatal(err)
	}
	previous := int64(record.GetInt("stored_bytes"))
	record.Set("files", map[string]FileEntry{"hr.log": {Encoding: encodingIdentity, UncompressedSize: size}})
	record.Set("stored_bytes", size)
	if err := s.app.Save(record); err != nil {
		t.Fatal(err)
	}
	if delta := size - previous; delta != 0 {
		if err := addAccountBytes(s.app, record.GetString("owner"), delta); err != nil {
			t.Fatal(err)
		}
	}
}

// storedTotals reads what the backend keeps for one recording and one account.
func storedTotals(t *testing.T, s *server, id, email string) (recording, account int64) {
	t.Helper()
	record, err := s.app.FindRecordById("recordings", id)
	if err != nil {
		t.Fatal(err)
	}
	return int64(record.GetInt("stored_bytes")), accountStorage(t, s, email)
}

// accountStorage reads the running total the quota is enforced from.
func accountStorage(t *testing.T, s *server, email string) int64 {
	t.Helper()
	user, err := s.app.FindAuthRecordByEmail("users", email)
	if err != nil {
		t.Fatal(err)
	}
	return int64(user.GetInt("storage_bytes"))
}

func TestStorageQuota(t *testing.T) {
	t.Setenv("TAIGA_USER_QUOTA_GB", "1")
	s := newServer(t)
	alice, bob := s.tokens["alice"], s.tokens["bob"]
	quota := int64(1) * 1024 * 1024 * 1024

	// One account whose recordings already fill the quota.
	createRecording(s, alice, "full", testMeta("full", "recording"))
	seedStoredBytes(t, s, "full", quota)
	createRecording(s, alice, "more", testMeta("more", "recording"))
	if st, _, body := appendChunk(s, alice, "more", "hr.log", 0, []byte("frames"), nil); st != 413 ||
		decode[ErrorResponse](t, body).Data["code"] != "storage_quota_exceeded" {
		t.Fatalf("upload beyond the quota = %d %s", st, body)
	}

	// The quota is per account: another account still uploads.
	createRecording(s, bob, "bobs", testMeta("bobs", "recording"))
	if st, _, body := appendChunk(s, bob, "bobs", "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("another account = %d %s", st, body)
	}

	// Deleting the bytes makes room again. The record API's delete hook is what takes
	// the recording's bytes off the account's total.
	s.expect(204, http.MethodDelete, recordingsAPI+"/full", alice, nil, nil)
	if got := accountStorage(t, s, "alice@example.com"); got != 0 {
		t.Fatalf("alice still holds %d bytes after deleting her recording", got)
	}
	if st, _, body := appendChunk(s, alice, "more", "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("upload after freeing space = %d %s", st, body)
	}
}

func TestZeroStorageQuotaIsUnlimited(t *testing.T) {
	t.Setenv("TAIGA_USER_QUOTA_GB", "0")
	s := newServer(t)
	alice := s.tokens["alice"]
	createRecording(s, alice, "full", testMeta("full", "recording"))
	seedStoredBytes(t, s, "full", int64(1024)*1024*1024*1024)
	createRecording(s, alice, "more", testMeta("more", "recording"))
	if st, _, body := appendChunk(s, alice, "more", "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("quota 0 must not limit an upload: %d %s", st, body)
	}
}

// TestStorageTotalsFollowWrites checks that the bytes a recording holds and the
// account's total move with every chunk and every delete that changes them.
func TestStorageTotalsFollowWrites(t *testing.T) {
	s := newServer(t)
	alice, bob := s.tokens["alice"], s.tokens["bob"]
	id := "totals"
	createRecording(s, alice, id, testMeta(id, "recording"))

	check := func(wantRecording, wantAccount int64) {
		t.Helper()
		recording, account := storedTotals(t, s, id, "alice@example.com")
		if recording != wantRecording || account != wantAccount {
			t.Fatalf("totals = %d recording, %d account, want %d and %d", recording, account, wantRecording, wantAccount)
		}
	}
	check(0, 0)

	// A chunk that is refused leaves the totals alone.
	if st, _, _ := appendChunk(s, alice, id, "hr.log", 1, []byte("x"), nil); st != 409 {
		t.Fatalf("wrong offset = %d", st)
	}
	check(0, 0)

	if st, _, body := appendChunk(s, alice, id, "hr.log", 0, []byte("hello"), nil); st != 204 {
		t.Fatalf("chunk = %d %s", st, body)
	}
	check(5, 5)
	if st, _, _ := appendChunk(s, alice, id, "hr.log", 5, []byte("!!"), nil); st != 204 {
		t.Fatalf("second chunk = %d", st)
	}
	if st, _, _ := appendChunk(s, alice, id, "ecg.log", 0, []byte("abc"), nil); st != 204 {
		t.Fatalf("second file = %d", st)
	}
	check(10, 10)

	// Deleting a file takes its bytes off both totals.
	s.expect(204, http.MethodDelete, "/v1/recordings/"+id+"/files/ecg.log", alice, nil, nil)
	check(7, 7)

	// Another account keeps its own total.
	createRecording(s, bob, "bobs", testMeta("bobs", "recording"))
	if st, _, _ := appendChunk(s, bob, "bobs", "hr.log", 0, []byte("four"), nil); st != 204 {
		t.Fatalf("another account's chunk = %d", st)
	}
	check(7, 7)
	if recording, account := storedTotals(t, s, "bobs", "bob@example.com"); recording != 4 || account != 4 {
		t.Fatalf("bob's totals = %d recording, %d account", recording, account)
	}

	// Deleting the recording takes everything it held off the account.
	s.expect(204, http.MethodDelete, recordingsAPI+"/"+id, alice, nil, nil)
	if got := accountStorage(t, s, "alice@example.com"); got != 0 {
		t.Fatalf("alice still holds %d bytes after deleting her recording", got)
	}
	if got := accountStorage(t, s, "bob@example.com"); got != 4 {
		t.Fatalf("bob's total changed to %d", got)
	}
}

// TestQuotaCheckUsesOnlyTheRunningTotal tests the decision on its own: it is a
// comparison against the account total, with no recordings to look at.
func TestQuotaCheckUsesOnlyTheRunningTotal(t *testing.T) {
	t.Setenv("TAIGA_USER_QUOTA_GB", "1")
	quota := int64(1) * 1024 * 1024 * 1024
	if err := quotaCheck(quota, 0); err != nil {
		t.Fatalf("a total at the quota must be allowed: %v", err)
	}
	err := quotaCheck(quota, 1)
	var failure *apiError
	if !errors.As(err, &failure) || failure.Status != http.StatusRequestEntityTooLarge || failure.Code != "storage_quota_exceeded" {
		t.Fatalf("one byte over the quota = %v", err)
	}
	t.Setenv("TAIGA_USER_QUOTA_GB", "0")
	if err := quotaCheck(quota, 1); err != nil {
		t.Fatalf("quota 0 must allow any total: %v", err)
	}
}

// TestQuotaIgnoresTheRecordingsOnDisk shows that the check reads the account total, not
// the account's recordings: bytes the total does not count do not limit an upload.
func TestQuotaIgnoresTheRecordingsOnDisk(t *testing.T) {
	t.Setenv("TAIGA_USER_QUOTA_GB", "1")
	t.Setenv("TAIGA_MAX_RECORDING_MB", "4096")
	s := newServer(t)
	alice := s.tokens["alice"]
	quota := int64(1) * 1024 * 1024 * 1024

	createRecording(s, alice, "uncounted", testMeta("uncounted", "recording"))
	seedStoredBytes(t, s, "uncounted", quota)
	record, err := s.app.FindRecordById("recordings", "uncounted")
	if err != nil {
		t.Fatal(err)
	}
	record.Set("stored_bytes", 0)
	if err := s.app.Save(record); err != nil {
		t.Fatal(err)
	}
	user, err := s.app.FindAuthRecordByEmail("users", "alice@example.com")
	if err != nil {
		t.Fatal(err)
	}
	user.Set("storage_bytes", 0)
	if err := s.app.Save(user); err != nil {
		t.Fatal(err)
	}

	createRecording(s, alice, "next", testMeta("next", "recording"))
	if st, _, body := appendChunk(s, alice, "next", "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("bytes the total does not count must not limit an upload: %d %s", st, body)
	}
}

// TestConcurrentChunksCannotBothExceedTheQuota leaves one byte in the quota and sends
// two chunks at once: one is written, the other is refused.
func TestConcurrentChunksCannotBothExceedTheQuota(t *testing.T) {
	t.Setenv("TAIGA_USER_QUOTA_GB", "1")
	s := newServer(t)
	alice := s.tokens["alice"]
	quota := int64(1) * 1024 * 1024 * 1024

	createRecording(s, alice, "full", testMeta("full", "recording"))
	seedStoredBytes(t, s, "full", quota-1)
	createRecording(s, alice, "race", testMeta("race", "recording"))

	names := []string{"hr.log", "ecg.log"}
	statuses := make([]int, len(names))
	var wait sync.WaitGroup
	for i, name := range names {
		wait.Add(1)
		go func() {
			defer wait.Done()
			statuses[i], _, _ = appendChunk(s, alice, "race", name, 0, []byte("x"), nil)
		}()
	}
	wait.Wait()

	written, refused := 0, 0
	for _, status := range statuses {
		switch status {
		case http.StatusNoContent:
			written++
		case http.StatusRequestEntityTooLarge:
			refused++
		}
	}
	if written != 1 || refused != 1 {
		t.Fatalf("chunk statuses = %v, want one 204 and one 413", statuses)
	}
	if got := accountStorage(t, s, "alice@example.com"); got != quota {
		t.Fatalf("account total = %d, want %d", got, quota)
	}
}

func TestAnalysisFlow(t *testing.T) {
	s := newServer(t)
	alice, bob, worker := s.tokens["alice"], s.tokens["bob"], s.tokens["worker"]
	id := "rec1"
	createRecording(s, alice, id, testMeta(id, "recording"))
	request := map[string]any{"algorithm": "baseline"}

	s.expectCode(409, "recording_incomplete", http.MethodPost, analysesAPI, alice, map[string]any{"recording": id, "algorithm": "baseline"}, nil)
	completeRecording(s, alice, id)
	if status, body := requestAnalysis(s, alice, id, request); status != http.StatusUnprocessableEntity ||
		decode[ErrorResponse](t, body).Data["code"] != "unknown_algorithm" {
		t.Fatalf("unknown algorithm = %d %s", status, body)
	}
	announce(s, "baseline", "1.1.0")
	announce(s, "baseline", "1.2.0")
	if status, _ := requestAnalysis(s, bob, id, request); status != http.StatusBadRequest {
		t.Fatalf("another user's recording = %d, want PocketBase's refused create", status)
	}
	if status, body := requestAnalysis(s, alice, id, map[string]any{"algorithm": "Bad Id"}); status != http.StatusBadRequest ||
		decode[ErrorResponse](t, body).Data["code"] != "invalid_algorithm" {
		t.Fatalf("invalid algorithm = %d %s", status, body)
	}
	if status, body := requestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline", "version": "9.9.9"}); status != http.StatusUnprocessableEntity ||
		decode[ErrorResponse](t, body).Data["code"] != "unknown_algorithm" {
		t.Fatalf("unknown version = %d %s", status, body)
	}

	// Announcing replaces; only the newest version of the last announcement is on offer.
	algorithms := decode[AlgorithmList](t, s.expect(200, http.MethodGet, "/v1/algorithms", alice, nil, nil)).Items
	if len(algorithms) != 1 || algorithms[0].Version != "1.2.0" || len(algorithms[0].Workers) != 1 {
		t.Fatalf("algorithms = %+v", algorithms)
	}

	analysis := mustRequestAnalysis(s, alice, id, request)
	if analysis.Status != "queued" || analysis.Version != "1.2.0" || analysis.Recording != id ||
		analysis.Result != nil || analysis.Summary != nil || analysis.Attempts != 0 || analysis.Params == nil {
		t.Fatalf("analysis = %+v", analysis)
	}
	if again := mustRequestAnalysis(s, alice, id, request); again.ID != analysis.ID {
		t.Fatalf("a repeated request created %s, want %s", again.ID, analysis.ID)
	}
	other := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline", "params": map[string]any{"k": 1}})
	if other.ID == analysis.ID {
		t.Fatal("different params must be a different analysis")
	}

	// Another user sees nothing of it, and a worker only what it holds the lease of.
	s.expect(404, http.MethodGet, analysesAPI+"/"+analysis.ID, bob, nil, nil)
	s.expect(404, http.MethodDelete, analysesAPI+"/"+analysis.ID, bob, nil, nil)
	s.expect(404, http.MethodGet, analysesAPI+"/"+analysis.ID, worker, nil, nil)
	if got := listAnalyses(s, worker, "?filter="+url.QueryEscape(`status="queued"`)); got.TotalItems != 0 {
		t.Fatalf("a worker lists %d queued analyses it does not hold", got.TotalItems)
	}
	if got := listAnalyses(s, alice, ""); got.TotalItems != 2 {
		t.Fatalf("alice sees %d analyses", got.TotalItems)
	}
	if got := listAnalyses(s, bob, ""); got.TotalItems != 0 {
		t.Fatalf("bob sees %d analyses", got.TotalItems)
	}

	// A worker reads the recording only while it holds a lease.
	s.expect(404, http.MethodGet, recordingsAPI+"/"+id, worker, nil, nil)
	s.expect(404, http.MethodGet, "/v1/recordings/"+id+"/files", worker, nil, nil)
	s.expect(404, http.MethodGet, "/v1/recordings/"+id+"/files/hr.log", worker, nil, nil)
	claimed := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, ClaimRequest{Algorithms: []ClaimAlgorithm{{ID: "baseline", Version: "1.2.0"}}}, nil))
	if claimed.Status != "running" || claimed.Attempts != 1 || claimed.ID != analysis.ID {
		t.Fatalf("claimed = %+v", claimed)
	}
	s.expect(200, http.MethodGet, recordingsAPI+"/"+id, worker, nil, nil)
	if held := listAnalyses(s, worker, ""); held.TotalItems != 1 || held.Items[0].ID != claimed.ID {
		t.Fatalf("a worker holds %+v, want only %s", held, claimed.ID)
	}
	if status, read := getAnalysis(s, worker, claimed.ID); status != 200 || read.ID != claimed.ID {
		t.Fatalf("worker read %d %+v", status, read)
	}
	s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files", worker, nil, nil)
	if got := s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files/hr.log", worker, nil, nil); string(got) != "frames" {
		t.Fatalf("worker read %q", got)
	}
	s.expect(404, http.MethodDelete, recordingsAPI+"/"+id, worker, nil, nil)
	s.expect(204, http.MethodPost, "/v1/analyses/claim", worker, ClaimRequest{Algorithms: []ClaimAlgorithm{{ID: "other"}}}, nil)

	// A result must match what was asked.
	bad := validResult("baseline", "1.2.0")
	bad["model"] = "someone-else"
	s.expectCode(422, "invalid_result", http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, bad)}, nil)
	if status, failed := getAnalysis(s, alice, analysis.ID); status != 200 || failed.Status != "failed" || failed.Error == "" || failed.FinishedAt == nil {
		t.Fatalf("a rejected result must fail the analysis: %d %+v", status, failed)
	}
	s.expectCode(409, "not_leased", http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.2.0"))}, nil)
	// The lease is gone, so the worker can no longer read the analysis either.
	s.expect(404, http.MethodGet, analysesAPI+"/"+analysis.ID, worker, nil, nil)

	// Asking again for a failed analysis replaces it with a fresh queued one.
	retry := mustRequestAnalysis(s, alice, id, request)
	if retry.Status != "queued" || retry.ID == analysis.ID || retry.Attempts != 0 {
		t.Fatalf("retry = %+v", retry)
	}
	s.expect(404, http.MethodGet, analysesAPI+"/"+analysis.ID, alice, nil, nil)

	// Complete the retry through the queue.
	next := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil))
	if next.ID != other.ID && next.ID != retry.ID {
		t.Fatalf("claimed %s", next.ID)
	}
	if next.ID != retry.ID {
		// The older queued analysis goes first; finish it and take the retry.
		s.expect(200, http.MethodPost, "/v1/analyses/"+next.ID+"/fail", worker, FailRequest{Error: "no"}, nil)
		next = decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil))
	}
	done := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/"+next.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.2.0"))}, nil))
	if done.Status != "succeeded" || done.FinishedAt == nil {
		t.Fatalf("done = %+v", done)
	}
	if status, full := getAnalysis(s, alice, next.ID); status != 200 || full.Result == nil || full.Summary == nil {
		t.Fatalf("full = %d %+v", status, full)
	} else if summary, ok := full.Summary.(map[string]any); !ok {
		t.Fatalf("summary = %v", full.Summary)
	} else if _, ok := summary["stages"]; !ok {
		t.Fatalf("summary = %v", summary)
	}
	s.expect(404, http.MethodGet, recordingsAPI+"/"+id, worker, nil, nil) // lease is over
	s.expect(404, http.MethodGet, analysesAPI+"/"+next.ID, worker, nil, nil)
	if got := listAnalyses(s, worker, ""); got.TotalItems != 0 {
		t.Fatalf("a worker still lists %d analyses after the lease", got.TotalItems)
	}

	// A list carries the same record, result included.
	list := listAnalyses(s, alice, "?filter="+url.QueryEscape(`status="succeeded"`))
	if list.TotalItems != 1 || list.Items[0].Result == nil || list.Items[0].Summary == nil {
		t.Fatalf("list = %+v", list)
	}
	ofRecording := listAnalyses(s, alice, "?filter="+url.QueryEscape(`recording="`+id+`" && status="succeeded"`))
	if ofRecording.TotalItems != 1 {
		t.Fatalf("analyses of the recording = %+v", ofRecording)
	}

	// Deleting the recording takes its analyses along.
	s.expect(204, http.MethodDelete, recordingsAPI+"/"+id, alice, nil, nil)
	if got := listAnalyses(s, alice, ""); got.TotalItems != 0 {
		t.Fatalf("%d analyses survived their recording", got.TotalItems)
	}
}

func mustJSON(t *testing.T, value any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// TestConcurrentIdenticalAnalysisRequestsShareOneAnalysis sends the same request
// several times at once. Asking again for an analysis is meant to be one analysis, so
// every answer must name the same row even when the requests pass the existence check
// together and the unique index is what keeps the duplicate out.
func TestConcurrentIdenticalAnalysisRequestsShareOneAnalysis(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	completeRecording(s, alice, "concurrent")
	announce(s, "baseline", "1.0.0")

	const requests = 8
	type answer struct {
		status int
		body   []byte
	}
	answers := make([]answer, requests)
	var wait sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < requests; i++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			status, _, body := s.call(http.MethodPost, analysesAPI, alice, map[string]any{
				"recording": "concurrent", "algorithm": "baseline",
			}, nil)
			answers[i] = answer{status: status, body: body}
		}()
	}
	close(start)
	wait.Wait()

	ids := map[string]bool{}
	for i, got := range answers {
		if got.status != http.StatusOK {
			t.Fatalf("request %d = %d %s", i, got.status, got.body)
		}
		ids[decode[AnalysisView](t, got.body).ID] = true
	}
	if len(ids) != 1 {
		t.Fatalf("the concurrent requests named %d analyses, want 1: %v", len(ids), ids)
	}
	if listed := listAnalyses(s, alice, ""); listed.TotalItems != 1 {
		t.Fatalf("%d analyses were stored, want 1", listed.TotalItems)
	}
}

func TestAnalysisParamsAreCheckedAgainstTheAnnouncedSchema(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	completeRecording(s, alice, "rec1")
	announceWithSchema(s, "typed", "1.0.0")

	if analysis := mustRequestAnalysis(s, alice, "rec1", map[string]any{
		"algorithm": "typed",
		"params":    map[string]any{"window": 5, "label": "a", "weight": nil},
	}); analysis.Status != "queued" {
		t.Fatalf("valid params = %+v", analysis)
	}
	for name, params := range map[string]map[string]any{
		"unknown parameter":  {"nope": 1},
		"wrong type":         {"window": "five"},
		"below the minimum":  {"window": 0},
		"above the maximum":  {"window": 22},
		"not an enum value":  {"label": "c"},
		"negative weight":    {"weight": -1},
		"fractional integer": {"window": 1.5},
	} {
		status, body := requestAnalysis(s, alice, "rec1", map[string]any{"algorithm": "typed", "params": params})
		var failure ErrorResponse
		if status != http.StatusUnprocessableEntity || json.Unmarshal(body, &failure) != nil || failure.Data["code"] != "invalid_params" {
			t.Errorf("%s = %d %s, want 422 invalid_params", name, status, body)
		}
	}
	for name, params := range map[string]any{
		"not an object": []any{1, 2},
	} {
		status, body := requestAnalysis(s, alice, "rec1", map[string]any{"algorithm": "typed", "params": params})
		if status != http.StatusBadRequest {
			t.Errorf("%s = %d %s, want 400", name, status, body)
		}
	}
	if analysis := mustRequestAnalysis(s, alice, "rec1", map[string]any{"algorithm": "typed", "params": nil}); analysis.Status != "queued" {
		t.Fatalf("omitted params = %+v", analysis)
	}
}

func TestLeasesAndRetries(t *testing.T) {
	t.Setenv("TAIGA_JOB_MAX_ATTEMPTS", "2")
	s := newServer(t)
	alice, worker := s.tokens["alice"], s.tokens["worker"]
	id := "rec1"
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})

	expire := func() {
		t.Helper()
		if _, err := s.app.DB().NewQuery("UPDATE analyses SET lease_until = {:past} WHERE id = {:id}").
			Bind(dbx.Params{"past": pbDateMillis(time.Now().Add(-time.Hour).UnixMilli()), "id": analysis.ID}).Execute(); err != nil {
			t.Fatal(err)
		}
	}
	state := func() AnalysisView {
		t.Helper()
		status, got := getAnalysis(s, alice, analysis.ID)
		if status != 200 {
			t.Fatalf("analysis %s = %d", analysis.ID, status)
		}
		return got
	}

	// A retryable failure waits for its backoff, a lease that ran out is requeued.
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	if got := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/"+analysis.ID+"/fail", worker, FailRequest{Error: "flaky", Retryable: true}, nil)); got.Status != "queued" || got.Error != "flaky" {
		t.Fatalf("retryable failure = %+v", got)
	}
	s.expect(204, http.MethodPost, "/v1/analyses/claim", worker, nil, nil) // backoff has not passed
	if _, err := s.app.DB().NewQuery("UPDATE analyses SET available_at = {:past} WHERE id = {:id}").
		Bind(dbx.Params{"past": pbDateMillis(time.Now().Add(-time.Minute).UnixMilli()), "id": analysis.ID}).Execute(); err != nil {
		t.Fatal(err)
	}
	if got := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)); got.Attempts != 2 {
		t.Fatalf("second claim = %+v", got)
	}

	// The lease runs out on the last attempt: the analysis fails instead of looping.
	expire()
	s.expectCode(409, "not_leased", http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.0.0"))}, nil)
	s.expect(204, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	if got := state(); got.Status != "failed" || got.Error != "lease expired too many times" {
		t.Fatalf("after expiry = %+v", got)
	}

	// A user deleting a running analysis cancels it: the worker's report is 404.
	again := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	s.expect(204, http.MethodDelete, analysesAPI+"/"+again.ID, alice, nil, nil)
	s.expectCode(404, "not_found", http.MethodPost, "/v1/analyses/"+again.ID+"/fail", worker, FailRequest{Error: "x"}, nil)
}

func TestLeaseRenewal(t *testing.T) {
	s := newServer(t)
	s.account("workers", "worker2@example.com", "worker-2")
	alice, worker, other := s.tokens["alice"], s.tokens["worker"], s.tokens["worker2"]
	id := "rec1"
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})
	lease := func() string {
		t.Helper()
		record, err := s.app.FindRecordById("analyses", analysis.ID)
		if err != nil {
			t.Fatal(err)
		}
		return record.GetString("lease_until")
	}

	// A user never renews, and nobody can renew work that is not leased to them.
	s.expect(403, http.MethodPost, "/v1/analyses/"+analysis.ID+"/lease", alice, LeaseRequest{LeaseSeconds: 60}, nil)
	s.expectCode(409, "not_leased", http.MethodPost, "/v1/analyses/"+analysis.ID+"/lease", worker, LeaseRequest{LeaseSeconds: 60}, nil)
	s.expectCode(404, "not_found", http.MethodPost, "/v1/analyses/missing/lease", worker, nil, nil)

	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, ClaimRequest{LeaseSeconds: 60}, nil)
	before := lease()

	// Another worker holding nothing of this analysis cannot take it over.
	s.expectCode(409, "not_leased", http.MethodPost, "/v1/analyses/"+analysis.ID+"/lease", other, LeaseRequest{LeaseSeconds: 600}, nil)
	if got := lease(); got != before {
		t.Fatalf("a refused renewal changed the lease from %q to %q", before, got)
	}

	// The worker that holds it extends the lease.
	renewed := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/"+analysis.ID+"/lease", worker, LeaseRequest{LeaseSeconds: 600}, nil))
	if renewed.ID != analysis.ID || renewed.Status != "running" {
		t.Fatalf("renewed = %+v", renewed)
	}
	if after := lease(); after <= before {
		t.Fatalf("lease was not extended: %q -> %q", before, after)
	}

	// An expired lease cannot be extended any more.
	if _, err := s.app.DB().NewQuery("UPDATE analyses SET lease_until = {:past} WHERE id = {:id}").
		Bind(dbx.Params{"past": pbDateMillis(time.Now().Add(-time.Hour).UnixMilli()), "id": analysis.ID}).Execute(); err != nil {
		t.Fatal(err)
	}
	s.expectCode(409, "not_leased", http.MethodPost, "/v1/analyses/"+analysis.ID+"/lease", worker, LeaseRequest{LeaseSeconds: 600}, nil)
}

// createToken makes an access token for the account and returns what the creation
// answered: the only time the token itself is visible.
func createToken(s *server, account, name string) TokenView {
	s.t.Helper()
	status, header, body := s.call(http.MethodPost, "/v1/tokens", s.tokens[account], TokenRequest{Name: name}, nil)
	if status != 201 {
		s.t.Fatalf("create token = %d %s", status, body)
	}
	created := decode[TokenView](s.t, body)
	if header.Get("Location") != "/v1/tokens/"+created.ID {
		s.t.Fatalf("create token location = %q", header.Get("Location"))
	}
	return created
}

// TestAccessTokensAreReadOnly is the contract of a personal access token: it reads the
// owner's recordings, analyses and results, sees nothing of another account, is refused
// on every write, and stops working when it is revoked.
func TestAccessTokensAreReadOnly(t *testing.T) {
	s := newServer(t)
	alice, bob, worker := s.tokens["alice"], s.tokens["bob"], s.tokens["worker"]
	id := "rec1"
	createRecording(s, alice, id, testMeta(id, "stopped"))
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	seal(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	s.expect(200, http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker,
		CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.0.0"))}, nil)

	created := createToken(s, "alice", "my assistant")
	if len(created.Token) < 40 || created.Name != "my assistant" ||
		created.ID == "" || created.Created == "" {
		t.Fatalf("created = %+v", created)
	}
	// The token is a PocketBase static token of the record: the record is its identity,
	// and the list never carries the token again.
	aliceAccount, err := s.app.FindAuthRecordByEmail("users", "alice@example.com")
	if err != nil {
		t.Fatal(err)
	}
	record, err := s.app.FindRecordById("access_tokens", created.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !record.Collection().IsAuth() {
		t.Fatalf("access_tokens is not an auth collection")
	}
	if record.GetString("hint") != created.Token[len(created.Token)-4:] || record.GetString("owner") != aliceAccount.Id {
		t.Fatalf("stored token = %+v", record)
	}
	if _, err := s.app.FindAuthRecordByToken(created.Token, core.TokenTypeAuth); err != nil {
		t.Fatalf("the created token is not accepted: %v", err)
	}
	list := decode[TokenList](t, s.expect(200, http.MethodGet, "/v1/tokens", alice, nil, nil))
	if len(list.Items) != 1 || list.Items[0].ID != created.ID || list.Items[0].Token != "" {
		t.Fatalf("list = %+v", list)
	}
	s.expectCode(400, "invalid_body", http.MethodPost, "/v1/tokens", alice, TokenRequest{Name: " "}, nil)
	if got := decode[TokenList](t, s.expect(200, http.MethodGet, "/v1/tokens", bob, nil, nil)); len(got.Items) != 0 {
		t.Fatalf("bob lists %+v", got)
	}

	// The token reads the owner's data through the record API.
	recordings := listRecordings(s, created.Token, "")
	if recordings.TotalItems != 1 || recordings.Items[0].ID != id {
		t.Fatalf("recordings through the token = %+v", recordings)
	}
	if got := decode[recordingRecord](t, s.expect(200, http.MethodGet, recordingsAPI+"/"+id, created.Token, nil, nil)); !got.Complete {
		t.Fatalf("recording through the token = %+v", got)
	}
	analyses := listAnalyses(s, created.Token, "")
	if analyses.TotalItems != 1 || analyses.Items[0].ID != analysis.ID || analyses.Items[0].Result == nil {
		t.Fatalf("analyses through the token = %+v", analyses)
	}
	if status, got := getAnalysis(s, created.Token, analysis.ID); status != 200 || got.Summary == nil || got.Result == nil {
		t.Fatalf("analysis through the token = %d %+v", status, got)
	}
	if got := listAnalyses(s, created.Token, "?filter="+url.QueryEscape(`recording="`+id+`"`)); got.TotalItems != 1 {
		t.Fatalf("recording analyses through the token = %+v", got)
	}

	// Another account's recording and analysis stay not found, and its own token sees nothing.
	bobToken := createToken(s, "bob", "bob's script")
	s.expect(404, http.MethodGet, recordingsAPI+"/"+id, bobToken.Token, nil, nil)
	s.expect(404, http.MethodGet, analysesAPI+"/"+analysis.ID, bobToken.Token, nil, nil)
	if got := listRecordings(s, bobToken.Token, ""); got.TotalItems != 0 {
		t.Fatalf("bob's token sees %d recordings", got.TotalItems)
	}

	// Every write route refuses it: a record create rule refuses the collection, an
	// update or delete rule hides the record, and the custom routes refuse the role.
	for _, call := range []struct {
		method, path string
		body         any
		want         int
	}{
		{http.MethodPost, recordingsAPI, map[string]any{"id": "new-recording", "meta": testMeta("new-recording", "recording")}, http.StatusBadRequest},
		{http.MethodPatch, recordingsAPI + "/" + id, map[string]bool{"complete": true}, http.StatusNotFound},
		{http.MethodDelete, recordingsAPI + "/" + id, nil, http.StatusNotFound},
		{http.MethodPost, analysesAPI, map[string]any{"recording": id, "algorithm": "baseline"}, http.StatusBadRequest},
		{http.MethodDelete, analysesAPI + "/" + analysis.ID, nil, http.StatusNotFound},
		{http.MethodPatch, "/v1/recordings/" + id + "/files/hr.log", []byte("more"), http.StatusForbidden},
		{http.MethodDelete, "/v1/recordings/" + id + "/files/hr.log", nil, http.StatusForbidden},
		{http.MethodPost, "/v1/tokens", TokenRequest{Name: "another"}, http.StatusForbidden},
		{http.MethodDelete, "/v1/tokens/" + created.ID, nil, http.StatusForbidden},
		{http.MethodPut, "/v1/workers/me", map[string]string{"name": "x"}, http.StatusForbidden},
		{http.MethodPost, "/v1/analyses/claim", nil, http.StatusForbidden},
		{http.MethodPost, "/v1/analyses/" + analysis.ID + "/lease", nil, http.StatusForbidden},
		{http.MethodPost, "/v1/analyses/" + analysis.ID + "/complete", map[string]any{"result": map[string]any{}}, http.StatusForbidden},
		{http.MethodPost, "/v1/analyses/" + analysis.ID + "/fail", map[string]string{"error": "x"}, http.StatusForbidden},
		{http.MethodGet, "/v1/recordings/" + id + "/files", nil, http.StatusForbidden},
		{http.MethodGet, "/v1/recordings/" + id + "/files/hr.log", nil, http.StatusForbidden},
		{http.MethodGet, "/v1/algorithms", nil, http.StatusForbidden},
		{http.MethodGet, "/v1/events", nil, http.StatusForbidden},
	} {
		status, _, body := s.call(call.method, call.path, created.Token, call.body, nil)
		if status != call.want {
			t.Errorf("%s %s through the token = %d, want %d: %s", call.method, call.path, status, call.want, body)
		}
	}
	// PocketBase's auth routes do not turn a read-only token into a login: its collection
	// has no auth rule, and the users route refuses a token of another collection.
	for _, path := range []string{
		"/api/collections/access_tokens/auth-refresh",
		"/api/collections/users/auth-refresh",
	} {
		if status, _, body := s.call(http.MethodPost, path, created.Token, map[string]any{}, nil); status != 403 {
			t.Errorf("POST %s with an access token = %d %s", path, status, body)
		}
	}
	// A HEAD answer has no body, so only its status can refuse the raw files.
	if status, _, _ := s.call(http.MethodHead, "/v1/recordings/"+id+"/files/hr.log", created.Token, nil, nil); status != 403 {
		t.Fatalf("HEAD through the token = %d", status)
	}
	// None of that changed anything.
	if got := decode[TokenList](t, s.expect(200, http.MethodGet, "/v1/tokens", alice, nil, nil)); len(got.Items) != 1 {
		t.Fatalf("the refused writes changed the token list: %+v", got)
	}
	s.expect(200, http.MethodGet, recordingsAPI+"/"+id, alice, nil, nil)

	// A token belongs to one account: another account cannot revoke it.
	s.expectCode(404, "not_found", http.MethodDelete, "/v1/tokens/"+created.ID, bob, nil, nil)
	s.expect(204, http.MethodDelete, "/v1/tokens/"+created.ID, alice, nil, nil)
	s.expect(401, http.MethodGet, "/v1/recordings/x/files", created.Token, nil, nil)
	s.expect(404, http.MethodGet, analysesAPI+"/"+analysis.ID, created.Token, nil, nil)
	s.expect(404, http.MethodDelete, "/v1/tokens/"+created.ID, alice, nil, nil)
}

func TestAllErrorsUseOneShape(t *testing.T) {
	s := newServer(t)
	for _, call := range []struct{ method, path, token string }{
		{http.MethodGet, "/v1/recordings/x/files", ""},
		{http.MethodGet, "/v1/docs/not-an-asset", ""},
		{http.MethodGet, "/v1/recordings/x/files", s.tokens["worker"]},
		{http.MethodGet, recordingsAPI + "/missing", s.tokens["alice"]},
		{http.MethodGet, analysesAPI + "/missing", s.tokens["alice"]},
		{http.MethodPatch, "/v1/recordings/x/files/hr.log", s.tokens["alice"]},
		{http.MethodGet, recordingsAPI + "?filter=" + url.QueryEscape("("), s.tokens["alice"]},
		{http.MethodPost, "/v1/analyses/claim", s.tokens["alice"]},
	} {
		status, header, body := s.call(call.method, call.path, call.token, nil, nil)
		var failure ErrorResponse
		if status < 400 || json.Unmarshal(body, &failure) != nil || failure.Status != status ||
			failure.Message == "" || failure.Data == nil ||
			!strings.HasPrefix(header.Get("Content-Type"), "application/json") {
			t.Errorf("%s %s = %d %s", call.method, call.path, status, body)
		}
	}
}

func TestVersionComparison(t *testing.T) {
	for _, c := range []struct {
		a, b string
		want int
	}{
		{"1.2.0", "1.10.0", -1}, {"2.0", "1.9.9", 1}, {"1.0.0", "1.0.0", 0}, {"1.0", "1.0.0", 0}, {"0.1.0", "0.1.1", -1},
	} {
		if got := compareVersions(c.a, c.b); got != c.want {
			t.Errorf("compareVersions(%q, %q) = %d, want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestDefaultVersionIgnoresWorkersThatWentAway(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	completeRecording(s, alice, "rec1")

	// An old announcement offers a newer version than the live worker does.
	announce(s, "baseline", "2.0.0")
	if _, err := s.app.DB().NewQuery("UPDATE workers SET last_seen = {:old}").
		Bind(dbx.Params{"old": pbDateMillis(time.Now().Add(-24 * time.Hour).UnixMilli())}).Execute(); err != nil {
		t.Fatal(err)
	}
	s.account("workers", "other@example.com", "worker-2")
	s.expect(204, http.MethodPut, "/v1/workers/me", s.tokens["other"], WorkerAnnouncement{
		Name: "worker-2", Version: "0.1.0",
		Algorithms: []AlgorithmView{{ID: "baseline", Version: "1.0.0", Name: "baseline", License: "MIT"}},
	}, nil)

	got := mustRequestAnalysis(s, alice, "rec1", map[string]any{"algorithm": "baseline"})
	if got.Version != "1.0.0" {
		t.Fatalf("default version = %s, want the live worker's 1.0.0", got.Version)
	}
	// Asking for the old one by name still queues it: its worker may come back.
	mustRequestAnalysis(s, alice, "rec1", map[string]any{"algorithm": "baseline", "version": "2.0.0"})
}

// stream is an open GET /v1/events connection, read by a goroutine so a test can wait for
// events with a timeout.
type stream struct {
	t      *testing.T
	events chan string
	cancel func()
	done   chan struct{}
}

func openStream(t *testing.T, s *server, token string) *stream {
	t.Helper()
	httpServer := httptest.NewServer(s.mux)
	t.Cleanup(httpServer.Close)
	ctx, cancel := context.WithCancel(context.Background())
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, httpServer.URL+"/v1/events", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	if response.StatusCode != 200 || !strings.HasPrefix(response.Header.Get("Content-Type"), "text/event-stream") {
		cancel()
		t.Fatalf("events = %d %s", response.StatusCode, response.Header.Get("Content-Type"))
	}
	st := &stream{t: t, events: make(chan string, 32), cancel: cancel, done: make(chan struct{})}
	go func() {
		defer close(st.done)
		defer response.Body.Close()
		reader := bufio.NewReader(response.Body)
		name := ""
		for {
			line, err := reader.ReadString('\n')
			if err != nil {
				return
			}
			line = strings.TrimRight(line, "\r\n")
			switch {
			case strings.HasPrefix(line, "event: "):
				name = strings.TrimPrefix(line, "event: ")
			case strings.HasPrefix(line, "data: "):
				st.events <- name + " " + strings.TrimPrefix(line, "data: ")
			case strings.HasPrefix(line, ":"):
				st.events <- line
			}
		}
	}()
	t.Cleanup(cancel)
	if first := st.next(); first != ": connected" {
		t.Fatalf("first line = %q", first)
	}
	return st
}

func (st *stream) next() string {
	st.t.Helper()
	select {
	case line := <-st.events:
		return line
	case <-time.After(5 * time.Second):
		st.t.Fatal("no event within 5 seconds")
		return ""
	}
}

// nothing asserts that no event arrives for a moment.
func (st *stream) nothing() {
	st.t.Helper()
	select {
	case line := <-st.events:
		st.t.Fatalf("unexpected event %q", line)
	case <-time.After(300 * time.Millisecond):
	}
}

func TestEventStream(t *testing.T) {
	s := newServer(t)
	alice, bob, worker := s.tokens["alice"], s.tokens["bob"], s.tokens["worker"]
	s.expect(401, http.MethodGet, "/v1/events", "", nil, nil)

	aliceEvents, bobEvents, workerEvents := openStream(t, s, alice), openStream(t, s, bob), openStream(t, s, worker)
	if events.count() != 3 {
		t.Fatalf("%d subscribers", events.count())
	}

	id := "rec1"
	createRecording(s, alice, id, testMeta(id, "recording"))
	if got := aliceEvents.next(); got != `recording {"id":"rec1","change":"created"}` {
		t.Fatalf("created event = %q", got)
	}
	replaceMeta(s, alice, id, testMeta(id, "stopped"))
	if got := aliceEvents.next(); got != `recording {"id":"rec1","change":"updated"}` {
		t.Fatalf("meta change event = %q", got)
	}
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	aliceEvents.nothing() // a chunk is not a change to what clients show
	seal(s, alice, id)
	if got := aliceEvents.next(); got != `recording {"id":"rec1","change":"updated"}` {
		t.Fatalf("sealed event = %q", got)
	}
	drain := func(st *stream) {
		for {
			select {
			case <-st.events:
			case <-time.After(300 * time.Millisecond):
				return
			}
		}
	}
	drain(aliceEvents)

	// A user only hears about their own things; a worker only about queued work.
	announce(s, "baseline", "1.0.0")
	analysis := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline"})
	queued := `analysis {"id":"` + analysis.ID + `","recording":"rec1","status":"queued"}`
	if got := aliceEvents.next(); got != queued {
		t.Fatalf("alice = %q, want %q", got, queued)
	}
	if got := workerEvents.next(); got != queued {
		t.Fatalf("worker = %q, want %q", got, queued)
	}
	bobEvents.nothing()

	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	if got := aliceEvents.next(); got != `analysis {"id":"`+analysis.ID+`","recording":"rec1","status":"running"}` {
		t.Fatalf("running event = %q", got)
	}
	workerEvents.nothing() // running is not work to claim
	s.expect(200, http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.0.0"))}, nil)
	if got := aliceEvents.next(); got != `analysis {"id":"`+analysis.ID+`","recording":"rec1","status":"succeeded"}` {
		t.Fatalf("succeeded event = %q", got)
	}

	// A retryable failure is queued again, which is work for a worker.
	again := mustRequestAnalysis(s, alice, id, map[string]any{"algorithm": "baseline", "params": map[string]int{"k": 1}})
	workerEvents.next()
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	s.expect(200, http.MethodPost, "/v1/analyses/"+again.ID+"/fail", worker, FailRequest{Error: "flaky", Retryable: true}, nil)
	if got := workerEvents.next(); !strings.Contains(got, again.ID) || !strings.Contains(got, `"queued"`) {
		t.Fatalf("requeue event = %q", got)
	}

	// Deleting an analysis and then the recording.
	drain(aliceEvents)
	s.expect(204, http.MethodDelete, analysesAPI+"/"+analysis.ID, alice, nil, nil)
	if got := aliceEvents.next(); got != `analysis {"id":"`+analysis.ID+`","recording":"rec1","status":"deleted"}` {
		t.Fatalf("delete event = %q", got)
	}
	s.expect(204, http.MethodDelete, recordingsAPI+"/"+id, alice, nil, nil)
	for aliceEvents.next() != `recording {"id":"rec1","change":"deleted"}` {
	}
	bobEvents.nothing()

	// Disconnecting releases the subscription.
	aliceEvents.cancel()
	<-aliceEvents.done
	deadline := time.Now().Add(2 * time.Second)
	for events.count() != 2 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if events.count() != 2 {
		t.Fatalf("%d subscribers after a disconnect, want 2", events.count())
	}
}

func TestEventStreamKeepalive(t *testing.T) {
	old := eventKeepalive
	eventKeepalive = 50 * time.Millisecond
	t.Cleanup(func() { eventKeepalive = old })
	s := newServer(t)
	st := openStream(t, s, s.tokens["alice"])
	if got := st.next(); got != ": keepalive" {
		t.Fatalf("got %q", got)
	}
}

func TestSlowSubscriberIsDropped(t *testing.T) {
	hub := &eventHub{subs: map[*subscriber]struct{}{}}
	sub := hub.subscribe(roleUser, "u1")
	for i := 0; i <= subscriberBuffer; i++ {
		hub.toUser("u1", eventRecording, RecordingEvent{ID: "x", Change: "updated"})
	}
	if hub.count() != 0 {
		t.Fatal("a subscriber that cannot keep up must be dropped so it reconnects")
	}
	for range sub.ch { // the channel is closed after what it held
	}
	hub.unsubscribe(sub) // and unsubscribing again is harmless
}
