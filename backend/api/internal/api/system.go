package api

import (
	"net/http"

	"github.com/pocketbase/pocketbase/core"

	"github.com/rbmr/TaigaSleep/backend/api/internal/version"
)

type HealthResponse struct {
	Status string `json:"status"`
	Time   string `json:"time"`
}

type VersionResponse struct {
	Service string `json:"service"`
	// Version is the backend release version.
	Version string `json:"version"`
	// API is the contract revision. A client refuses a server whose value differs from
	// the one it was built for.
	API int `json:"api"`
}

func GetHealth(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, HealthResponse{Status: "ok", Time: derefString(rfc3339(nowString()))})
}

func GetVersion(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, VersionResponse{Service: "taiga-sleep", Version: version.Number, API: version.API})
}

// ServeOpenAPI serves the generated document at GET /v1/openapi.json. The record
// API's entries are generated from the collection schema, so the document describes
// the collections this database actually has.
func ServeOpenAPI(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, openAPIDocument(e.App))
}
