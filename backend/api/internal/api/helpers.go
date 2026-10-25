package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
)

func requireUser(e *core.RequestEvent) error {
	if e.Auth == nil || e.Auth.Collection().Name != "users" {
		return e.ForbiddenError("user account required", nil)
	}
	return nil
}

func decodeObject(raw json.RawMessage) (map[string]any, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return map[string]any{}, nil
	}
	var value map[string]any
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, err
	}
	if value == nil {
		value = map[string]any{}
	}
	return value, nil
}

func paramsHash(params map[string]any) (string, error) {
	raw, err := json.Marshal(params)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])[:16], nil
}

func readJSONField(record *core.Record, name string, target any) {
	_ = record.UnmarshalJSONField(name, target)
}

func findSessionForUser(app core.App, id string, user *core.Record) (*core.Record, error) {
	if user == nil {
		return nil, apis.NewForbiddenError("user account required", nil)
	}
	record, err := app.FindRecordById("sessions", id)
	if err != nil || record.GetString("owner") != user.Id {
		return nil, apis.NewNotFoundError("session not found", nil)
	}
	return record, nil
}

func findAnalysisForUser(app core.App, id string, user *core.Record) (*core.Record, error) {
	analysis, err := app.FindRecordById("analyses", id)
	if err != nil {
		return nil, apis.NewNotFoundError("analysis not found", nil)
	}
	session, err := app.FindRecordById("sessions", analysis.GetString("session"))
	if err != nil || user == nil || session.GetString("owner") != user.Id {
		return nil, apis.NewNotFoundError("analysis not found", nil)
	}
	return analysis, nil
}

func findJobForUser(app core.App, id string, user *core.Record) (*core.Record, error) {
	job, err := app.FindRecordById("jobs", id)
	if err != nil {
		return nil, apis.NewNotFoundError("job not found", nil)
	}
	session, err := app.FindRecordById("sessions", job.GetString("session"))
	if err != nil || user == nil || session.GetString("owner") != user.Id {
		return nil, apis.NewNotFoundError("job not found", nil)
	}
	return job, nil
}

func workerName(e *core.RequestEvent) (string, error) {
	if e.Auth == nil || e.Auth.Collection().Name != "workers" {
		return "", e.ForbiddenError("worker account required", nil)
	}
	name := e.Auth.GetString("name")
	if name == "" {
		return "", e.BadRequestError("worker name is required", nil)
	}
	return name, nil
}

func isNotFound(err error) bool {
	return err != nil && err.Error() == "sql: no rows in result set"
}

func asBadRequest(message string, err error) error {
	if err == nil {
		return apis.NewBadRequestError(message, nil)
	}
	return apis.NewBadRequestError(message, fmt.Errorf("%w", err))
}
