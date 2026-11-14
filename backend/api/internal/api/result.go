package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/rbmr/TaigaSleep/backend/api/internal/summary"
)

// The result of an analysis is stored as the worker sent it. The API only checks the
// envelope the app and the contract rely on (see docs/analysis-contract.md), so an
// algorithm can add whatever else it produces.

type stageSegment struct {
	Stage string `json:"stage"`
	Count int    `json:"count"`
}

type stageTimeline struct {
	EpochSeconds int            `json:"epoch_seconds"`
	StartUnixMs  int64          `json:"start_unix_ms"`
	Count        int            `json:"count"`
	Segments     []stageSegment `json:"segments"`
}

type scoreComponent struct {
	ID     string  `json:"id"`
	Label  string  `json:"label"`
	Weight float64 `json:"weight"`
	Value  float64 `json:"value"`
}

// resultItem is one typed output: a number, a series or a score. Every item describes itself,
// so a client can render it without knowing the algorithm's metric keys.
type resultItem struct {
	Kind        string           `json:"kind"`
	ID          string           `json:"id"`
	Label       string           `json:"label"`
	Group       string           `json:"group"`
	Unit        string           `json:"unit"`
	Value       *float64         `json:"value,omitempty"`
	Explanation string           `json:"explanation"`
	Sources     []string         `json:"sources"`
	Series      json.RawMessage  `json:"series,omitempty"`
	Components  []scoreComponent `json:"components,omitempty"`
}

type resultEnvelope struct {
	ContractVersion int            `json:"contract_version"`
	Model           string         `json:"model"`
	ModelVersion    string         `json:"model_version"`
	Stages          stageTimeline  `json:"stages"`
	Items           []resultItem   `json:"items"`
	Capabilities    map[string]any `json:"capabilities"`
	Provenance      map[string]any `json:"provenance"`
}

func hashHex(raw []byte) string {
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])[:16]
}

func parseResult(raw json.RawMessage) (resultEnvelope, error) {
	var result resultEnvelope
	if err := json.Unmarshal(raw, &result); err != nil {
		return result, fmt.Errorf("result is not a valid object: %w", err)
	}
	if result.ContractVersion == 0 {
		return result, fmt.Errorf("analysis result is missing contract_version")
	}
	if result.Model == "" || result.ModelVersion == "" {
		return result, fmt.Errorf("analysis result is missing model identity")
	}
	if result.Stages.Count < 1 || len(result.Stages.Segments) == 0 {
		return result, fmt.Errorf("analysis result has no stage timeline")
	}
	covered := 0
	for _, segment := range result.Stages.Segments {
		if segment.Stage == "" || segment.Count < 1 {
			return result, fmt.Errorf("analysis result has an invalid stage segment")
		}
		covered += segment.Count
	}
	if covered != result.Stages.Count {
		return result, fmt.Errorf("stage segments do not cover every 30-second interval")
	}
	if result.ContractVersion < 3 {
		return result, fmt.Errorf("analysis result uses contract version %d, want 3", result.ContractVersion)
	}
	if result.Capabilities == nil || result.Provenance == nil {
		return result, fmt.Errorf("analysis result is missing capabilities or provenance")
	}
	if err := validateItems(result.Items); err != nil {
		return result, err
	}
	return result, nil
}

func validateItems(items []resultItem) error {
	if len(items) == 0 {
		return fmt.Errorf("analysis result has no typed items")
	}
	seen := make(map[string]bool, len(items))
	for _, item := range items {
		if item.ID == "" || item.Label == "" {
			return fmt.Errorf("analysis result has an item without id or label")
		}
		if seen[item.ID] {
			return fmt.Errorf("analysis result repeats item %q", item.ID)
		}
		seen[item.ID] = true
		if len(item.Sources) == 0 {
			return fmt.Errorf("analysis result item %q has no data source", item.ID)
		}
		switch item.Kind {
		case "number":
			if item.Value == nil {
				return fmt.Errorf("analysis result number %q has no value", item.ID)
			}
		case "series":
			if len(item.Series) == 0 {
				return fmt.Errorf("analysis result series %q has no data", item.ID)
			}
		case "score":
			if item.Value == nil || *item.Value < 0 || *item.Value > 100 {
				return fmt.Errorf("analysis result score %q must be between 0 and 100", item.ID)
			}
			if len(item.Components) == 0 {
				return fmt.Errorf("analysis result score %q has no components", item.ID)
			}
			for _, component := range item.Components {
				if component.ID == "" || component.Weight <= 0 {
					return fmt.Errorf("analysis result score %q has an invalid component", item.ID)
				}
				if component.Value < 0 || component.Value > 100 {
					return fmt.Errorf("analysis result component %q must be between 0 and 100", component.ID)
				}
			}
		default:
			return fmt.Errorf("analysis result item %q has unknown kind %q", item.ID, item.Kind)
		}
	}
	return nil
}

// summaryOf derives the small headline stored next to a result: what a list of
// recordings shows without loading the full result. It shares its derivation with
// the migration that rebuilds summaries written in an older shape.
func summaryOf(raw json.RawMessage) map[string]any {
	return summary.Of(raw)
}
