// Package ratelimit holds the API's default rate-limit rules. A migration writes them
// once, on the data directory that reaches it, and PocketBase's settings own them after
// that.
package ratelimit

import "github.com/pocketbase/pocketbase/core"

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
)

// Rules is the whole rate-limit configuration of the API. PocketBase matches a rule
// on "METHOD /path" first and then on the longest path prefix, so the upload rule only
// has to name the file paths and the last rule covers everything else. The auth rules
// name PocketBase's collection actions, which is how its own routes find them: the
// password routes of both auth collections are what slows password guessing.
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
