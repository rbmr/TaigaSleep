package api

import (
	"slices"
	"testing"

	"github.com/pocketbase/pocketbase/core"
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

// rateLimitFor looks a rule up the way PocketBase's limiter does, with both labels it
// tries: "METHOD /path" first and then the path.
func rateLimitFor(settings *core.Settings, method, path string) (core.RateLimitRule, bool) {
	return settings.RateLimits.FindRateLimitRule([]string{method + " " + path, path})
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

// TestBootstrapConfiguresPocketBaseSettings checks the settings an empty data directory
// gets when no variable names a value.
func TestBootstrapConfiguresPocketBaseSettings(t *testing.T) {
	app := bootedApp(t, t.TempDir())
	settings := app.Settings()

	if got := settings.TrustedProxy.Headers; !slices.Equal(got, []string{defaultTrustedProxyHeaders}) {
		t.Errorf("trusted proxy headers = %v, want %q", got, defaultTrustedProxyHeaders)
	}
	if !settings.RateLimits.Enabled {
		t.Error("the rate limiter is disabled")
	}
	if rule, ok := rateLimitFor(settings, "POST", "/v1/auth/login"); !ok ||
		rule.MaxRequests != loginRateLimit || rule.Duration != loginRateLimitWindow {
		t.Errorf("login rule = %+v (%v), want %d per %d s", rule, ok, loginRateLimit, loginRateLimitWindow)
	}
	if rule, ok := rateLimitFor(settings, "PATCH", "/v1/recordings/x/files/hr.log"); !ok ||
		rule.MaxRequests != uploadRateLimit || rule.Duration != uploadRateLimitWindow {
		t.Errorf("upload rule = %+v (%v), want %d per %d s", rule, ok, uploadRateLimit, uploadRateLimitWindow)
	}
	if rule, ok := rateLimitFor(settings, "GET", "/v1/recordings"); !ok ||
		rule.MaxRequests != apiRateLimit || rule.Duration != apiRateLimitWindow {
		t.Errorf("API rule = %+v (%v), want %d per %d s", rule, ok, apiRateLimit, apiRateLimitWindow)
	}
	if got := settings.Backups.Cron; got != defaultBackupCron {
		t.Errorf("backup cron = %q, want %q", got, defaultBackupCron)
	}
	if got := settings.Backups.CronMaxKeep; got != defaultBackupKeep {
		t.Errorf("backup keep = %d, want %d", got, defaultBackupKeep)
	}
}

// TestBootstrapTrustsOnlyConfiguredProxyHeaders reads the addresses the API is told to
// trust from the environment; an empty value means clients connect directly.
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

// TestBackupSettingsOutliveARestart keeps what the operator set in the dashboard: the
// default only fills an empty value, and a variable overrides only what it names.
func TestBackupSettingsOutliveARestart(t *testing.T) {
	const (
		operatorCron = "15 4 * * *"
		operatorKeep = 30
	)
	dir := t.TempDir()

	first := bootedApp(t, dir)
	if got := first.Settings().Backups; got.Cron != defaultBackupCron || got.CronMaxKeep != defaultBackupKeep {
		t.Fatalf("fresh backups = %+v, want the defaults", got)
	}
	settings := first.Settings()
	settings.Backups.Cron = operatorCron
	settings.Backups.CronMaxKeep = operatorKeep
	if err := first.Save(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	if err := first.ClearBootstrap(); err != nil {
		t.Fatalf("stop the first instance: %v", err)
	}

	restarted := bootedApp(t, dir)
	if got := restarted.Settings().Backups; got.Cron != operatorCron || got.CronMaxKeep != operatorKeep {
		t.Errorf("restarted backups = %+v, want %q and %d", got, operatorCron, operatorKeep)
	}
}

// TestBackupVariablesOverrideTheStoredSettings checks that the two variables win over
// what the operator set, and that an empty schedule turns automatic backups off.
func TestBackupVariablesOverrideTheStoredSettings(t *testing.T) {
	t.Setenv("TAIGA_BACKUP_CRON", "30 1 * * *")
	t.Setenv("TAIGA_BACKUP_KEEP", "7")
	app := bootedApp(t, t.TempDir())
	if got := app.Settings().Backups; got.Cron != "30 1 * * *" || got.CronMaxKeep != 7 {
		t.Errorf("backups = %+v, want the variables", got)
	}

	t.Setenv("TAIGA_BACKUP_CRON", "")
	off := bootedApp(t, t.TempDir())
	if got := off.Settings().Backups; got.Cron != "" || got.CronMaxKeep != 7 {
		t.Errorf("backups = %+v, want automatic backups off", got)
	}
}
