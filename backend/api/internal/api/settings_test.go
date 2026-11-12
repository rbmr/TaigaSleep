package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"

	"github.com/rbmr/TaigaSleep/backend/api/internal/ratelimit"
)

// newConfiguredServer is a server whose PocketBase settings were applied the way the
// bootstrap applies them, so the routes run behind PocketBase's rate limiter. Like the
// integration harness, it acts as clients behind a proxy and names its header.
func newConfiguredServer(t *testing.T) *server {
	t.Helper()
	t.Setenv("TAIGA_TRUSTED_PROXY_HEADERS", "X-Forwarded-For")
	app, err := tests.NewTestApp()
	if err != nil {
		t.Fatalf("test app: %v", err)
	}
	t.Cleanup(app.Cleanup)
	if applyDeploymentSettings(app.Settings()) {
		if err := app.Save(app.Settings()); err != nil {
			t.Fatalf("save settings: %v", err)
		}
	}
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
	s.createAccount("users", "alice@example.com", "")
	return s
}

// TestProxyHeaderCarriesTheClientAddress checks the address the request log records and
// the rate limiter keys on once a proxy header is configured: it is the header's client,
// and without the header it is the connection's address.
func TestProxyHeaderCarriesTheClientAddress(t *testing.T) {
	s := newConfiguredServer(t)
	if got := s.app.Settings().TrustedProxy.Headers; !slices.Equal(got, []string{"X-Forwarded-For"}) {
		t.Fatalf("trusted proxy headers = %v, want X-Forwarded-For", got)
	}

	event := func(header map[string]string) *core.RequestEvent {
		request := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
		request.RemoteAddr = "192.0.2.10:5000"
		for name, value := range header {
			request.Header.Set(name, value)
		}
		e := &core.RequestEvent{App: s.app}
		e.Request = request
		return e
	}

	if got := event(map[string]string{"X-Forwarded-For": "203.0.113.7"}).RealIP(); got != "203.0.113.7" {
		t.Errorf("real address = %q, want the client's", got)
	}
	if got := event(nil).RealIP(); got != "192.0.2.10" {
		t.Errorf("real address = %q, want the connection's", got)
	}
}

// TestUnconfiguredProxyHeaderIsIgnored keeps a client that sends the header itself from
// choosing the address it is logged and limited as.
func TestUnconfiguredProxyHeaderIsIgnored(t *testing.T) {
	t.Setenv("TAIGA_TRUSTED_PROXY_HEADERS", "")
	app := bootedApp(t, t.TempDir())

	request := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
	request.RemoteAddr = "192.0.2.10:5000"
	request.Header.Set("X-Forwarded-For", "203.0.113.7")
	event := &core.RequestEvent{App: app}
	event.Request = request

	if got := event.RealIP(); got != "192.0.2.10" {
		t.Errorf("real address = %q, want the connection's", got)
	}
}

// TestLoginRateLimitCountsPerClientAddress drives the real password login route past
// the limit and checks that PocketBase's limiter answers 429, and that another client
// address still has its own budget.
func TestLoginRateLimitCountsPerClientAddress(t *testing.T) {
	s := newConfiguredServer(t)
	s.createAccount("users", "bob@example.com", "")

	login := func(password, address string) (int, []byte) {
		status, _, body := s.call(http.MethodPost, "/api/collections/users/auth-with-password", "",
			map[string]string{"identity": "bob@example.com", "password": password},
			map[string]string{"X-Forwarded-For": address})
		return status, body
	}

	const (
		guessing = "203.0.113.7"
		other    = "203.0.113.8"
	)
	attempts := 0
	for attempts <= 2*ratelimit.LoginMaxRequests {
		status, body := login("wrong", guessing)
		if status == http.StatusBadRequest {
			attempts++
			continue
		}
		if status != http.StatusTooManyRequests {
			t.Fatalf("attempt %d = %d: %s", attempts+1, status, body)
		}
		var failure map[string]any
		if err := json.Unmarshal(body, &failure); err != nil || failure["message"] == "" {
			t.Fatalf("throttled answer = %s", body)
		}
		break
	}
	if attempts != ratelimit.LoginMaxRequests {
		t.Fatalf("got %d attempts before the limit, want %d", attempts, ratelimit.LoginMaxRequests)
	}

	// The right password waits too once the address is out of budget.
	if status, body := login("password123", guessing); status != http.StatusTooManyRequests {
		t.Errorf("login after the limit = %d: %s", status, body)
	}
	// Another address is a different client, with its own budget.
	if status, body := login("password123", other); status != http.StatusOK {
		t.Errorf("login from another address = %d: %s", status, body)
	}
}

// patchSettings changes PocketBase's settings as a superuser, the way the dashboard
// does.
func patchSettings(t *testing.T, app core.App, body any) {
	t.Helper()
	superusers, err := app.FindCollectionByNameOrId("_superusers")
	if err != nil {
		t.Fatalf("superusers collection: %v", err)
	}
	admin := core.NewRecord(superusers)
	admin.SetEmail("admin@example.com")
	admin.SetPassword("password123")
	admin.SetVerified(true)
	if err := app.Save(admin); err != nil {
		t.Fatalf("create superuser: %v", err)
	}

	router, err := apis.NewRouter(app)
	if err != nil {
		t.Fatalf("router: %v", err)
	}
	mux, err := router.BuildMux()
	if err != nil {
		t.Fatalf("mux: %v", err)
	}
	call := func(method, path, token string, payload any) (int, []byte) {
		var reader io.Reader
		if payload != nil {
			raw, err := json.Marshal(payload)
			if err != nil {
				t.Fatal(err)
			}
			reader = bytes.NewReader(raw)
		}
		request := httptest.NewRequest(method, path, reader)
		if payload != nil {
			request.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			request.Header.Set("Authorization", "Bearer "+token)
		}
		recorder := httptest.NewRecorder()
		mux.ServeHTTP(recorder, request)
		result := recorder.Result()
		data, _ := io.ReadAll(result.Body)
		return result.StatusCode, data
	}

	status, data := call(http.MethodPost, "/api/collections/_superusers/auth-with-password", "",
		map[string]string{"identity": "admin@example.com", "password": "password123"})
	if status != http.StatusOK {
		t.Fatalf("superuser login: %d %s", status, data)
	}
	var login struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(data, &login); err != nil {
		t.Fatalf("superuser token: %v", err)
	}

	status, data = call(http.MethodPatch, "/api/settings", login.Token, body)
	if status != http.StatusOK {
		t.Fatalf("patch settings: %d %s", status, data)
	}
}

// TestAppNameChangedInTheDashboardSurvivesARestart follows a data directory from its first
// start: the initial migration sets the app name once, and the name the operator gives it
// afterwards is not written over by a later start.
func TestAppNameChangedInTheDashboardSurvivesARestart(t *testing.T) {
	dir := t.TempDir()
	fresh := migratedApp(t, dir)
	if got := fresh.Settings().Meta.AppName; got != "Taiga Sleep" {
		t.Fatalf("app name after the migration = %q, want Taiga Sleep", got)
	}

	patchSettings(t, fresh, map[string]any{
		"meta": map[string]any{"appName": "Night Owls"},
	})
	if err := fresh.ClearBootstrap(); err != nil {
		t.Fatalf("stop the first instance: %v", err)
	}

	// An older unit or compose file may still name the app the default way; the
	// operator's choice wins.
	t.Setenv("TAIGA_APP_NAME", "Taiga Sleep")
	t.Setenv("TAIGA_APP_URL", "http://127.0.0.1:8090")

	restarted := migratedApp(t, dir)
	if got := restarted.Settings().Meta.AppName; got != "Night Owls" {
		t.Errorf("restarted app name = %q, want the operator's", got)
	}
}

// TestRateLimitDefaultsAreSetOnceAndTheOperatorKeepsTheChoice follows a data directory
// from its first start: the migration enables the rules, and a rule the operator changes
// through the settings API afterwards is not written over by a later start.
func TestRateLimitDefaultsAreSetOnceAndTheOperatorKeepsTheChoice(t *testing.T) {
	dir := t.TempDir()
	fresh := migratedApp(t, dir)
	if !fresh.Settings().RateLimits.Enabled {
		t.Fatal("the rate limiter is disabled")
	}

	rules := slices.Clone(fresh.Settings().RateLimits.Rules)
	for i := range rules {
		if rules[i].Label == "*:authWithPassword" {
			rules[i].MaxRequests = 7
			rules[i].Duration = 90
		}
	}
	patchSettings(t, fresh, map[string]any{
		"rateLimits": map[string]any{"rules": rules},
	})
	if err := fresh.ClearBootstrap(); err != nil {
		t.Fatalf("stop the first instance: %v", err)
	}

	restarted := migratedApp(t, dir)
	rule, ok := rateLimitByLabel(restarted.Settings(), "*:authWithPassword")
	if !ok || rule.MaxRequests != 7 || rule.Duration != 90 {
		t.Errorf("restarted password login rule = %+v (%v), want 7 per 90 s", rule, ok)
	}
}
