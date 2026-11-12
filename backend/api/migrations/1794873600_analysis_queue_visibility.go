package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"
)

// A worker learns that an analysis was queued through PocketBase's realtime stream,
// whose access checks are the collection's view rule. It therefore has to see the
// queued analyses; it reads a recording and its files only while it holds the lease of
// one of its analyses, exactly as before.

const (
	analysesQueueListRule = `recording.owner = @request.auth.id || recording.owner = @request.auth.owner || (worker = @request.auth.id && status = "running" && lease_until >= @now) || (@request.auth.collectionName = "workers" && status = "queued")`
	// analysesListRuleBeforeQueue is the rule this migration replaces.
	analysesListRuleBeforeQueue = `recording.owner = @request.auth.id || recording.owner = @request.auth.owner || (worker = @request.auth.id && status = "running" && lease_until >= @now)`
)

func init() {
	m.Register(upAnalysisQueueVisibility, downAnalysisQueueVisibility)
}

func upAnalysisQueueVisibility(app core.App) error {
	return setAnalysesListRule(app, analysesQueueListRule)
}

func downAnalysisQueueVisibility(app core.App) error {
	return setAnalysesListRule(app, analysesListRuleBeforeQueue)
}

func setAnalysesListRule(app core.App, rule string) error {
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	analyses.ListRule = types.Pointer(rule)
	analyses.ViewRule = types.Pointer(rule)
	return app.Save(analyses)
}
