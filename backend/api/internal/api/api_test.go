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
	status, _, body := s.call(http.MethodPost, "/v1/auth/login", "", map[string]string{"email": email, "password": "password123"}, nil)
	if status != 200 {
		s.t.Fatalf("login %s: %d %s", email, status, body)
	}
	var out TokenResponse
	if err := json.Unmarshal(body, &out); err != nil {
		s.t.Fatal(err)
	}
	s.tokens[strings.SplitN(email, "@", 2)[0]] = out.Token
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

func (s *server) expectCode(want int, code, method, path, token string, body any, headers map[string]string) {
	s.t.Helper()
	data := s.expect(want, method, path, token, body, headers)
	var failure ErrorResponse
	if err := json.Unmarshal(data, &failure); err != nil || failure.Code != code {
		s.t.Fatalf("%s %s: error code = %q (%v), want %q: %s", method, path, failure.Code, err, code, data)
	}
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
	s.expect(200, http.MethodPut, "/v1/recordings/"+id, token, testMeta(id, "stopped"), nil)
	status, _, body := appendChunk(s, token, id, "hr.log", 0, []byte("frames"), nil)
	if status != 204 {
		s.t.Fatalf("append: %d %s", status, body)
	}
	s.expect(200, http.MethodPatch, "/v1/recordings/"+id, token, map[string]bool{"complete": true}, nil)
}

func announce(s *server, id, version string) {
	s.t.Helper()
	s.expect(204, http.MethodPut, "/v1/workers/me", s.tokens["worker"], WorkerAnnouncement{
		Name: "worker-1", Version: "0.1.0",
		Algorithms: []AlgorithmView{{ID: id, Version: version, Name: id, License: "MIT"}},
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
	s.expectCode(401, "unauthorized", http.MethodPost, "/v1/auth/login", "", map[string]string{"email": "alice@example.com", "password": "wrong"}, nil)
	s.expectCode(400, "invalid_body", http.MethodPost, "/v1/auth/login", "", map[string]string{"email": "alice@example.com"}, nil)
	s.expectCode(401, "unauthorized", http.MethodGet, "/v1/me", "", nil, nil)
	s.expectCode(401, "unauthorized", http.MethodGet, "/v1/me", "not-a-token", nil, nil)

	me := decode[MeResponse](t, s.expect(200, http.MethodGet, "/v1/me", s.tokens["alice"], nil, nil))
	if me.Role != roleUser || me.Email != "alice@example.com" {
		t.Fatalf("me = %+v", me)
	}
	worker := decode[MeResponse](t, s.expect(200, http.MethodGet, "/v1/me", s.tokens["worker"], nil, nil))
	if worker.Role != roleWorker {
		t.Fatalf("worker me = %+v", worker)
	}
	refreshed := decode[TokenResponse](t, s.expect(200, http.MethodPost, "/v1/auth/refresh", s.tokens["alice"], nil, nil))
	s.expect(200, http.MethodGet, "/v1/me", refreshed.Token, nil, nil)

	// A user cannot use worker endpoints and a worker cannot use user ones.
	s.expectCode(403, "forbidden", http.MethodPost, "/v1/analyses/claim", s.tokens["alice"], nil, nil)
	s.expectCode(403, "forbidden", http.MethodGet, "/v1/recordings", s.tokens["worker"], nil, nil)
}

func TestLoginIsThrottled(t *testing.T) {
	s := newServer(t)
	t.Cleanup(func() { logins.failures = map[string][]time.Time{} })
	for i := 0; i < loginMaxFailures; i++ {
		s.expect(401, http.MethodPost, "/v1/auth/login", "", map[string]string{"email": "bob@example.com", "password": "nope"}, nil)
	}
	s.expectCode(429, "too_many_attempts", http.MethodPost, "/v1/auth/login", "", map[string]string{"email": "bob@example.com", "password": "password123"}, nil)
}

func TestPocketBaseRecordAPIIsClosed(t *testing.T) {
	s := newServer(t)
	for _, path := range []string{
		"/api/collections/recordings/records",
		"/api/collections/analyses/records",
		"/api/collections/users/records",
		"/api/collections/workers/records",
	} {
		status, _, _ := s.call(http.MethodGet, path, s.tokens["alice"], nil, nil)
		if status != 403 && status != 404 {
			t.Errorf("GET %s = %d, want it closed", path, status)
		}
	}
	for _, collection := range []string{"users", "workers"} {
		status, _, _ := s.call(http.MethodPost, "/api/collections/"+collection+"/auth-with-password", "",
			map[string]string{"identity": "alice@example.com", "password": "password123"}, nil)
		if status == 200 {
			t.Errorf("PocketBase password login for %s still works", collection)
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

	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "fetch_pending"), nil)
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	s.expectCode(409, "recording_in_progress", http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil)

	// The tracker handed the rest over: the recording is finished and can be sealed.
	s.expect(200, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "stopped"), nil)
	done := decode[RecordingView](t, s.expect(200, http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil))
	if !done.Complete || done.State != "stopped" {
		t.Fatalf("complete = %+v", done)
	}
}

func TestRecordingLifecycle(t *testing.T) {
	s := newServer(t)
	alice, bob := s.tokens["alice"], s.tokens["bob"]
	id := "20261101_220000-ab12"

	status, header, body := s.call(http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)
	if status != 201 || header.Get("Location") != "/v1/recordings/"+id {
		t.Fatalf("create = %d %v %s", status, header, body)
	}
	created := decode[RecordingView](t, body)
	if created.ID != id || created.State != "recording" || created.Complete || created.Device != "Polar H10 X" || created.EndedAt != nil {
		t.Fatalf("created = %+v", created)
	}
	// meta is returned as it was sent, including fields this version does not know.
	extra := testMeta(id, "recording")
	extra["future_field"] = map[string]any{"a": 1}
	var echoed map[string]any
	if err := json.Unmarshal(decode[RecordingView](t, s.expect(200, http.MethodPut, "/v1/recordings/"+id, alice, extra, nil)).Meta, &echoed); err != nil ||
		echoed["future_field"] == nil || echoed["sensor"].(map[string]any)["kind"] != "polar-h10" {
		t.Fatalf("meta not returned verbatim: %v %v", echoed, err)
	}

	// Creating again replaces the metadata; another account gets no hint but id_taken.
	s.expect(200, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)
	s.expectCode(409, "id_taken", http.MethodPut, "/v1/recordings/"+id, bob, testMeta(id, "recording"), nil)
	s.expectCode(404, "not_found", http.MethodGet, "/v1/recordings/"+id, bob, nil, nil)
	s.expectCode(404, "not_found", http.MethodDelete, "/v1/recordings/"+id, bob, nil, nil)

	// Validation.
	s.expectCode(422, "invalid_meta", http.MethodPut, "/v1/recordings/other-id", alice, testMeta(id, "recording"), nil)
	s.expectCode(422, "invalid_meta", http.MethodPut, "/v1/recordings/x1", alice, map[string]string{"format": "nope"}, nil)
	s.expectCode(400, "invalid_id", http.MethodPut, "/v1/recordings/bad.id", alice, testMeta("bad.id", "recording"), nil)

	// Completing needs a finished recording with data.
	s.expectCode(409, "recording_in_progress", http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil)
	s.expect(200, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "stopped"), nil)
	s.expectCode(422, "no_data", http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil)
	s.expectCode(400, "invalid_body", http.MethodPatch, "/v1/recordings/"+id, alice, map[string]string{"x": "y"}, nil)
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	done := decode[RecordingView](t, s.expect(200, http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil))
	if !done.Complete || done.SizeBytes != 6 || done.State != "stopped" || done.EndedAt == nil || done.DurationS != 3600 {
		t.Fatalf("complete = %+v", done)
	}
	s.expect(200, http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil) // idempotent
	s.expectCode(409, "recording_complete", http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": false}, nil)
	s.expectCode(409, "recording_complete", http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "stopped"), nil)

	// Lists are scoped to the owner and filterable.
	list := decode[RecordingList](t, s.expect(200, http.MethodGet, "/v1/recordings", alice, nil, nil))
	if list.Total != 1 || len(list.Items) != 1 || list.Limit != 50 {
		t.Fatalf("list = %+v", list)
	}
	if got := decode[RecordingList](t, s.expect(200, http.MethodGet, "/v1/recordings", bob, nil, nil)); got.Total != 0 {
		t.Fatalf("bob sees %d recordings", got.Total)
	}
	for query, want := range map[string]int{
		"?complete=true": 1, "?complete=false": 0, "?state=stopped": 1, "?state=recording": 0,
		"?from=2026-11-01": 1, "?from=2027-01-01": 0, "?to=2026-11-30": 1, "?sort=duration_s&limit=1": 1,
	} {
		if got := decode[RecordingList](t, s.expect(200, http.MethodGet, "/v1/recordings"+query, alice, nil, nil)); len(got.Items) != want {
			t.Errorf("%s: %d items, want %d", query, len(got.Items), want)
		}
	}
	for _, query := range []string{"?sort=bad", "?state=bad", "?complete=maybe", "?limit=0", "?limit=500", "?offset=-1", "?from=yesterday"} {
		s.expectCode(400, "invalid_query", http.MethodGet, "/v1/recordings"+query, alice, nil, nil)
	}

	s.expect(204, http.MethodDelete, "/v1/recordings/"+id, alice, nil, nil)
	s.expectCode(404, "not_found", http.MethodGet, "/v1/recordings/"+id, alice, nil, nil)
}

func TestFileUpload(t *testing.T) {
	s := newServer(t)
	alice, bob := s.tokens["alice"], s.tokens["bob"]
	id := "rec1"
	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)

	// Appending needs the right offset; a wrong one says where the file is.
	if st, h, _ := appendChunk(s, alice, id, "hr.log", 0, []byte("hello "), nil); st != 204 || h.Get(headerUploadOffset) != "6" {
		t.Fatalf("first chunk = %d %v", st, h)
	}
	st, h, body := appendChunk(s, alice, id, "hr.log", 0, []byte("again"), nil)
	if st != 409 || h.Get(headerUploadOffset) != "6" || decode[ErrorResponse](t, body).Code != "offset_mismatch" {
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
	if st != 409 || decode[ErrorResponse](t, b).Code != "encoding_mismatch" {
		t.Fatalf("mixed encoding = %d %s", st, b)
	}
	if st, _, b := appendChunk(s, alice, id, "acc.log", 0, []byte("not gzip at all, really not"), gz); st != 400 || decode[ErrorResponse](t, b).Code != "invalid_gzip" {
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
	s.expect(200, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "stopped"), nil)
	s.expect(200, http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil)
	if st, _, b := appendChunk(s, alice, id, "hr.log", 11, []byte("more"), nil); st != 409 || decode[ErrorResponse](t, b).Code != "recording_complete" {
		t.Fatalf("append after complete = %d %s", st, b)
	}
	s.expectCode(409, "recording_complete", http.MethodDelete, "/v1/recordings/"+id+"/files/hr.log", alice, nil, nil)
}

func TestChunkLimits(t *testing.T) {
	t.Setenv("TAIGA_MAX_UPLOAD_CHUNK_MB", "1")
	s := newServer(t)
	alice := s.tokens["alice"]
	s.expect(201, http.MethodPut, "/v1/recordings/big", alice, testMeta("big", "recording"), nil)
	if st, _, b := appendChunk(s, alice, "big", "hr.log", 0, make([]byte, 1024*1024+1), nil); st != 413 || decode[ErrorResponse](t, b).Code != "chunk_too_large" {
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
	s.expect(201, http.MethodPut, "/v1/recordings/full", alice, testMeta("full", "recording"), nil)
	seedStoredBytes(t, s, "full", quota)
	s.expect(201, http.MethodPut, "/v1/recordings/more", alice, testMeta("more", "recording"), nil)
	if st, _, body := appendChunk(s, alice, "more", "hr.log", 0, []byte("frames"), nil); st != 413 ||
		decode[ErrorResponse](t, body).Code != "storage_quota_exceeded" {
		t.Fatalf("upload beyond the quota = %d %s", st, body)
	}

	// The quota is per account: another account still uploads.
	s.expect(201, http.MethodPut, "/v1/recordings/bobs", bob, testMeta("bobs", "recording"), nil)
	if st, _, body := appendChunk(s, bob, "bobs", "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("another account = %d %s", st, body)
	}

	// Deleting the bytes makes room again.
	s.expect(204, http.MethodDelete, "/v1/recordings/full", alice, nil, nil)
	if st, _, body := appendChunk(s, alice, "more", "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("upload after freeing space = %d %s", st, body)
	}
}

func TestZeroStorageQuotaIsUnlimited(t *testing.T) {
	t.Setenv("TAIGA_USER_QUOTA_GB", "0")
	s := newServer(t)
	alice := s.tokens["alice"]
	s.expect(201, http.MethodPut, "/v1/recordings/full", alice, testMeta("full", "recording"), nil)
	seedStoredBytes(t, s, "full", int64(1024)*1024*1024*1024)
	s.expect(201, http.MethodPut, "/v1/recordings/more", alice, testMeta("more", "recording"), nil)
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
	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)

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
	s.expect(201, http.MethodPut, "/v1/recordings/bobs", bob, testMeta("bobs", "recording"), nil)
	if st, _, _ := appendChunk(s, bob, "bobs", "hr.log", 0, []byte("four"), nil); st != 204 {
		t.Fatalf("another account's chunk = %d", st)
	}
	check(7, 7)
	if recording, account := storedTotals(t, s, "bobs", "bob@example.com"); recording != 4 || account != 4 {
		t.Fatalf("bob's totals = %d recording, %d account", recording, account)
	}

	// Deleting the recording takes everything it held off the account.
	s.expect(204, http.MethodDelete, "/v1/recordings/"+id, alice, nil, nil)
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

	s.expect(201, http.MethodPut, "/v1/recordings/uncounted", alice, testMeta("uncounted", "recording"), nil)
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

	s.expect(201, http.MethodPut, "/v1/recordings/next", alice, testMeta("next", "recording"), nil)
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

	s.expect(201, http.MethodPut, "/v1/recordings/full", alice, testMeta("full", "recording"), nil)
	seedStoredBytes(t, s, "full", quota-1)
	s.expect(201, http.MethodPut, "/v1/recordings/race", alice, testMeta("race", "recording"), nil)

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
	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)
	request := map[string]any{"algorithm": "baseline"}

	s.expectCode(409, "recording_incomplete", http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, request, nil)
	completeRecording(s, alice, id)
	s.expectCode(422, "unknown_algorithm", http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, request, nil)
	announce(s, "baseline", "1.1.0")
	announce(s, "baseline", "1.2.0")
	s.expectCode(404, "not_found", http.MethodPost, "/v1/recordings/"+id+"/analyses", bob, request, nil)
	s.expectCode(400, "invalid_algorithm", http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]string{"algorithm": "Bad Id"}, nil)
	s.expectCode(422, "unknown_algorithm", http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]any{"algorithm": "baseline", "version": "9.9.9"}, nil)

	// Announcing replaces; only the newest version of the last announcement is on offer.
	algorithms := decode[AlgorithmList](t, s.expect(200, http.MethodGet, "/v1/algorithms", alice, nil, nil)).Items
	if len(algorithms) != 1 || algorithms[0].Version != "1.2.0" || len(algorithms[0].Workers) != 1 {
		t.Fatalf("algorithms = %+v", algorithms)
	}

	status, header, body := s.call(http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, request, nil)
	if status != 201 {
		t.Fatalf("create analysis = %d %s", status, body)
	}
	analysis := decode[AnalysisView](t, body)
	if analysis.Status != "queued" || analysis.Version != "1.2.0" || analysis.Recording != id || analysis.Result != nil || analysis.Summary != nil ||
		header.Get("Location") != "/v1/analyses/"+analysis.ID {
		t.Fatalf("analysis = %+v %v", analysis, header)
	}
	if again := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, request, nil)); again.ID != analysis.ID {
		t.Fatalf("a repeated request created %s, want %s", again.ID, analysis.ID)
	}
	other := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]any{"algorithm": "baseline", "params": map[string]any{"k": 1}}, nil))
	if other.ID == analysis.ID {
		t.Fatal("different params must be a different analysis")
	}

	// Another user sees nothing of it, and a worker only what it holds the lease of.
	s.expectCode(404, "not_found", http.MethodGet, "/v1/analyses/"+analysis.ID, bob, nil, nil)
	s.expectCode(404, "not_found", http.MethodDelete, "/v1/analyses/"+analysis.ID, bob, nil, nil)
	s.expectCode(404, "not_found", http.MethodGet, "/v1/analyses/"+analysis.ID, worker, nil, nil)
	if got := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses?status=queued", worker, nil, nil)); got.Total != 0 {
		t.Fatalf("a worker lists %d queued analyses it does not hold", got.Total)
	}
	if got := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses", alice, nil, nil)); got.Total != 2 {
		t.Fatalf("alice sees %d analyses", got.Total)
	}
	if got := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses", bob, nil, nil)); got.Total != 0 {
		t.Fatalf("bob sees %d analyses", got.Total)
	}

	// A worker reads the recording only while it holds a lease.
	s.expect(404, http.MethodGet, "/v1/recordings/"+id, worker, nil, nil)
	s.expect(404, http.MethodGet, "/v1/recordings/"+id+"/files", worker, nil, nil)
	s.expect(404, http.MethodGet, "/v1/recordings/"+id+"/files/hr.log", worker, nil, nil)
	claimed := decode[AnalysisView](t, s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, ClaimRequest{Algorithms: []ClaimAlgorithm{{ID: "baseline", Version: "1.2.0"}}}, nil))
	if claimed.Status != "running" || claimed.Attempts != 1 || claimed.ID != analysis.ID {
		t.Fatalf("claimed = %+v", claimed)
	}
	s.expect(200, http.MethodGet, "/v1/recordings/"+id, worker, nil, nil)
	if held := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses", worker, nil, nil)); held.Total != 1 || held.Items[0].ID != claimed.ID {
		t.Fatalf("a worker holds %+v, want only %s", held, claimed.ID)
	}
	if read := decode[AnalysisView](t, s.expect(200, http.MethodGet, "/v1/analyses/"+claimed.ID, worker, nil, nil)); read.ID != claimed.ID {
		t.Fatalf("worker read %+v", read)
	}
	s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files", worker, nil, nil)
	if got := s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files/hr.log", worker, nil, nil); string(got) != "frames" {
		t.Fatalf("worker read %q", got)
	}
	s.expectCode(403, "forbidden", http.MethodDelete, "/v1/recordings/"+id, worker, nil, nil)
	s.expect(204, http.MethodPost, "/v1/analyses/claim", worker, ClaimRequest{Algorithms: []ClaimAlgorithm{{ID: "other"}}}, nil)

	// A result must match what was asked.
	bad := validResult("baseline", "1.2.0")
	bad["model"] = "someone-else"
	s.expectCode(422, "invalid_result", http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, bad)}, nil)
	failed := decode[AnalysisView](t, s.expect(200, http.MethodGet, "/v1/analyses/"+analysis.ID, alice, nil, nil))
	if failed.Status != "failed" || failed.Error == "" || failed.FinishedAt == nil {
		t.Fatalf("a rejected result must fail the analysis: %+v", failed)
	}
	s.expectCode(409, "not_leased", http.MethodPost, "/v1/analyses/"+analysis.ID+"/complete", worker, CompleteRequest{Result: mustJSON(t, validResult("baseline", "1.2.0"))}, nil)
	// The lease is gone, so the worker can no longer read the analysis either.
	s.expect(404, http.MethodGet, "/v1/analyses/"+analysis.ID, worker, nil, nil)

	// Asking again for a failed analysis replaces it with a fresh queued one.
	retry := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, request, nil))
	if retry.Status != "queued" || retry.ID == analysis.ID || retry.Attempts != 0 {
		t.Fatalf("retry = %+v", retry)
	}
	s.expect(404, http.MethodGet, "/v1/analyses/"+analysis.ID, alice, nil, nil)

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
	full := decode[AnalysisView](t, s.expect(200, http.MethodGet, "/v1/analyses/"+next.ID, alice, nil, nil))
	if full.Result == nil || full.Summary == nil {
		t.Fatalf("full = %+v", full)
	}
	summary := full.Summary.(map[string]any)
	if _, ok := summary["stages"]; !ok {
		t.Fatalf("summary = %v", summary)
	}
	s.expect(404, http.MethodGet, "/v1/recordings/"+id, worker, nil, nil) // lease is over
	s.expect(404, http.MethodGet, "/v1/analyses/"+next.ID, worker, nil, nil)
	if got := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses", worker, nil, nil)); got.Total != 0 {
		t.Fatalf("a worker still lists %d analyses after the lease", got.Total)
	}

	// Lists leave the result out unless asked.
	list := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/analyses?status=succeeded", alice, nil, nil))
	if list.Total != 1 || list.Items[0].Result != nil || list.Items[0].Summary == nil {
		t.Fatalf("list = %+v", list)
	}
	expanded := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses?recording="+id+"&status=succeeded&expand=result", alice, nil, nil))
	if expanded.Total != 1 || expanded.Items[0].Result == nil {
		t.Fatalf("expanded = %+v", expanded)
	}
	s.expectCode(400, "invalid_query", http.MethodGet, "/v1/analyses?expand=everything", alice, nil, nil)

	// Deleting the recording takes its analyses along.
	s.expect(204, http.MethodDelete, "/v1/recordings/"+id, alice, nil, nil)
	if got := decode[AnalysisList](t, s.expect(200, http.MethodGet, "/v1/analyses", alice, nil, nil)); got.Total != 0 {
		t.Fatalf("%d analyses survived their recording", got.Total)
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

func TestLeasesAndRetries(t *testing.T) {
	t.Setenv("TAIGA_JOB_MAX_ATTEMPTS", "2")
	s := newServer(t)
	alice, worker := s.tokens["alice"], s.tokens["worker"]
	id := "rec1"
	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]string{"algorithm": "baseline"}, nil))

	expire := func() {
		t.Helper()
		if _, err := s.app.DB().NewQuery("UPDATE analyses SET lease_until = {:past} WHERE id = {:id}").
			Bind(dbx.Params{"past": pbDateMillis(time.Now().Add(-time.Hour).UnixMilli()), "id": analysis.ID}).Execute(); err != nil {
			t.Fatal(err)
		}
	}
	state := func() AnalysisView {
		t.Helper()
		return decode[AnalysisView](t, s.expect(200, http.MethodGet, "/v1/analyses/"+analysis.ID, alice, nil, nil))
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
	again := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]string{"algorithm": "baseline"}, nil))
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	s.expect(204, http.MethodDelete, "/v1/analyses/"+again.ID, alice, nil, nil)
	s.expectCode(404, "not_found", http.MethodPost, "/v1/analyses/"+again.ID+"/fail", worker, FailRequest{Error: "x"}, nil)
}

func TestLeaseRenewal(t *testing.T) {
	s := newServer(t)
	s.account("workers", "worker2@example.com", "worker-2")
	alice, worker, other := s.tokens["alice"], s.tokens["worker"], s.tokens["worker2"]
	id := "rec1"
	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)
	completeRecording(s, alice, id)
	announce(s, "baseline", "1.0.0")
	analysis := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]string{"algorithm": "baseline"}, nil))
	lease := func() string {
		t.Helper()
		record, err := s.app.FindRecordById("analyses", analysis.ID)
		if err != nil {
			t.Fatal(err)
		}
		return record.GetString("lease_until")
	}

	// A user never renews, and nobody can renew work that is not leased to them.
	s.expectCode(403, "forbidden", http.MethodPost, "/v1/analyses/"+analysis.ID+"/lease", alice, LeaseRequest{LeaseSeconds: 60}, nil)
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

func TestAllErrorsUseOneShape(t *testing.T) {
	s := newServer(t)
	for _, call := range []struct{ method, path, token string }{
		{http.MethodGet, "/v1/recordings", ""},
		{http.MethodGet, "/v1/recordings", s.tokens["worker"]},
		{http.MethodGet, "/v1/recordings/missing", s.tokens["alice"]},
		{http.MethodGet, "/v1/analyses/missing", s.tokens["alice"]},
		{http.MethodGet, "/v1/recordings?limit=x", s.tokens["alice"]},
	} {
		status, header, body := s.call(call.method, call.path, call.token, nil, nil)
		var failure ErrorResponse
		if status < 400 || json.Unmarshal(body, &failure) != nil || failure.Code == "" || failure.Message == "" ||
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
	s.expect(201, http.MethodPut, "/v1/recordings/rec1", alice, testMeta("rec1", "recording"), nil)
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

	got := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/rec1/analyses", alice, map[string]string{"algorithm": "baseline"}, nil))
	if got.Version != "1.0.0" {
		t.Fatalf("default version = %s, want the live worker's 1.0.0", got.Version)
	}
	// Asking for the old one by name still queues it: its worker may come back.
	s.expect(201, http.MethodPost, "/v1/recordings/rec1/analyses", alice, map[string]string{"algorithm": "baseline", "version": "2.0.0"}, nil)
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
	s.expectCode(401, "unauthorized", http.MethodGet, "/v1/events", "", nil, nil)

	aliceEvents, bobEvents, workerEvents := openStream(t, s, alice), openStream(t, s, bob), openStream(t, s, worker)
	if events.count() != 3 {
		t.Fatalf("%d subscribers", events.count())
	}

	id := "rec1"
	s.expect(201, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "recording"), nil)
	if got := aliceEvents.next(); got != `recording {"id":"rec1","change":"created"}` {
		t.Fatalf("created event = %q", got)
	}
	s.expect(200, http.MethodPut, "/v1/recordings/"+id, alice, testMeta(id, "stopped"), nil)
	if got := aliceEvents.next(); got != `recording {"id":"rec1","change":"updated"}` {
		t.Fatalf("meta change event = %q", got)
	}
	if st, _, b := appendChunk(s, alice, id, "hr.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append: %d %s", st, b)
	}
	aliceEvents.nothing() // a chunk is not a change to what clients show
	s.expect(200, http.MethodPatch, "/v1/recordings/"+id, alice, map[string]bool{"complete": true}, nil)
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
	analysis := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]string{"algorithm": "baseline"}, nil))
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
	again := decode[AnalysisView](t, s.expect(201, http.MethodPost, "/v1/recordings/"+id+"/analyses", alice, map[string]any{"algorithm": "baseline", "params": map[string]int{"k": 1}}, nil))
	workerEvents.next()
	s.expect(200, http.MethodPost, "/v1/analyses/claim", worker, nil, nil)
	s.expect(200, http.MethodPost, "/v1/analyses/"+again.ID+"/fail", worker, FailRequest{Error: "flaky", Retryable: true}, nil)
	if got := workerEvents.next(); !strings.Contains(got, again.ID) || !strings.Contains(got, `"queued"`) {
		t.Fatalf("requeue event = %q", got)
	}

	// Deleting an analysis and then the recording.
	drain(aliceEvents)
	s.expect(204, http.MethodDelete, "/v1/analyses/"+analysis.ID, alice, nil, nil)
	if got := aliceEvents.next(); got != `analysis {"id":"`+analysis.ID+`","recording":"rec1","status":"deleted"}` {
		t.Fatalf("delete event = %q", got)
	}
	s.expect(204, http.MethodDelete, "/v1/recordings/"+id, alice, nil, nil)
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
