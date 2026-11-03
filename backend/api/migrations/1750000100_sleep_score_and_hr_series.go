package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// The night statistics redesign adds two result blocks: an epoch-aligned heart
// rate series and the sleep score. Both are optional so results stored before
// this migration remain readable.
func init() {
	m.Register(upSleepScoreAndHrSeries, downSleepScoreAndHrSeries)
}

func upSleepScoreAndHrSeries(app core.App) error {
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	analyses.Fields.Add(
		&core.JSONField{Name: "hr", MaxSize: 1048576},
		&core.JSONField{Name: "sleep_quality", MaxSize: 262144},
	)
	return app.Save(analyses)
}

func downSleepScoreAndHrSeries(app core.App) error {
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	analyses.Fields.RemoveByName("hr")
	analyses.Fields.RemoveByName("sleep_quality")
	return app.Save(analyses)
}
