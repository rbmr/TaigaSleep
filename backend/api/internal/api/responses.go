package api

// Response envelopes for the custom API routes.
//
// The handlers encode these types directly, so the OpenAPI document generated
// from them always describes what the API actually returns.

type HealthResponse struct {
	Status  string `json:"status"`
	Service string `json:"service"`
	Time    string `json:"time"`
}

type AuthCheckResponse struct {
	OK         bool   `json:"ok"`
	ID         string `json:"id"`
	Collection string `json:"collection"`
}

type SessionUploadResponse struct {
	Session SessionView `json:"session"`
	Upload  Manifest    `json:"upload"`
}

type RetryJobResponse struct {
	Job      JobView       `json:"job"`
	Analysis *AnalysisView `json:"analysis"`
}

type AlgorithmsResponse struct {
	Algorithms []AlgorithmView `json:"algorithms"`
}

type ClaimJobsResponse struct {
	Job *JobView `json:"job"`
}

type JobResultResponse struct {
	Job *JobView `json:"job"`
}

type RequeueJobsResponse struct {
	Requeued int64 `json:"requeued"`
}

type CapabilitiesResponse struct {
	OK      bool   `json:"ok"`
	Worker  string `json:"worker"`
	Version string `json:"version"`
	Count   int    `json:"count"`
}

type WorkerManifestResponse struct {
	Job    string   `json:"job"`
	Worker string   `json:"worker"`
	Upload Manifest `json:"upload"`
}

// ErrorResponse mirrors the JSON body PocketBase returns for a failed request.
type ErrorResponse struct {
	Status  int            `json:"status"`
	Message string         `json:"message"`
	Data    map[string]any `json:"data"`
}
