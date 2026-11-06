// Package summary derives the small headline stored next to an analysis result.
package summary

import "encoding/json"

// Of derives the headline stored next to a result: the compact stage timeline,
// and the id and value of every score item. The API writes it when an analysis
// completes, and the migration that rebuilds old summaries uses the same code.
func Of(raw json.RawMessage) map[string]any {
	var full map[string]any
	if err := json.Unmarshal(raw, &full); err != nil {
		return map[string]any{}
	}
	summary := map[string]any{}
	if stages, ok := full["stages"].(map[string]any); ok {
		summary["stages"] = map[string]any{
			"epoch_seconds": stages["epoch_seconds"],
			"start_unix_ms": stages["start_unix_ms"],
			"count":         stages["count"],
			"segments":      stages["segments"],
		}
	}
	scores := []map[string]any{}
	if items, ok := full["items"].([]any); ok {
		for _, entry := range items {
			item, ok := entry.(map[string]any)
			if !ok || item["kind"] != "score" {
				continue
			}
			scores = append(scores, map[string]any{"id": item["id"], "value": item["value"]})
		}
	}
	if len(scores) > 0 {
		summary["scores"] = scores
	}
	return summary
}
