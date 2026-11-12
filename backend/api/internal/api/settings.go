package api

import (
	"os"
	"slices"
	"strconv"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// How often one client address may call a group of routes. PocketBase's rate limiter
// keys on the client address, so these are per address, not per account: the login
// limit is what slows password guessing, the others only keep one client from
// flooding the API.
const (
	loginRateLimit        = 20
	loginRateLimitWindow  = 300
	tokenRateLimit        = 20
	tokenRateLimitWindow  = 60
	uploadRateLimit       = 300
	uploadRateLimitWindow = 60
	apiRateLimit          = 300
	apiRateLimitWindow    = 10
)

// applyDeploymentSettings writes the settings the TAIGA_* variables configure and
// reports whether anything changed, so the bootstrap saves only when it has to.
//
// The trusted proxy and the rate limits are this API's own configuration, so they are
// written every time: no header is trusted unless one is named, and the client address
// cannot be chosen by the caller. The backup settings belong to the operator once the
// schema migration has written the defaults, so a variable overrides a stored value
// only when it is set.
func applyDeploymentSettings(settings *core.Settings) bool {
	changed := false

	if name := os.Getenv("TAIGA_APP_NAME"); name != "" && settings.Meta.AppName != name {
		settings.Meta.AppName = name
		changed = true
	}
	if url := os.Getenv("TAIGA_APP_URL"); url != "" && settings.Meta.AppURL != url {
		settings.Meta.AppURL = url
		changed = true
	}

	headers := headerList(os.Getenv("TAIGA_TRUSTED_PROXY_HEADERS"))
	if !slices.Equal(settings.TrustedProxy.Headers, headers) {
		settings.TrustedProxy.Headers = headers
		changed = true
	}

	if rules := rateLimitRules(); !settings.RateLimits.Enabled || !slices.Equal(settings.RateLimits.Rules, rules) {
		settings.RateLimits.Enabled = true
		settings.RateLimits.Rules = rules
		changed = true
	}

	// An empty value disables the automatic backups, so the variable is read even when
	// it is set to nothing.
	if raw, set := os.LookupEnv("TAIGA_BACKUP_CRON"); set {
		if cron := strings.TrimSpace(raw); cron != settings.Backups.Cron {
			settings.Backups.Cron = cron
			changed = true
		}
	}
	if raw := strings.TrimSpace(os.Getenv("TAIGA_BACKUP_KEEP")); raw != "" {
		if keep, err := strconv.Atoi(raw); err == nil && keep > 0 && keep != settings.Backups.CronMaxKeep {
			settings.Backups.CronMaxKeep = keep
			changed = true
		}
	}

	return changed
}

// headerList reads a comma-separated list of header names. Without one, clients
// connect directly and no header is trusted.
func headerList(raw string) []string {
	var headers []string
	for _, part := range strings.Split(raw, ",") {
		if part = strings.TrimSpace(part); part != "" {
			headers = append(headers, part)
		}
	}
	return headers
}

// rateLimitRules is the whole rate-limit configuration of the API. PocketBase matches
// a rule on "METHOD /path" first and then on the longest path prefix, so the upload
// rule only has to name the file paths and the last rule covers everything else. The
// auth rules name PocketBase's collection actions, which is how its own routes find
// them: the password routes of both auth collections are what slows password guessing.
func rateLimitRules() []core.RateLimitRule {
	return []core.RateLimitRule{
		{
			Label:       "*:authWithPassword",
			MaxRequests: loginRateLimit,
			Duration:    loginRateLimitWindow,
		},
		{
			Label:       "*:authRefresh",
			MaxRequests: apiRateLimit,
			Duration:    apiRateLimitWindow,
		},
		{
			Label:       "POST " + apiPrefix + "/tokens",
			MaxRequests: tokenRateLimit,
			Duration:    tokenRateLimitWindow,
		},
		{
			Label:       "PATCH " + apiPrefix + "/recordings/",
			MaxRequests: uploadRateLimit,
			Duration:    uploadRateLimitWindow,
		},
		{
			Label:       apiPrefix + "/",
			MaxRequests: apiRateLimit,
			Duration:    apiRateLimitWindow,
		},
	}
}
