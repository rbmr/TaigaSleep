// Package ratelimit holds the API's rate-limit rules and the per-account budgets
// PocketBase's limiter cannot key on: its counters know the client address only.
// A migration writes the default rules once, on the data directory that reaches it,
// and PocketBase's settings own them after that.
package ratelimit

import (
	"sync"
	"time"

	"github.com/pocketbase/pocketbase/core"
)

// How often one client address may call a group of routes. PocketBase's rate limiter
// keys on the client address, so these are per address, not per account: the login
// limit is what slows password guessing, the others only keep one client from
// flooding the API.
const (
	LoginMaxRequests    = 20
	LoginWindowSeconds  = 300
	TokenMaxRequests    = 20
	TokenWindowSeconds  = 60
	UploadMaxRequests   = 300
	UploadWindowSeconds = 60
	APIMaxRequests      = 300
	APIWindowSeconds    = 10

	// The record API carries the app's bulk traffic. A catch-up writes two records
	// per recording, so the budget leaves room for many recordings in one window.
	RecordMaxRequests   = 900
	RecordWindowSeconds = 60

	// A realtime connection is long-lived, so what a client may hold open is what
	// matters; this only slows a client that connects in a loop.
	RealtimeMaxRequests   = 60
	RealtimeWindowSeconds = 60
)

// RecordingsPath and AnalysesPath are the record API of the two collections the app
// reads and writes; the record budget covers both.
const (
	RecordingsPath = "/api/collections/recordings/records"
	AnalysesPath   = "/api/collections/analyses/records"
)

// What one account may do in one window, whatever address it uses. The record API
// budget is below the address budget of the same traffic, so one busy account leaves
// room for the others sharing its address. The /v1 budget is above the existing
// upload rule, so an upload at the chunk rate the API already allows stays well
// below it.
const (
	AccountRecordMaxRequests   = 600
	AccountRecordWindowSeconds = 60

	AccountAPIMaxRequests   = 900
	AccountAPIWindowSeconds = 60

	// Concurrent realtime connections one account may hold.
	AccountRealtimeMaxConnections    = 8
	AccountRealtimeRetryAfterSeconds = 60
)

// Rules is the whole rate-limit configuration of the API as the first migration
// writes it. PocketBase matches a rule on "METHOD /path" first and then on the path,
// either exactly or by a label that ends in "/" and is a prefix of it; the first rule
// in the list that matches wins, so the upload rule only has to name the file paths
// and the last rule covers everything else. The auth rules name PocketBase's
// collection actions, which is how its own routes find them: the password routes of
// both auth collections are what slows password guessing.
func Rules() []core.RateLimitRule {
	return []core.RateLimitRule{
		{
			Label:       "*:authWithPassword",
			MaxRequests: LoginMaxRequests,
			Duration:    LoginWindowSeconds,
		},
		{
			Label:       "*:authRefresh",
			MaxRequests: APIMaxRequests,
			Duration:    APIWindowSeconds,
		},
		{
			Label:       "POST /v1/tokens",
			MaxRequests: TokenMaxRequests,
			Duration:    TokenWindowSeconds,
		},
		{
			Label:       "PATCH /v1/recordings/",
			MaxRequests: UploadMaxRequests,
			Duration:    UploadWindowSeconds,
		},
		{
			Label:       "/v1/",
			MaxRequests: APIMaxRequests,
			Duration:    APIWindowSeconds,
		},
	}
}

// RecordRules are the rules for the routes PocketBase's limiter did not name: the
// record API the app reads and writes through, and the realtime endpoint. A later
// migration adds them to a data directory that already holds the earlier rules.
func RecordRules() []core.RateLimitRule {
	return []core.RateLimitRule{
		{
			Label:       RecordingsPath + "/",
			MaxRequests: RecordMaxRequests,
			Duration:    RecordWindowSeconds,
		},
		{
			Label:       AnalysesPath + "/",
			MaxRequests: RecordMaxRequests,
			Duration:    RecordWindowSeconds,
		},
		{
			Label:       "/api/realtime",
			MaxRequests: RealtimeMaxRequests,
			Duration:    RealtimeWindowSeconds,
		},
	}
}

// Budget is one window of requests one account may make in a group of routes.
type Budget struct {
	Name        string
	MaxRequests int
	Window      time.Duration
}

// RecordAPIBudget is the account's budget for the recordings and analyses record API.
func RecordAPIBudget() Budget {
	return Budget{
		Name:        "record-api",
		MaxRequests: AccountRecordMaxRequests,
		Window:      AccountRecordWindowSeconds * time.Second,
	}
}

// APIBudget is the account's budget for /v1, upload chunks included.
func APIBudget() Budget {
	return Budget{
		Name:        "api",
		MaxRequests: AccountAPIMaxRequests,
		Window:      AccountAPIWindowSeconds * time.Second,
	}
}

// Limits counts what each account has used and how many realtime connections it
// holds. One instance belongs to one app.
type Limits struct {
	mu        sync.Mutex
	windows   map[string]*window
	open      map[string]int
	lastSweep time.Time
}

type window struct {
	start    time.Time
	interval time.Duration
	used     int
}

// NewLimits returns empty per-account counters.
func NewLimits() *Limits {
	return &Limits{
		windows: map[string]*window{},
		open:    map[string]int{},
	}
}

// Allow records one request for the account in the budget's group. It reports false
// with the time left in the current window once the budget is used up. A window
// starts with its first request and is fixed until it is over, like PocketBase's.
func (l *Limits) Allow(budget Budget, account string, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()

	l.sweep(now)

	key := budget.Name + "|" + account
	entry := l.windows[key]
	if entry == nil || now.Sub(entry.start) >= budget.Window {
		entry = &window{start: now, interval: budget.Window}
		l.windows[key] = entry
	}
	if entry.used >= budget.MaxRequests {
		return false, budget.Window - now.Sub(entry.start)
	}
	entry.used++
	return true, 0
}

// Connect records one realtime connection for the account and returns the function
// that releases it again. It reports false when the account already holds the
// maximum.
func (l *Limits) Connect(account string) (release func(), ok bool) {
	l.mu.Lock()
	defer l.mu.Unlock()

	if l.open[account] >= AccountRealtimeMaxConnections {
		return nil, false
	}
	l.open[account]++

	var once sync.Once
	return func() {
		once.Do(func() {
			l.mu.Lock()
			defer l.mu.Unlock()
			if l.open[account] <= 1 {
				delete(l.open, account)
				return
			}
			l.open[account]--
		})
	}, true
}

// sweep drops windows that are over; without it every account that ever made a
// request would keep an entry. It runs at most every ten minutes.
func (l *Limits) sweep(now time.Time) {
	const sweepEvery = 10 * time.Minute
	if now.Sub(l.lastSweep) < sweepEvery {
		return
	}
	l.lastSweep = now
	for key, entry := range l.windows {
		if now.Sub(entry.start) >= 2*entry.interval {
			delete(l.windows, key)
		}
	}
}
