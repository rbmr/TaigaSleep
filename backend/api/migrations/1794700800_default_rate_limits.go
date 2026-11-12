package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"github.com/rbmr/TaigaSleep/backend/api/internal/ratelimit"
)

// PocketBase intends its rate-limit rules as operator settings. This migration writes
// the API's defaults once, on the data directory that reaches it, and leaves rules
// already stored alone. Afterwards the dashboard owns them, so changing them stays
// that way; the migration does not run again.
func init() {
	m.Register(upDefaultRateLimits, downDefaultRateLimits)
}

func upDefaultRateLimits(app core.App) error {
	settings := app.Settings()
	if settings.RateLimits.Enabled {
		return nil
	}
	settings.RateLimits.Enabled = true
	settings.RateLimits.Rules = ratelimit.Rules()
	return app.Save(settings)
}

// downDefaultRateLimits keeps the rules: they are ordinary operator settings after
// this migration ran, and reverting the migration must not throw a choice away.
func downDefaultRateLimits(core.App) error {
	return nil
}
