package migrations

import (
	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"
)

// PocketBase evaluates a collection rule as the WHERE of the query the request runs,
// so every term of a rule should be a comparison the database can answer from an
// index. Three were not:
//
//   - an analysis was owned only through its recording, so listing an account's
//     analyses joined recordings for every row;
//   - a worker's access to a recording was a back-relation to the analyses it leases,
//     which a database can only evaluate by walking the recordings table;
//   - the account of an access token was compared through @request.auth.owner, a field
//     of the token's record, which PocketBase resolves as a join.
//
// An analysis now carries its owner, a recording carries the worker whose lease is
// live, and @request.auth.id is the account every role acts for (the API presents a
// token as the account it was made for). The rules are then plain comparisons on the
// collection's own columns, each answered by an index, and a list request no longer
// grows with the rows of other accounts.
const (
	// The account's own recordings, and the one a worker holds the lease of.
	recordingsListRuleIndexed = `owner = @request.auth.id || leased_by = @request.auth.id`
	// A recording is read one by one as well, where the lease can be checked exactly:
	// the answer is a single row reached through its id, so the subquery costs one
	// lookup instead of one per row.
	recordingsViewRuleIndexed = `owner = @request.auth.id || (analyses_via_recording.worker ?= @request.auth.id && analyses_via_recording.status ?= "running" && analyses_via_recording.lease_until ?>= @now)`
	// The account's own analyses, the one a worker holds a live lease of, and the
	// queue a worker is woken by.
	analysesListRuleIndexed = `owner = @request.auth.id || (worker = @request.auth.id && status = "running" && lease_until >= @now) || (@request.auth.collectionName = "workers" && status = "queued")`
)

func init() {
	m.Register(upListIndexes, downListIndexes)
}

func upListIndexes(app core.App) error {
	if err := addAnalysisOwner(app); err != nil {
		return err
	}
	if err := addRecordingLessee(app); err != nil {
		return err
	}

	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		return err
	}
	recordings.ListRule = types.Pointer(recordingsListRuleIndexed)
	recordings.ViewRule = types.Pointer(recordingsViewRuleIndexed)
	if err := app.Save(recordings); err != nil {
		return err
	}

	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	analyses.ListRule = types.Pointer(analysesListRuleIndexed)
	analyses.ViewRule = types.Pointer(analysesListRuleIndexed)
	return app.Save(analyses)
}

func downListIndexes(app core.App) error {
	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		return err
	}
	recordings.ListRule = types.Pointer(recordingsListRule)
	recordings.ViewRule = types.Pointer(recordingsListRule)
	recordings.RemoveIndex("idx_recordings_leased_by")
	recordings.Fields.RemoveByName("leased_by")
	if err := app.Save(recordings); err != nil {
		return err
	}

	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	analyses.ListRule = types.Pointer(analysesQueueListRule)
	analyses.ViewRule = types.Pointer(analysesQueueListRule)
	analyses.RemoveIndex("idx_analyses_owner_created")
	analyses.Fields.RemoveByName("owner")
	return app.Save(analyses)
}

// addAnalysisOwner gives an analysis the account its recording belongs to, filled
// from the recording for every row already stored. The column is what the list rule
// compares, so it is part of the row instead of a join.
func addAnalysisOwner(app core.App) error {
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	users, err := app.FindCollectionByNameOrId("_pb_users_auth_")
	if err != nil {
		return err
	}
	if analyses.Fields.GetByName("owner") == nil {
		analyses.Fields.Add(&core.RelationField{
			Name:          "owner",
			CollectionId:  users.Id,
			CascadeDelete: true,
			MaxSelect:     1,
		})
	}
	analyses.AddIndex("idx_analyses_owner_created", false, "owner, created", "")
	if err := app.Save(analyses); err != nil {
		return err
	}
	return backfillAnalysisOwner(app)
}

// backfillAnalysisOwner copies each analysis's recording owner in batches, so a
// large table is worked through a page at a time and rows already filled are left
// alone.
func backfillAnalysisOwner(app core.App) error {
	const batch = 200
	last := ""
	for {
		rows := []struct {
			Id string `db:"id"`
		}{}
		if err := app.DB().NewQuery(
			"SELECT id FROM analyses WHERE (owner = '' OR owner IS NULL) AND id > {:last} ORDER BY id LIMIT {:limit}",
		).Bind(dbx.Params{"last": last, "limit": batch}).All(&rows); err != nil {
			return err
		}
		if len(rows) == 0 {
			return nil
		}
		for _, row := range rows {
			if _, err := app.DB().NewQuery(
				"UPDATE analyses SET owner = COALESCE((SELECT owner FROM recordings WHERE recordings.id = analyses.recording), '') " +
					"WHERE id = {:id} AND (owner = '' OR owner IS NULL)",
			).Bind(dbx.Params{"id": row.Id}).Execute(); err != nil {
				return err
			}
		}
		last = rows[len(rows)-1].Id
	}
}

// addRecordingLessee gives a recording the worker that holds a live lease of one of
// its analyses. The list rule compares it, and a database cannot answer a
// back-relation for every row a list walks.
func addRecordingLessee(app core.App) error {
	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		return err
	}
	workers, err := app.FindCollectionByNameOrId("workers")
	if err != nil {
		return err
	}
	if recordings.Fields.GetByName("leased_by") == nil {
		recordings.Fields.Add(&core.RelationField{
			Name:         "leased_by",
			CollectionId: workers.Id,
			MaxSelect:    1,
			// Bookkeeping for the list rule, not part of the recording a client reads. It
			// names one worker: a recording whose analyses two workers lease at the same
			// time lists for one of them, and reading it by id keeps the exact check
			// against its analyses.
			Hidden: true,
		})
	}
	recordings.AddIndex("idx_recordings_leased_by", false, "leased_by", "")
	if err := app.Save(recordings); err != nil {
		return err
	}
	_, err = app.DB().NewQuery(
		"UPDATE recordings SET leased_by = COALESCE((SELECT worker FROM analyses " +
			"WHERE analyses.recording = recordings.id AND analyses.status = 'running' " +
			"AND analyses.worker != '' AND analyses.lease_until > {:now} " +
			"ORDER BY analyses.lease_until DESC LIMIT 1), '') " +
			"WHERE leased_by = '' AND id IN (SELECT recording FROM analyses " +
			"WHERE status = 'running' AND worker != '' AND lease_until > {:now})",
	).Bind(dbx.Params{"now": types.NowDateTime().String()}).Execute()
	return err
}
