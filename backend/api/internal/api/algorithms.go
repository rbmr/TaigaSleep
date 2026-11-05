package api

import (
	"encoding/json"
	"io"
	"net/http"
	"sort"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// AlgorithmView is the manifest of an algorithm a worker can run; see
// docs/analysis-contract.md.
type AlgorithmView struct {
	ID              string         `json:"id"`
	Version         string         `json:"version"`
	Name            string         `json:"name"`
	Description     string         `json:"description"`
	License         string         `json:"license"`
	Citation        any            `json:"citation"`
	Homepage        any            `json:"homepage"`
	Needs           any            `json:"needs"`
	EpochSeconds    int            `json:"epoch_seconds"`
	StageScheme     string         `json:"stage_scheme"`
	SupportedStages []string       `json:"supported_stages"`
	Capabilities    []string       `json:"capabilities"`
	DefaultParams   map[string]any `json:"default_params"`
	ParamsSchema    map[string]any `json:"params_schema"`
	Source          string         `json:"source"`
	Workers         []string       `json:"workers,omitempty"`

	// lastSeen is the newest announcement of this algorithm version, in stored date
	// format. It decides which version is the default, not what is listed.
	lastSeen string
}

type AlgorithmList struct {
	Items []AlgorithmView `json:"items"`
}

// WorkerAnnouncement is what a worker says about itself.
type WorkerAnnouncement struct {
	Name       string          `json:"name"`
	Version    string          `json:"version"`
	Algorithms []AlgorithmView `json:"algorithms"`
}

func PutWorker(e *core.RequestEvent) error {
	var body WorkerAnnouncement
	if err := json.NewDecoder(io.LimitReader(e.Request.Body, 524288)).Decode(&body); err != nil {
		return badRequest("invalid_body", "the body must describe the worker and its algorithms")
	}
	name := strings.TrimSpace(body.Name)
	if name == "" {
		name = e.Auth.GetString("name")
	}
	if name == "" {
		name = e.Auth.Id
	}
	if len(name) > 100 {
		name = name[:100]
	}
	if len(body.Algorithms) > 100 {
		return badRequest("too_many_algorithms", "a worker offers at most 100 algorithms")
	}
	for _, algorithm := range body.Algorithms {
		if !algorithmIDRE.MatchString(algorithm.ID) {
			return badRequest("invalid_algorithm", "algorithm id is invalid")
		}
		if algorithm.Version == "" || algorithm.Name == "" || algorithm.License == "" {
			return badRequest("invalid_algorithm", "algorithm version, name and license are required")
		}
	}
	err := e.App.RunInTransaction(func(txApp core.App) error {
		record, err := txApp.FindRecordById("workers", e.Auth.Id)
		if err != nil {
			return err
		}
		record.Set("name", name)
		record.Set("version", body.Version)
		record.Set("algorithms", body.Algorithms)
		record.Set("last_seen", nowString())
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}
	return e.NoContent(http.StatusNoContent)
}

// registeredAlgorithms is every algorithm any registered worker has announced, one
// entry per id and version, with the workers that offer it.
func registeredAlgorithms(app core.App) []AlgorithmView {
	workers, err := app.FindAllRecords("workers")
	if err != nil {
		return nil
	}
	groups := map[string]*AlgorithmView{}
	for _, worker := range workers {
		var algorithms []AlgorithmView
		_ = worker.UnmarshalJSONField("algorithms", &algorithms)
		name := worker.GetString("name")
		if name == "" {
			name = worker.Id
		}
		for _, algorithm := range algorithms {
			if algorithm.ID == "" || algorithm.Version == "" {
				continue
			}
			key := algorithm.ID + "\x00" + algorithm.Version
			entry := groups[key]
			if entry == nil {
				copied := algorithm
				copied.Workers = nil
				entry = &copied
				groups[key] = entry
			}
			entry.Workers = append(entry.Workers, name)
			if seen := worker.GetString("last_seen"); seen > entry.lastSeen {
				entry.lastSeen = seen
			}
		}
	}
	keys := make([]string, 0, len(groups))
	for key := range groups {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]AlgorithmView, 0, len(keys))
	for _, key := range keys {
		result = append(result, *groups[key])
	}
	return result
}

func ListAlgorithms(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, AlgorithmList{Items: registeredAlgorithms(e.App)})
}
