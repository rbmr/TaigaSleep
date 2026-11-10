package api

import (
	"testing"

	"github.com/pocketbase/pocketbase/core"
)

// TestBootstrapStoresDeploymentSettings starts the app on an empty data directory with
// both deployment variables set and reads the stored settings back.
func TestBootstrapStoresDeploymentSettings(t *testing.T) {
	const (
		appName = "Taiga Test"
		appURL  = "http://127.0.0.1:8090"
	)
	t.Setenv("TAIGA_APP_NAME", appName)
	t.Setenv("TAIGA_APP_URL", appURL)

	app := core.NewBaseApp(core.BaseAppConfig{DataDir: t.TempDir()})
	t.Cleanup(func() { _ = app.ClearBootstrap() })
	Register(app)
	if err := app.Bootstrap(); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}

	// Read the settings from the database instead of the values the hook left in memory.
	if err := app.ReloadSettings(); err != nil {
		t.Fatalf("reload settings: %v", err)
	}
	if got := app.Settings().Meta.AppName; got != appName {
		t.Errorf("stored app name = %q, want %q", got, appName)
	}
	if got := app.Settings().Meta.AppURL; got != appURL {
		t.Errorf("stored app url = %q, want %q", got, appURL)
	}
}
