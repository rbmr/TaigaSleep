package migrations

import (
	"testing"

	"github.com/pocketbase/pocketbase/core"

	"github.com/rbmr/TaigaSleep/backend/api/internal/ratelimit"
)

// rulesByLabel indexes the stored rate-limit rules by label. Every default rule has
// no audience, so the label names one rule here.
func rulesByLabel(app core.App) map[string]core.RateLimitRule {
	rules := make(map[string]core.RateLimitRule, len(app.Settings().RateLimits.Rules))
	for _, rule := range app.Settings().RateLimits.Rules {
		rules[rule.Label] = rule
	}
	return rules
}

// TestRecordAPIRateLimitsAreAddedOnceWithTheDefaults starts from the rules the first
// rate-limit migration left, with one of them changed the way an operator would, and
// checks that this migration adds the record API and realtime rules without touching
// that choice. A second run adds nothing.
func TestRecordAPIRateLimitsAreAddedOnceWithTheDefaults(t *testing.T) {
	app := newMigrationTestApp(t)

	settings := app.Settings()
	settings.RateLimits.Enabled = true
	settings.RateLimits.Rules = ratelimit.Rules()
	for i := range settings.RateLimits.Rules {
		if settings.RateLimits.Rules[i].Label == "*:authWithPassword" {
			settings.RateLimits.Rules[i].MaxRequests = 7
			settings.RateLimits.Rules[i].Duration = 90
		}
	}
	if err := app.Save(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	if err := app.ReloadSettings(); err != nil {
		t.Fatalf("reload settings: %v", err)
	}

	if err := upRecordAPIRateLimits(app); err != nil {
		t.Fatalf("migration: %v", err)
	}
	rules := rulesByLabel(app)
	for _, want := range ratelimit.RecordRules() {
		got, ok := rules[want.Label]
		if !ok {
			t.Errorf("rule %q was not added", want.Label)
			continue
		}
		if got.MaxRequests != want.MaxRequests || got.Duration != want.Duration {
			t.Errorf("rule %q = %d per %d s, want %d per %d s",
				want.Label, got.MaxRequests, got.Duration, want.MaxRequests, want.Duration)
		}
	}
	if got := rules["*:authWithPassword"]; got.MaxRequests != 7 || got.Duration != 90 {
		t.Errorf("the operator's login rule = %+v, want 7 per 90 s", got)
	}

	// A second run finds everything and changes nothing.
	count := len(app.Settings().RateLimits.Rules)
	if err := upRecordAPIRateLimits(app); err != nil {
		t.Fatalf("second migration: %v", err)
	}
	if got := len(app.Settings().RateLimits.Rules); got != count {
		t.Errorf("rules after a second run = %d, want %d", got, count)
	}
}
