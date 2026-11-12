package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"
)

// PocketBase's record API is the way clients read and write recordings and
// analyses. Its rules decide what a caller may see and change on every route,
// including filtering, sorting, pagination and relation expansion; the checks
// that need more than a filter (sealing a recording, validating an analysis
// request) run as request hooks in the API package.
//
// Every rule of a collection is set here, together with the field constraints
// the schema can express. The account that owns a recording is the only one who
// manages it; a read-only access token reads for its owner; a worker reads only
// while it holds a live lease of an analysis of the recording.

const (
	// A user's own recordings, an access token's owner's, or a recording whose
	// running, leased analysis belongs to this worker.
	recordingsListRule = `owner = @request.auth.id || owner = @request.auth.owner || (analyses_via_recording.worker ?= @request.auth.id && analyses_via_recording.status ?= "running" && analyses_via_recording.lease_until ?>= @now)`
	// The same records can be viewed one by one.
	recordingsViewRule = recordingsListRule
	// Only a user creates a recording; the owner and the derived fields are set
	// by the create hook, so the rule only decides who may call it.
	recordingsCreateRule = `@request.auth.collectionName = "users"`
	// A record is managed by the account that owns it and by nobody else: a
	// worker has its lease for reading, an access token never writes.
	recordingsUpdateRule = `@request.auth.collectionName = "users" && owner = @request.auth.id`
	recordingsDeleteRule = recordingsUpdateRule

	// An analysis is visible through its recording's owner, or to the worker that
	// holds a live lease of it.
	analysesListRule = `recording.owner = @request.auth.id || recording.owner = @request.auth.owner || (worker = @request.auth.id && status = "running" && lease_until >= @now)`
	analysesViewRule = analysesListRule
	// Only the recording's owner asks for an analysis.
	analysesCreateRule = `@request.auth.collectionName = "users" && recording.owner = @request.auth.id`
	// Nobody changes an analysis through the record API once it exists: the
	// worker queue's own routes are what fill it in.
	analysesDeleteRule = analysesCreateRule
)

func init() {
	m.Register(upRecordAPIRules, downRecordAPIRules)
}

func upRecordAPIRules(app core.App) error {
	recordings, err := app.FindCollectionByNameOrId("recordings")
	if err != nil {
		return err
	}
	recordings.ListRule = types.Pointer(recordingsListRule)
	recordings.ViewRule = types.Pointer(recordingsViewRule)
	recordings.CreateRule = types.Pointer(recordingsCreateRule)
	recordings.UpdateRule = types.Pointer(recordingsUpdateRule)
	recordings.DeleteRule = types.Pointer(recordingsDeleteRule)
	// A recording without its meta.json has nothing to show, so the schema
	// requires it like the hook's validation does.
	if meta, ok := recordings.Fields.GetByName("meta").(*core.JSONField); ok {
		meta.Required = true
	}
	if err := app.Save(recordings); err != nil {
		return err
	}

	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	analyses.ListRule = types.Pointer(analysesListRule)
	analyses.ViewRule = types.Pointer(analysesViewRule)
	analyses.CreateRule = types.Pointer(analysesCreateRule)
	// An analysis is immutable through the API; the worker queue routes update
	// it as the server, not as a client.
	analyses.UpdateRule = nil
	analyses.DeleteRule = types.Pointer(analysesDeleteRule)
	// The shape of a request is part of the schema where a field can express it:
	// an algorithm id, and the 16 hex characters of a parameter hash.
	if algorithm, ok := analyses.Fields.GetByName("algorithm").(*core.TextField); ok {
		algorithm.Pattern = `^[a-z0-9][a-z0-9._-]{0,63}$`
	}
	if hash, ok := analyses.Fields.GetByName("params_hash").(*core.TextField); ok {
		hash.Max = 16
		hash.Pattern = `^[0-9a-f]{16}$`
	}
	// A worker's lease lookup is the rule's hot path: its analyses by worker,
	// status and lease end.
	analyses.AddIndex("idx_analyses_worker_lease", false, "worker, status, lease_until", "")
	return app.Save(analyses)
}

func downRecordAPIRules(app core.App) error {
	for _, name := range []string{"recordings", "analyses"} {
		collection, err := app.FindCollectionByNameOrId(name)
		if err != nil {
			continue
		}
		collection.ListRule, collection.ViewRule = nil, nil
		collection.CreateRule, collection.UpdateRule, collection.DeleteRule = nil, nil, nil
		if err := app.Save(collection); err != nil {
			return err
		}
	}

	if recordings, err := app.FindCollectionByNameOrId("recordings"); err == nil {
		if meta, ok := recordings.Fields.GetByName("meta").(*core.JSONField); ok {
			meta.Required = false
		}
		if err := app.Save(recordings); err != nil {
			return err
		}
	}
	if analyses, err := app.FindCollectionByNameOrId("analyses"); err == nil {
		analyses.RemoveIndex("idx_analyses_worker_lease")
		if algorithm, ok := analyses.Fields.GetByName("algorithm").(*core.TextField); ok {
			algorithm.Pattern = ""
		}
		if hash, ok := analyses.Fields.GetByName("params_hash").(*core.TextField); ok {
			hash.Max = 32
			hash.Pattern = ""
		}
		if err := app.Save(analyses); err != nil {
			return err
		}
	}
	return nil
}
