package migrations

import (
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/security"
	"github.com/pocketbase/pocketbase/tools/types"

	"github.com/rbmr/TaigaSleep/backend/api/internal/results"
)

// The analyses table is also the queue. Its result column held up to 4 MB per row,
// so sorting or filtering queue rows walked SQLite overflow pages for every result.
// Results now live in their own collection, one row per analysis, and the API still
// returns the result in the same field when one analysis is read by id.
//
// The migration copies existing results in batches, so it never loads the whole
// table into memory, and then removes the column from the queue table.
const (
	analysisResultsCollectionID = "pbc_2468101214"
	analysisResultBatch         = 10
)

func init() {
	m.Register(upAnalysisResults, downAnalysisResults)
}

func upAnalysisResults(app core.App) error {
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	if err := ensureAnalysisResults(app); err != nil {
		return err
	}
	if analyses.Fields.GetByName("result") == nil {
		return nil
	}
	if err := moveAnalysisResults(app); err != nil {
		return err
	}
	analyses.Fields.RemoveByName("result")
	return app.Save(analyses)
}

func downAnalysisResults(app core.App) error {
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	if analyses.Fields.GetByName("result") == nil {
		analyses.Fields.Add(&core.JSONField{Name: "result", MaxSize: results.MaxBytes})
		if err := app.Save(analyses); err != nil {
			return err
		}
	}
	collection, err := app.FindCollectionByNameOrId("analysis_results")
	if err != nil {
		return nil
	}
	if err := restoreAnalysisResults(app); err != nil {
		return err
	}
	return app.Delete(collection)
}

// ensureAnalysisResults creates the result collection once. The relation cascades,
// so deleting an analysis, a recording or an account takes its result with it.
func ensureAnalysisResults(app core.App) error {
	if _, err := app.FindCollectionByNameOrId("analysis_results"); err == nil {
		return nil
	}
	analyses, err := app.FindCollectionByNameOrId("analyses")
	if err != nil {
		return err
	}
	resultsCollection := core.NewBaseCollection("analysis_results", analysisResultsCollectionID)
	resultsCollection.Fields.Add(
		&core.RelationField{
			Name:          "analysis",
			Required:      true,
			CollectionId:  analyses.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		&core.JSONField{Name: "result", Required: true, MaxSize: results.MaxBytes},
		&core.AutodateField{Name: "created", OnCreate: true},
	)
	resultsCollection.AddIndex("idx_analysis_results_analysis", true, "analysis", "")
	return app.Save(resultsCollection)
}

// moveAnalysisResults copies one page of results at a time and leaves a row that is
// already there alone, so a migration that was stopped half way can finish.
func moveAnalysisResults(app core.App) error {
	last := ""
	for {
		rows := []struct {
			Id     string `db:"id"`
			Result string `db:"result"`
		}{}
		if err := app.DB().NewQuery(
			"SELECT id, result FROM analyses WHERE result IS NOT NULL AND id > {:last} ORDER BY id LIMIT {:limit}",
		).Bind(dbx.Params{"last": last, "limit": analysisResultBatch}).All(&rows); err != nil {
			return err
		}
		if len(rows) == 0 {
			return nil
		}
		for _, row := range rows {
			raw := strings.TrimSpace(row.Result)
			if raw == "" || raw == "null" {
				continue
			}
			if _, err := app.DB().NewQuery(
				"INSERT INTO analysis_results (id, analysis, result, created) " +
					"SELECT {:id}, {:analysis}, {:result}, {:created} " +
					"WHERE NOT EXISTS (SELECT 1 FROM analysis_results WHERE analysis = {:analysis})",
			).Bind(dbx.Params{
				"id":       security.RandomString(15),
				"analysis": row.Id,
				"result":   raw,
				"created":  types.NowDateTime().String(),
			}).Execute(); err != nil {
				return err
			}
		}
		last = rows[len(rows)-1].Id
	}
}

// restoreAnalysisResults puts the results back into the queue table for the down
// migration, one page at a time as well.
func restoreAnalysisResults(app core.App) error {
	last := ""
	for {
		rows := []struct {
			Id       string `db:"id"`
			Analysis string `db:"analysis"`
			Result   string `db:"result"`
		}{}
		if err := app.DB().NewQuery(
			"SELECT id, analysis, result FROM analysis_results WHERE id > {:last} ORDER BY id LIMIT {:limit}",
		).Bind(dbx.Params{"last": last, "limit": analysisResultBatch}).All(&rows); err != nil {
			return err
		}
		if len(rows) == 0 {
			return nil
		}
		for _, row := range rows {
			if _, err := app.DB().NewQuery(
				"UPDATE analyses SET result = {:result} WHERE id = {:analysis}",
			).Bind(dbx.Params{"result": row.Result, "analysis": row.Analysis}).Execute(); err != nil {
				return err
			}
		}
		last = rows[len(rows)-1].Id
	}
}
