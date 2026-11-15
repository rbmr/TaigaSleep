package api

import (
	"errors"
	"math"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/hook"
	"github.com/pocketbase/pocketbase/tools/router"

	"github.com/rbmr/TaigaSleep/backend/api/internal/ratelimit"
)

// PocketBase's limiter counts every request per client address. The API adds two
// limits it cannot express: how much one account may do whatever address it uses, and
// how many realtime connections one account may hold. Both answer like the address
// limiter does, with PocketBase's 429 shape and a Retry-After header it does not set.

// accountLimitsStoreKey is where the app keeps its per-account counters.
const accountLimitsStoreKey = "__taigaAccountLimits__"

// accountLimits returns the app's per-account counters, creating them on first use.
func accountLimits(app core.App) *ratelimit.Limits {
	return app.Store().GetOrSet(accountLimitsStoreKey, func() any {
		return ratelimit.NewLimits()
	}).(*ratelimit.Limits)
}

// accountRateLimitMiddlewareId names the middleware in the route's hook chain.
const accountRateLimitMiddlewareId = "taigaAccountRateLimit"

// accountRateLimitMiddleware counts the request against its account's budget and adds
// the Retry-After header to a 429. It runs after the auth token is loaded, so the
// account is known, and before PocketBase's limiter, so it sees the 429 that one
// produces as well. A request the address limiter refuses still spends the account's
// budget: a client that keeps calling a shared address after it was refused is
// flooding it.
func accountRateLimitMiddleware() *hook.Handler[*core.RequestEvent] {
	return &hook.Handler[*core.RequestEvent]{
		Id:       accountRateLimitMiddlewareId,
		Priority: apis.DefaultRateLimitMiddlewarePriority - 10,
		Func: func(e *core.RequestEvent) error {
			if !e.App.Settings().RateLimits.Enabled || e.HasSuperuserAuth() || excludedAddress(e) {
				return e.Next()
			}
			if account := requestAccount(e.Auth); account != "" {
				if budget, ok := accountBudgetFor(e.Request.URL.Path); ok {
					if allowed, wait := accountLimits(e.App).Allow(budget, account, time.Now()); !allowed {
						return tooManyRequests(e, wait)
					}
				}
			}
			err := e.Next()
			if err != nil && isTooManyRequests(err) && e.Response.Header().Get("Retry-After") == "" {
				e.Response.Header().Set("Retry-After", retryAfter(addressLimitWindow(e)))
			}
			return err
		},
	}
}

// registerRealtimeHooks limits how many realtime connections one account holds at
// once. PocketBase's limiter counts the connect attempts of an address, not the
// connections an account keeps open. The connection releases its place when the
// stream ends, which is when Next returns.
func registerRealtimeHooks(app core.App) {
	app.OnRealtimeConnectRequest().BindFunc(func(e *core.RealtimeConnectRequestEvent) error {
		account := requestAccount(e.Auth)
		if !e.App.Settings().RateLimits.Enabled || e.HasSuperuserAuth() || excludedAddress(e.RequestEvent) || account == "" {
			return e.Next()
		}
		release, ok := accountLimits(e.App).Connect(account)
		if !ok {
			// The connect route has already announced an event stream; this answer is
			// the API's ordinary error shape.
			e.Response.Header().Set("Content-Type", "application/json")
			return tooManyRequests(e.RequestEvent, ratelimit.AccountRealtimeRetryAfterSeconds*time.Second)
		}
		defer release()
		return e.Next()
	})
}

// excludedAddress reports whether the operator excluded this client address from
// PocketBase's limiter. The account budgets honour the same list, so a monitoring or
// test client is not limited by an account's budget either.
func excludedAddress(e *core.RequestEvent) bool {
	address, err := netip.ParseAddr(e.RealIP())
	if err != nil {
		return false
	}
	for _, entry := range e.App.Settings().RateLimits.ExcludedIPs {
		if prefix, err := netip.ParsePrefix(entry); err == nil {
			if prefix.Contains(address) {
				return true
			}
			continue
		}
		if single, err := netip.ParseAddr(entry); err == nil && single == address {
			return true
		}
	}
	return false
}

// requestAccount is the account a request acts for. A read-only access token acts for
// the account that created it, so its reads share that account's budget instead of
// getting a fresh one per token.
func requestAccount(auth *core.Record) string {
	if auth == nil {
		return ""
	}
	if auth.Collection().Name == "access_tokens" {
		return auth.GetString("owner")
	}
	return auth.Id
}

// accountBudgetFor names the account budget a request counts against. The record API
// and /v1 each have one. The realtime endpoint has none here: what one account may
// open is counted by the connect hook.
func accountBudgetFor(path string) (ratelimit.Budget, bool) {
	switch {
	case underPath(path, ratelimit.RecordingsPath), underPath(path, ratelimit.AnalysesPath):
		return ratelimit.RecordAPIBudget(), true
	case underPath(path, "/v1"):
		return ratelimit.APIBudget(), true
	}
	return ratelimit.Budget{}, false
}

// underPath reports whether path is base or one of its subpaths.
func underPath(path, base string) bool {
	return path == base || strings.HasPrefix(path, base+"/")
}

// tooManyRequests answers 429 in PocketBase's shape with the seconds to wait.
func tooManyRequests(e *core.RequestEvent, wait time.Duration) error {
	e.Response.Header().Set("Retry-After", retryAfter(wait))
	return e.TooManyRequestsError("", nil)
}

// retryAfter is how long a client waits, in whole seconds, at least one.
func retryAfter(wait time.Duration) string {
	seconds := int64(math.Ceil(wait.Seconds()))
	if seconds < 1 {
		seconds = 1
	}
	return strconv.FormatInt(seconds, 10)
}

// isTooManyRequests reports whether the request failed because of a rate limit.
func isTooManyRequests(err error) bool {
	var apiErr *router.ApiError
	return errors.As(err, &apiErr) && apiErr.Status == http.StatusTooManyRequests
}

// addressLimitWindow is the window of the rule PocketBase's limiter used for this
// request. Its limiter does not say how far a window has advanced, so the whole
// window is the safe answer; a minute when no rule names the route.
func addressLimitWindow(e *core.RequestEvent) time.Duration {
	audience := core.RateLimitRuleAudienceGuest
	if e.Auth != nil {
		audience = core.RateLimitRuleAudienceAuth
	}
	rule, ok := e.App.Settings().RateLimits.FindRateLimitRule(
		[]string{e.Request.Method + " " + e.Request.URL.Path, e.Request.URL.Path},
		core.RateLimitRuleAudienceAll, audience,
	)
	if !ok {
		return time.Minute
	}
	return rule.DurationTime()
}
