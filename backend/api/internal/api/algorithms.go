package api

import (
	"encoding/json"
	"net/http"
	"sort"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

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
	WorkerCount     int            `json:"worker_count,omitempty"`
}

type CapabilitiesRequest struct {
	Worker     string          `json:"worker"`
	Version    string          `json:"version"`
	Algorithms []AlgorithmView `json:"algorithms"`
}

func ReportCapabilities(e *core.RequestEvent) error {
	if e.Auth == nil || e.Auth.Collection().Name != "workers" {
		return e.ForbiddenError("worker account required", nil)
	}
	var body CapabilitiesRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("invalid request body", err)
	}
	worker := strings.TrimSpace(body.Worker)
	if worker == "" {
		worker = e.Auth.GetString("name")
	}
	if worker == "" {
		worker = e.Auth.Id
	}
	if len(worker) > 120 {
		worker = worker[:120]
	}
	if len(body.Algorithms) > 100 {
		return e.BadRequestError("too many algorithms", nil)
	}
	for _, algorithm := range body.Algorithms {
		if !modelIDRE.MatchString(algorithm.ID) {
			return e.BadRequestError("algorithm id is invalid", nil)
		}
		if algorithm.Version == "" || algorithm.Name == "" || algorithm.License == "" {
			return e.BadRequestError("algorithm version, name and license are required", nil)
		}
	}
	raw, err := json.Marshal(body.Algorithms)
	if err != nil {
		return e.BadRequestError("algorithm capabilities are invalid", err)
	}
	if len(raw) > 262144 {
		return e.BadRequestError("algorithm capabilities are too large", nil)
	}

	err = e.App.RunInTransaction(func(txApp core.App) error {
		record, err := txApp.FindRecordById("workers", e.Auth.Id)
		if err != nil {
			return err
		}
		record.Set("name", worker)
		record.Set("version", body.Version)
		record.Set("algorithms", body.Algorithms)
		record.Set("last_seen", nowString())
		return txApp.Save(record)
	})
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, map[string]any{
		"ok":      true,
		"worker":  worker,
		"version": body.Version,
		"count":   len(body.Algorithms),
	})
}

func ListAlgorithms(e *core.RequestEvent) error {
	if err := requireUser(e); err != nil {
		return err
	}
	workers, err := e.App.FindAllRecords("workers")
	if err != nil {
		return err
	}

	type group struct {
		info    AlgorithmView
		workers []string
	}
	groups := map[string]*group{}
	for _, worker := range workers {
		var algorithms []AlgorithmView
		readJSONField(worker, "algorithms", &algorithms)
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
				entry = &group{info: algorithm}
				groups[key] = entry
			}
			found := false
			for _, existing := range entry.workers {
				if existing == name {
					found = true
					break
				}
			}
			if !found {
				entry.workers = append(entry.workers, name)
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
		entry := groups[key]
		item := entry.info
		item.Workers = entry.workers
		item.WorkerCount = len(entry.workers)
		result = append(result, item)
	}
	return e.JSON(http.StatusOK, map[string]any{"algorithms": result})
}
