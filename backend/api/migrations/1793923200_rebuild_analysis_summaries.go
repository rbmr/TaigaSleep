package migrations

import (
	"bytes"
	"encoding/json"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"github.com/rbmr/TaigaSleep/backend/api/internal/summary"
)

// The analysis summary changed from carrying the old result blocks (metrics,
// quality, sleep_quality) to the compact stage timeline and the score items.
// Rows completed before that change still hold the old summary, so rebuild each
// one from its result with the same derivation the API uses now.
func init() {
	m.Register(rebuildAnalysisSummaries, nil)
}

func rebuildAnalysisSummaries(app core.App) error {
	rows := []struct {
		Id      string `db:"id"`
		Result  string `db:"result"`
		Summary string `db:"summary"`
	}{}
	if err := app.DB().NewQuery(
		"SELECT id, COALESCE(result, '') AS result, COALESCE(summary, '') AS summary FROM analyses",
	).All(&rows); err != nil {
		return err
	}

	for _, row := range rows {
		raw := strings.TrimSpace(row.Result)
		if raw == "" {
			continue
		}
		var result map[string]any
		if err := json.Unmarshal([]byte(raw), &result); err != nil || result == nil {
			continue
		}

		next, err := json.Marshal(summary.Of(json.RawMessage(raw)))
		if err != nil {
			continue
		}
		current, err := normalizeJSON(row.Summary)
		if err == nil && bytes.Equal(current, next) {
			continue
		}

		if _, err := app.DB().NewQuery("UPDATE analyses SET summary = {:summary} WHERE id = {:id}").
			Bind(dbx.Params{"summary": string(next), "id": row.Id}).
			Execute(); err != nil {
			return err
		}
	}
	return nil
}

// normalizeJSON re-encodes a stored JSON value so two equivalent values compare
// equal regardless of how they were built (for example a []map against a []any).
func normalizeJSON(raw string) ([]byte, error) {
	var value any
	if err := json.Unmarshal([]byte(raw), &value); err != nil {
		return nil, err
	}
	return json.Marshal(value)
}
