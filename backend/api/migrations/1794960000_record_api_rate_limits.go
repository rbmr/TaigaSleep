package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"github.com/rbmr/TaigaSleep/backend/api/internal/ratelimit"
)

// The first rate-limit migration named the /v1 routes only; the record API and the
// realtime endpoint are PocketBase's own routes and had no limit. This adds their
// defaults to a data directory that already holds the earlier rules, and leaves every
// rule it finds alone: the dashboard owns them from the moment they are written.
func init() {
	m.Register(upRecordAPIRateLimits, downRecordAPIRateLimits)
}

func upRecordAPIRateLimits(app core.App) error {
	settings := app.Settings()

	existing := make(map[string]bool, len(settings.RateLimits.Rules))
	for _, rule := range settings.RateLimits.Rules {
		existing[rule.Label+"@@"+rule.Audience] = true
	}
	added := false
	for _, rule := range ratelimit.RecordRules() {
		if existing[rule.Label+"@@"+rule.Audience] {
			continue
		}
		settings.RateLimits.Rules = append(settings.RateLimits.Rules, rule)
		added = true
	}
	if !added {
		return nil
	}
	return app.Save(settings)
}

// downRecordAPIRateLimits keeps the rules: they are ordinary operator settings after
// this migration ran, and reverting the migration must not throw a choice away.
func downRecordAPIRateLimits(core.App) error {
	return nil
}
