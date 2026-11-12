package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
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

// TestLoginRateLimitCountsPerClientAddress drives the real login route past the limit
// and checks that PocketBase's limiter answers 429 in the one error shape, and that
// another client address still has its own budget.
func TestLoginRateLimitCountsPerClientAddress(t *testing.T) {
	s := newConfiguredServer(t)
	s.createAccount("users", "bob@example.com", "")

	login := func(password, address string) (int, []byte) {
		status, _, body := s.call(http.MethodPost, "/v1/auth/login", "",
			map[string]string{"email": "bob@example.com", "password": password},
			map[string]string{"X-Forwarded-For": address})
		return status, body
	}

	const (
		guessing = "203.0.113.7"
		other    = "203.0.113.8"
	)
	attempts := 0
	for attempts <= 2*loginRateLimit {
		status, body := login("wrong", guessing)
		if status == http.StatusUnauthorized {
			attempts++
			continue
		}
		if status != http.StatusTooManyRequests {
			t.Fatalf("attempt %d = %d: %s", attempts+1, status, body)
		}
		var failure ErrorResponse
		if err := json.Unmarshal(body, &failure); err != nil || failure.Code != "too_many_requests" {
			t.Fatalf("throttled answer = %s", body)
		}
		break
	}
	if attempts != loginRateLimit {
		t.Fatalf("got %d attempts before the limit, want %d", attempts, loginRateLimit)
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
