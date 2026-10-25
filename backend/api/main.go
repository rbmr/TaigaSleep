package main

import (
	"log"
	"os"

	"github.com/pocketbase/pocketbase"
	"github.com/pocketbase/pocketbase/plugins/migratecmd"

	"github.com/rbmr/TaigaSleep/backend/api/internal/api"
	_ "github.com/rbmr/TaigaSleep/backend/api/migrations"
)

func main() {
	app := pocketbase.New()

	var automigrate bool
	app.RootCmd.PersistentFlags().BoolVar(
		&automigrate,
		"automigrate",
		false,
		"enable/disable auto creation of migration files",
	)

	app.RootCmd.ParseFlags(os.Args[1:])

	migratecmd.MustRegister(app, app.RootCmd, migratecmd.Config{
		TemplateLang: migratecmd.TemplateLangGo,
		Automigrate:  automigrate,
	})

	api.Register(app)

	if err := app.Start(); err != nil {
		log.Fatal(err)
	}
}
