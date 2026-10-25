package main

import (
	"log"
	"os"
	"strconv"

	"github.com/pocketbase/pocketbase"
	"github.com/pocketbase/pocketbase/plugins/jsvm"
	"github.com/pocketbase/pocketbase/plugins/migratecmd"

	"github.com/rbmr/TaigaSleep/backend/api/internal/api"
)

func main() {
	app := pocketbase.New()

	// Keep the stock flags so the deployment, tests and docs stay familiar.
	// The JSVM is currently used only for the existing migrations; the custom
	// routes and record hooks are Go.
	var hooksDir string
	app.RootCmd.PersistentFlags().StringVar(
		&hooksDir,
		"hooksDir",
		"",
		"the directory with the JS app hooks",
	)

	var hooksWatch bool
	app.RootCmd.PersistentFlags().BoolVar(
		&hooksWatch,
		"hooksWatch",
		true,
		"auto restart the app on pb_hooks file change",
	)

	hooksPool := 1
	if value := os.Getenv("TAIGA_HOOKS_POOL"); value != "" {
		if parsed, err := strconv.Atoi(value); err == nil && parsed > 0 {
			hooksPool = parsed
		}
	}
	var hooksPoolFlag int
	app.RootCmd.PersistentFlags().IntVar(
		&hooksPoolFlag,
		"hooksPool",
		hooksPool,
		"the total prewarm goja.Runtime instances for the JS migrations",
	)

	var migrationsDir string
	app.RootCmd.PersistentFlags().StringVar(
		&migrationsDir,
		"migrationsDir",
		"",
		"the directory with the user defined migrations",
	)

	var automigrate bool
	app.RootCmd.PersistentFlags().BoolVar(
		&automigrate,
		"automigrate",
		false,
		"enable/disable auto creation of migration files",
	)

	app.RootCmd.ParseFlags(os.Args[1:])

	jsvm.MustRegister(app, jsvm.Config{
		MigrationsDir: migrationsDir,
		HooksDir:      hooksDir,
		HooksWatch:    hooksWatch,
		HooksPoolSize: hooksPoolFlag,
	})
	migratecmd.MustRegister(app, app.RootCmd, migratecmd.Config{
		TemplateLang: migratecmd.TemplateLangJS,
		Automigrate:  automigrate,
		Dir:          migrationsDir,
	})

	api.Register(app)

	if err := app.Start(); err != nil {
		log.Fatal(err)
	}
}
