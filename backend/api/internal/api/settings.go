package api

import (
	"os"
	"slices"
	"strconv"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// The deployment settings PocketBase keeps in its database rather than in flags.
// Each has a default that fits the deployed setup described in backend/README.md.
const (
	defaultTrustedProxyHeaders = "X-Forwarded-For"
	defaultBackupCron          = "0 3 * * *"
	defaultBackupKeep          = 14
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
// The trusted proxy and the rate limits are this API's own configuration: they are
// written every time. The backup settings belong to the operator once they exist, so
// a default only fills an empty value and a variable only overrides what it names.
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

	headers := []string{defaultTrustedProxyHeaders}
	if raw, set := os.LookupEnv("TAIGA_TRUSTED_PROXY_HEADERS"); set {
		headers = headerList(raw)
	}
	if !slices.Equal(settings.TrustedProxy.Headers, headers) {
		settings.TrustedProxy.Headers = headers
		changed = true
	}

	if rules := rateLimitRules(); !settings.RateLimits.Enabled || !slices.Equal(settings.RateLimits.Rules, rules) {
		settings.RateLimits.Enabled = true
		settings.RateLimits.Rules = rules
		changed = true
	}

	// The limit belongs to the schedule the operator stored, so it is read before the
	// schedule is replaced.
	storedCron := settings.Backups.Cron
	if cron := backupCron(storedCron); cron != storedCron {
		settings.Backups.Cron = cron
		changed = true
	}
	if keep := backupKeep(storedCron, settings.Backups.CronMaxKeep); keep != settings.Backups.CronMaxKeep {
		settings.Backups.CronMaxKeep = keep
		changed = true
	}

	return changed
}

// headerList reads a comma-separated list of header names. An empty value means
// clients connect directly and no header is trusted.
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
// rule only has to name the file paths and the last rule covers everything else.
func rateLimitRules() []core.RateLimitRule {
	return []core.RateLimitRule{
		{
			Label:       "POST " + apiPrefix + "/auth/login",
			MaxRequests: loginRateLimit,
			Duration:    loginRateLimitWindow,
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

// backupCron is the automatic backup schedule. Unset keeps the operator's schedule,
// or the default when none is stored; set uses the value, and an empty value turns
// automatic backups off.
func backupCron(stored string) string {
	if raw, set := os.LookupEnv("TAIGA_BACKUP_CRON"); set {
		return strings.TrimSpace(raw)
	}
	if stored != "" {
		return stored
	}
	return defaultBackupCron
}

// backupKeep is how many automatic backups are kept. TAIGA_BACKUP_KEEP overrides it
// when it is a positive whole number; a schedule the operator set keeps its own limit,
// and without a schedule the default applies.
func backupKeep(storedCron string, storedKeep int) int {
	if raw := strings.TrimSpace(os.Getenv("TAIGA_BACKUP_KEEP")); raw != "" {
		if keep, err := strconv.Atoi(raw); err == nil && keep > 0 {
			return keep
		}
	}
	if storedCron != "" && storedKeep > 0 {
		return storedKeep
	}
	return defaultBackupKeep
}
