package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"

	"github.com/rbmr/TaigaSleep/backend/api/internal/storage"
)

// Storage totals keeps the bytes each recording holds on the recording and the sum on
// the account, so the quota check reads one row instead of every recording and file of
// the account. The totals are filled from the files on disk once, which is what they
// would have been had the columns existed before.
func init() {
	m.Register(upStorageTotals, downStorageTotals)
}

func upStorageTotals(app core.App) error {
	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		return err
	}
	if users.Fields.GetByName("storage_bytes") == nil {
		users.Fields.Add(&core.NumberField{Name: "storage_bytes", Min: types.Pointer(0.0)})
		if err := app.Save(users); err != nil {
			return err
		}
	}

	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		return err
	}
	if recordings.Fields.GetByName("stored_bytes") == nil {
		recordings.Fields.Add(&core.NumberField{Name: "stored_bytes", Min: types.Pointer(0.0)})
		if err := app.Save(recordings); err != nil {
			return err
		}
	}

	_, _, err = storage.Reconcile(app)
	return err
}

func downStorageTotals(app core.App) error {
	for _, name := range []string{"recordings", "users"} {
		collection, err := app.FindCollectionByNameOrId(name)
		if err != nil {
			continue
		}
		field := "stored_bytes"
		if name == "users" {
			field = "storage_bytes"
		}
		collection.Fields.RemoveByName(field)
		if err := app.Save(collection); err != nil {
			return err
		}
	}
	return nil
}
