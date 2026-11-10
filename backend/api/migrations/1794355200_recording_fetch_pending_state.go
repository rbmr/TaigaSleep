package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// A recording whose tracker still holds part of the night is fetch_pending: the
// metadata and the files that are already there are uploaded like any other, and
// the API refuses to seal it until the rest has arrived.
func init() {
	m.Register(upFetchPendingState, nil)
}

func upFetchPendingState(app core.App) error {
	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		return err
	}
	state, ok := recordings.Fields.GetByName("state").(*core.SelectField)
	if !ok {
		return nil
	}
	for _, value := range state.Values {
		if value == "fetch_pending" {
			return nil
		}
	}
	state.Values = append(state.Values, "fetch_pending")
	return app.Save(recordings)
}
