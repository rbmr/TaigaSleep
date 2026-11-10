package main

import (
	"fmt"

	"github.com/pocketbase/pocketbase/core"
	"github.com/spf13/cobra"

	"github.com/rbmr/TaigaSleep/backend/api/internal/storage"
)

// storageCheckCommand recomputes the stored byte totals from the files on disk. The
// API keeps them correct as it writes; the command is for a total that drifted, for
// example after files were changed outside the API. It writes rows, so it expects the
// API to be stopped.
func storageCheckCommand(app core.App) *cobra.Command {
	return &cobra.Command{
		Use:          "storage-check",
		Short:        "Recompute the storage totals from the files on disk",
		SilenceUsage: true,
		RunE: func(command *cobra.Command, args []string) error {
			// The totals live in columns a migration adds, so the command is also
			// usable before the first start of a new version.
			if err := app.RunAllMigrations(); err != nil {
				return err
			}
			recordings, accounts, err := storage.Reconcile(app)
			if err != nil {
				return err
			}
			fmt.Fprintf(command.OutOrStdout(), "storage totals: corrected %d recordings and %d accounts\n", recordings, accounts)
			return nil
		},
	}
}
