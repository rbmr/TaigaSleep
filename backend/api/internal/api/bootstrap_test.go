package api

import (
	"os"
	"slices"
	"testing"

	"github.com/pocketbase/pocketbase/core"

	_ "github.com/rbmr/TaigaSleep/backend/api/migrations"
)

// bootedApp starts the API on the data directory and returns the app with its settings
// reloaded from the database, so a test reads what was stored rather than what the hook
// left in memory.
func bootedApp(t *testing.T, dataDir string) *core.BaseApp {
	t.Helper()
	app := core.NewBaseApp(core.BaseAppConfig{DataDir: dataDir})
	t.Cleanup(func() { _ = app.ClearBootstrap() })
	Register(app)
	if err := app.Bootstrap(); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}
	if err := app.ReloadSettings(); err != nil {
		t.Fatalf("reload settings: %v", err)
	}
	return app
}

// migratedApp starts the API the way `serve` does: the bootstrap applies the environment
// and the migrations then run.
func migratedApp(t *testing.T, dataDir string) *core.BaseApp {
	t.Helper()
	app := bootedApp(t, dataDir)
	if err := app.RunAllMigrations(); err != nil {
		t.Fatalf("migrations: %v", err)
	}
	if err := app.ReloadSettings(); err != nil {
		t.Fatalf("reload settings: %v", err)
	}
	return app
}

// clearEnv removes the variables for the duration of the test, so a test that depends on
// their absence is not decided by the shell it runs in.
func clearEnv(t *testing.T, names ...string) {
	t.Helper()
	for _, name := range names {
		value, set := os.LookupEnv(name)
		if err := os.Unsetenv(name); err != nil {
			t.Fatalf("unset %s: %v", name, err)
		}
		t.Cleanup(func() {
			if set {
				_ = os.Setenv(name, value)
			}
		})
	}
}

// rateLimitFor looks a rule up the way PocketBase's limiter does, with both labels it
// tries: "METHOD /path" first and then the path.
func rateLimitFor(settings *core.Settings, method, path string) (core.RateLimitRule, bool) {
	return settings.RateLimits.FindRateLimitRule([]string{method + " " + path, path})
}

// rateLimitByLabel finds one of the API's own rules by its exact label; PocketBase's
// collection routes look their limit up by a collection action label.
func rateLimitByLabel(settings *core.Settings, label string) (core.RateLimitRule, bool) {
	for _, rule := range settings.RateLimits.Rules {
		if rule.Label == label {
			return rule, true
		}
	}
	return core.RateLimitRule{}, false
}

// TestBootstrapStoresDeploymentSettings starts the app on an empty data directory with
// both deployment variables set and reads the stored settings back.
func TestBootstrapStoresDeploymentSettings(t *testing.T) {
	const (
		appName = "Taiga Test"
		appURL  = "http://127.0.0.1:8090"
	)
	t.Setenv("TAIGA_APP_NAME", appName)
	t.Setenv("TAIGA_APP_URL", appURL)

	app := bootedApp(t, t.TempDir())

	if got := app.Settings().Meta.AppName; got != appName {
		t.Errorf("stored app name = %q, want %q", got, appName)
	}
	if got := app.Settings().Meta.AppURL; got != appURL {
		t.Errorf("stored app url = %q, want %q", got, appURL)
	}
}

// TestBootstrapEnablesTheRateLimiter checks the rules an empty data directory gets.
func TestBootstrapEnablesTheRateLimiter(t *testing.T) {
	settings := bootedApp(t, t.TempDir()).Settings()

	if !settings.RateLimits.Enabled {
		t.Error("the rate limiter is disabled")
	}
	if rule, ok := rateLimitByLabel(settings, "*:authWithPassword"); !ok ||
		rule.MaxRequests != loginRateLimit || rule.Duration != loginRateLimitWindow {
		t.Errorf("password login rule = %+v (%v), want %d per %d s", rule, ok, loginRateLimit, loginRateLimitWindow)
	}
	if rule, ok := rateLimitFor(settings, "PATCH", "/v1/recordings/x/files/hr.log"); !ok ||
		rule.MaxRequests != uploadRateLimit || rule.Duration != uploadRateLimitWindow {
		t.Errorf("upload rule = %+v (%v), want %d per %d s", rule, ok, uploadRateLimit, uploadRateLimitWindow)
	}
	if rule, ok := rateLimitFor(settings, "GET", "/v1/recordings"); !ok ||
		rule.MaxRequests != apiRateLimit || rule.Duration != apiRateLimitWindow {
		t.Errorf("API rule = %+v (%v), want %d per %d s", rule, ok, apiRateLimit, apiRateLimitWindow)
	}
}

// TestBootstrapTrustsOnlyConfiguredProxyHeaders reads the headers the API may take the
// client address from out of the environment.
func TestBootstrapTrustsOnlyConfiguredProxyHeaders(t *testing.T) {
	t.Setenv("TAIGA_TRUSTED_PROXY_HEADERS", "X-Real-IP, CF-Connecting-IP")
	behind := bootedApp(t, t.TempDir())
	if got := behind.Settings().TrustedProxy.Headers; !slices.Equal(got, []string{"X-Real-IP", "CF-Connecting-IP"}) {
		t.Errorf("trusted proxy headers = %v", got)
	}

	t.Setenv("TAIGA_TRUSTED_PROXY_HEADERS", "")
	direct := bootedApp(t, t.TempDir())
	if got := direct.Settings().TrustedProxy.Headers; len(got) != 0 {
		t.Errorf("trusted proxy headers = %v, want none", got)
	}
}

// TestBootstrapTrustsNoProxyHeaderByDefault keeps a header nobody configured out of the
// settings, including one an earlier start stored: a client must not be able to choose
// the address it is logged and limited as.
func TestBootstrapTrustsNoProxyHeaderByDefault(t *testing.T) {
	t.Setenv("TAIGA_TRUSTED_PROXY_HEADERS", "")
	dir := t.TempDir()

	first := bootedApp(t, dir)
	settings := first.Settings()
	settings.TrustedProxy.Headers = []string{"X-Forwarded-For"}
	if err := first.Save(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	if err := first.ClearBootstrap(); err != nil {
		t.Fatalf("stop the first instance: %v", err)
	}

	restarted := bootedApp(t, dir)
	if got := restarted.Settings().TrustedProxy.Headers; len(got) != 0 {
		t.Errorf("trusted proxy headers = %v, want none", got)
	}
}

// TestBackupDefaultsAreSetOnceAndTheOperatorKeepsTheChoice follows a data directory from
// its first start: the migration writes the schedule, and what the operator changes or
// clears in the dashboard afterwards is not written over by a later start.
func TestBackupDefaultsAreSetOnceAndTheOperatorKeepsTheChoice(t *testing.T) {
	clearEnv(t, "TAIGA_BACKUP_CRON", "TAIGA_BACKUP_KEEP")
	dir := t.TempDir()

	fresh := migratedApp(t, dir)
	if got := fresh.Settings().Backups; got.Cron != "0 3 * * *" || got.CronMaxKeep != 14 {
		t.Fatalf("fresh backups = %+v, want 0 3 * * * and 14", got)
	}

	// The operator changes the schedule and the limit in the dashboard.
	settings := fresh.Settings()
	settings.Backups.Cron = "15 4 * * *"
	settings.Backups.CronMaxKeep = 30
	if err := fresh.Save(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	if err := fresh.ClearBootstrap(); err != nil {
		t.Fatalf("stop the first instance: %v", err)
	}

	restarted := migratedApp(t, dir)
	if got := restarted.Settings().Backups; got.Cron != "15 4 * * *" || got.CronMaxKeep != 30 {
		t.Errorf("restarted backups = %+v, want the operator's values", got)
	}

	// Clearing the schedule turns automatic backups off, and that stays too.
	settings = restarted.Settings()
	settings.Backups.Cron = ""
	if err := restarted.Save(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	if err := restarted.ClearBootstrap(); err != nil {
		t.Fatalf("stop the second instance: %v", err)
	}

	cleared := migratedApp(t, dir)
	if got := cleared.Settings().Backups.Cron; got != "" {
		t.Errorf("backup cron after clearing = %q, want none", got)
	}
}

// TestBackupVariablesOverrideTheStoredSettings checks that the two variables win over
// what is stored, and that an empty schedule turns automatic backups off.
func TestBackupVariablesOverrideTheStoredSettings(t *testing.T) {
	clearEnv(t, "TAIGA_BACKUP_CRON", "TAIGA_BACKUP_KEEP")
	dir := t.TempDir()

	first := migratedApp(t, dir)
	if err := first.ClearBootstrap(); err != nil {
		t.Fatalf("stop the first instance: %v", err)
	}

	t.Setenv("TAIGA_BACKUP_CRON", "30 1 * * *")
	t.Setenv("TAIGA_BACKUP_KEEP", "7")
	overridden := bootedApp(t, dir)
	if got := overridden.Settings().Backups; got.Cron != "30 1 * * *" || got.CronMaxKeep != 7 {
		t.Errorf("backups = %+v, want the variables", got)
	}
	if err := overridden.ClearBootstrap(); err != nil {
		t.Fatalf("stop the second instance: %v", err)
	}

	t.Setenv("TAIGA_BACKUP_CRON", "")
	off := bootedApp(t, dir)
	if got := off.Settings().Backups.Cron; got != "" {
		t.Errorf("backup cron = %q, want automatic backups off", got)
	}
}

// TestDisabledBackupsOnAFreshDataDirStayOff covers the first start with the variable that
// turns them off: the migration runs after the bootstrap and must not switch them on.
func TestDisabledBackupsOnAFreshDataDirStayOff(t *testing.T) {
	t.Setenv("TAIGA_BACKUP_CRON", "")
	app := migratedApp(t, t.TempDir())
	if got := app.Settings().Backups.Cron; got != "" {
		t.Errorf("backup cron = %q, want automatic backups off", got)
	}
}
