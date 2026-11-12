package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// A server that holds health data should back itself up without the operator having to
// remember. This migration writes the default schedule once, on the data directory that
// reaches it, and leaves a schedule already stored alone. Afterwards the dashboard owns
// the value, so changing or clearing it stays that way; the migration does not run again.
func init() {
	m.Register(upDefaultBackupSchedule, downDefaultBackupSchedule)
}

const (
	migratedBackupCron = "0 3 * * *"
	migratedBackupKeep = 14
)

func upDefaultBackupSchedule(app core.App) error {
	settings := app.Settings()
	if settings.Backups.Cron != "" {
		return nil
	}
	settings.Backups.Cron = migratedBackupCron
	settings.Backups.CronMaxKeep = migratedBackupKeep
	return app.Save(settings)
}

// downDefaultBackupSchedule keeps the schedule: it is an ordinary operator setting after
// this migration ran, and reverting the migration must not throw a choice away.
func downDefaultBackupSchedule(core.App) error {
	return nil
}
