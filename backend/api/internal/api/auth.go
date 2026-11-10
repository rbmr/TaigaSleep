package api

import (
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/pocketbase/pocketbase/core"
)

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type AccountView struct {
	ID    string `json:"id"`
	Email string `json:"email"`
}

type TokenResponse struct {
	Token string `json:"token"`
	// Role is "user" for an app account and "worker" for an analysis worker.
	Role string      `json:"role"`
	User AccountView `json:"user"`
}

type MeResponse struct {
	ID    string `json:"id"`
	Email string `json:"email"`
	Role  string `json:"role"`
}

func tokenFor(record *core.Record) (TokenResponse, error) {
	token, err := record.NewAuthToken()
	if err != nil {
		return TokenResponse{}, err
	}
	role := roleUser
	if record.Collection().Name == "workers" {
		role = roleWorker
	}
	return TokenResponse{Token: token, Role: role, User: AccountView{ID: record.Id, Email: record.Email()}}, nil
}

func Login(e *core.RequestEvent) error {
	var body LoginRequest
	const expected = "email and password are required"
	if err := decodeBody(e, bodyLimitSmall, &body, expected, false); err != nil {
		return err
	}
	if body.Email == "" || body.Password == "" {
		return badRequest("invalid_body", expected)
	}
	key := e.RealIP() + "|" + strings.ToLower(body.Email)
	if logins.blocked(key) {
		return newError(http.StatusTooManyRequests, "too_many_attempts", "too many failed logins, try again later")
	}
	var account *core.Record
	for _, collection := range []string{"users", "workers"} {
		record, err := e.App.FindAuthRecordByEmail(collection, body.Email)
		if err == nil && record.ValidatePassword(body.Password) {
			account = record
			break
		}
	}
	if account == nil {
		logins.fail(key)
		return unauthorized("the email or password is wrong")
	}
	logins.reset(key)
	response, err := tokenFor(account)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, response)
}

func Refresh(e *core.RequestEvent) error {
	response, err := tokenFor(e.Auth)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, response)
}

func Me(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, MeResponse{ID: e.Auth.Id, Email: e.Auth.Email(), Role: roleOf(e)})
}

// loginLimiter slows password guessing: after maxFailures wrong passwords for the
// same address and account, further tries are refused until the window passes.
type loginLimiter struct {
	mu       sync.Mutex
	failures map[string][]time.Time
}

const (
	loginWindow      = 5 * time.Minute
	loginMaxFailures = 10
)

var logins = &loginLimiter{failures: map[string][]time.Time{}}

func (l *loginLimiter) recent(key string) []time.Time {
	cutoff := time.Now().Add(-loginWindow)
	kept := l.failures[key][:0]
	for _, at := range l.failures[key] {
		if at.After(cutoff) {
			kept = append(kept, at)
		}
	}
	if len(kept) == 0 {
		delete(l.failures, key)
		return nil
	}
	l.failures[key] = kept
	return kept
}

func (l *loginLimiter) blocked(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.recent(key)) >= loginMaxFailures
}

func (l *loginLimiter) fail(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.failures[key] = append(l.recent(key), time.Now())
}

func (l *loginLimiter) reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.failures, key)
}
