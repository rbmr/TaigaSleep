package main

import (
	"fmt"

	"github.com/pocketbase/pocketbase/core"
	"github.com/spf13/cobra"

	"github.com/rbmr/TaigaSleep/backend/api/internal/storage"
)

// storageCheckCommand compares the stored byte totals with the files on disk and
// corrects them. The API keeps them correct as it writes; the command is for a total
// that drifted, for example after files were changed outside the API. It writes rows,
// so it expects the API to be stopped.
func storageCheckCommand(app core.App) *cobra.Command {
	var removeOrphans bool
	command := &cobra.Command{
		Use:          "storage-check",
		Short:        "Compare the storage totals with the files on disk and repair them",
		SilenceUsage: true,
		RunE: func(command *cobra.Command, args []string) error {
			// The totals live in columns a migration adds, so the command is also
			// usable before the first start of a new version.
			if err := app.RunAllMigrations(); err != nil {
				return err
			}
			report, err := storage.Check(app, storage.Options{RemoveOrphans: removeOrphans})
			if err != nil {
				return err
			}
			out := command.OutOrStdout()
			fmt.Fprintf(out, "storage: corrected %d recordings, %d accounts and %d compressed files\n",
				report.Recordings, report.Accounts, report.Files)
			if len(report.Orphans) == 0 {
				return nil
			}
			if report.Removed == len(report.Orphans) {
				fmt.Fprintf(out, "removed %d directories without a recording\n", report.Removed)
				return nil
			}
			fmt.Fprintf(out, "%d directories without a recording:\n", len(report.Orphans))
			for _, name := range report.Orphans {
				fmt.Fprintf(out, "  recordings/%s\n", name)
			}
			if report.Removed > 0 {
				fmt.Fprintf(out, "removed %d of them\n", report.Removed)
			} else {
				fmt.Fprintf(out, "pass --remove-orphans to remove them\n")
			}
			return nil
		},
	}
	command.Flags().BoolVar(&removeOrphans, "remove-orphans", false,
		"Remove directories under recordings/ that have no recording row")
	return command
}
