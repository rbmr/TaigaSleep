package api

import (
	"encoding/json"
	"strconv"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// AnalysisView is an analysis. Result is left out of lists unless asked for, since
// it is the large part; Summary is the small derived headline.
type AnalysisView struct {
	ID         string         `json:"id"`
	Recording  string         `json:"recording"`
	Algorithm  string         `json:"algorithm"`
	Version    string         `json:"version"`
	Params     map[string]any `json:"params"`
	Status     string         `json:"status"`
	Error      string         `json:"error"`
	Attempts   int            `json:"attempts"`
	Created    string         `json:"created"`
	Updated    string         `json:"updated"`
	FinishedAt *string        `json:"finished_at"`
	Summary    any            `json:"summary"`
	Result     any            `json:"result,omitempty"`
}

const (
	statusQueued    = "queued"
	statusRunning   = "running"
	statusSucceeded = "succeeded"
	statusFailed    = "failed"
)

func readAny(record *core.Record, name string) any {
	var value any
	if err := record.UnmarshalJSONField(name, &value); err != nil {
		return nil
	}
	return value
}

func serializeAnalysis(record *core.Record, withResult bool) AnalysisView {
	params := map[string]any{}
	_ = record.UnmarshalJSONField("params", &params)
	if params == nil {
		params = map[string]any{}
	}
	view := AnalysisView{
		ID:         record.Id,
		Recording:  record.GetString("recording"),
		Algorithm:  record.GetString("algorithm"),
		Version:    record.GetString("version"),
		Params:     params,
		Status:     record.GetString("status"),
		Error:      record.GetString("error"),
		Attempts:   record.GetInt("attempts"),
		Created:    derefString(rfc3339(record.GetString("created"))),
		Updated:    derefString(rfc3339(record.GetString("updated"))),
		FinishedAt: rfc3339(record.GetString("finished_at")),
		Summary:    readAny(record, "summary"),
	}
	if withResult {
		view.Result = readAny(record, "result")
	}
	return view
}

func paramsHash(params map[string]any) (string, error) {
	raw, err := json.Marshal(params) // map keys are sorted, so equal params hash equal
	if err != nil {
		return "", err
	}
	return hashHex(raw), nil
}

// compareVersions orders dotted versions numerically where it can and falls back to
// string order for anything else.
func compareVersions(a, b string) int {
	left, right := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(left) || i < len(right); i++ {
		l, r := "0", "0" // a missing part counts as zero: 1.0 equals 1.0.0
		if i < len(left) {
			l = left[i]
		}
		if i < len(right) {
			r = right[i]
		}
		ln, lerr := strconv.Atoi(l)
		rn, rerr := strconv.Atoi(r)
		if lerr == nil && rerr == nil {
			if ln != rn {
				if ln < rn {
					return -1
				}
				return 1
			}
			continue
		}
		if c := strings.Compare(l, r); c != 0 {
			return c
		}
	}
	return 0
}
