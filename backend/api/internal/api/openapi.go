package api

import (
	"net/http"
	"reflect"
	"strconv"
	"strings"
	"sync"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/hook"
	"github.com/pocketbase/pocketbase/tools/router"
)

// The route manifest below is the single declaration of the custom API: it
// registers the handlers and it generates the OpenAPI 3.1 document that
// /api/taiga/openapi.json serves. Adding a route means adding one entry here; a
// route that is registered but not declared, or documented but not registered,
// cannot happen because both come from the same table.

const (
	openAPIPath       = "/api/taiga/openapi.json"
	docsPath          = "/api/taiga/docs"
	openAPIVersion    = "3.1.0"
	openAPITitle      = "Taiga Sleep API"
	openAPIDocVersion = "1.0.0"

	securityUser   = "userToken"
	securityWorker = "workerToken"

	tagSystem   = "system"
	tagSessions = "sessions"
	tagUploads  = "uploads"
	tagAnalysis = "analyses"
	tagWorkers  = "workers"
)

const apiDescription = `Custom routes of the self-hosted Taiga Sleep backend, running on a PocketBase app.

Authentication uses PocketBase collection auth. Send the collection-dependent token
returned by POST /api/collections/users/auth-with-password, or
POST /api/collections/workers/auth-with-password for the worker routes, in a plain
` + "`Authorization`" + ` header. PocketBase does not expect a ` + "`Bearer `" + ` prefix.

Only the custom /api/taiga routes are described here. PocketBase's built-in
collection API is documented by PocketBase itself. Browse this document in
Swagger UI at /api/taiga/docs, or fetch it as JSON here. No recording content or
health data is part of this document.`

type routeParam struct {
	Name        string
	In          string
	Required    bool
	Description string
	Schema      map[string]any
}

type routeBody struct {
	ContentType string
	Description string
	// Schema is called with the document's schema reflector so request bodies
	// can reference the same component definitions as responses.
	Schema func(*schemaReflector) map[string]any
}

type routeResponse struct {
	Status      int
	Description string
	ContentType string
	Schema      func(*schemaReflector) map[string]any
}

// openAPIRoute declares one custom route: how it is mounted and how it is
// documented. Both use the same entry.
type openAPIRoute struct {
	Method      string
	Path        string
	OperationID string
	Summary     string
	Description string
	Tag         string
	Security    string
	Params      []routeParam
	Body        *routeBody
	Responses   []routeResponse
	Handler     func(*core.RequestEvent) error
}

// Routes returns the custom API manifest.
func Routes() []openAPIRoute {
	return []openAPIRoute{
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/health",
			OperationID: "getHealth",
			Summary:     "Report API health",
			Tag:         tagSystem,
			Responses: []routeResponse{
				jsonResponse(200, "The API is up and answering requests.", HealthResponse{}),
			},
			Handler: GetHealth,
		},
		{
			Method:      http.MethodGet,
			Path:        openAPIPath,
			OperationID: "getOpenAPI",
			Summary:     "Return this OpenAPI document",
			Description: "Generated from the route manifest when the process starts, so it always matches the routes this build serves. Downloadable from the documentation page.",
			Tag:         tagSystem,
			Responses: []routeResponse{
				jsonResponse(200, "The OpenAPI 3.1 document.", map[string]any{}),
			},
			Handler: ServeOpenAPI,
		},
		{
			Method:      http.MethodGet,
			Path:        docsPath,
			OperationID: "getDocs",
			Summary:     "Browse this API in Swagger UI",
			Description: "Interactive documentation of the running build. It loads `GET /api/taiga/openapi.json` and needs no internet access, no CDN and no second service.",
			Tag:         tagSystem,
			Responses: []routeResponse{
				{
					Status:      200,
					Description: "The Swagger UI page.",
					ContentType: "text/html; charset=utf-8",
					Schema: func(*schemaReflector) map[string]any {
						return map[string]any{"type": "string"}
					},
				},
			},
			Handler: ServeDocs,
		},
		{
			Method:      http.MethodGet,
			Path:        docsPath + "/{asset}",
			OperationID: "getDocsAsset",
			Summary:     "Static assets for the documentation page",
			Description: "The vendored Swagger UI release the documentation page loads. Not intended to be called directly.",
			Tag:         tagSystem,
			Params: []routeParam{
				{
					Name:        "asset",
					In:          "path",
					Required:    true,
					Description: "Vendored asset name.",
					Schema:      map[string]any{"type": "string", "enum": docsAssetNames()},
				},
			},
			Responses: withErrors([]routeResponse{
				{
					Status:      200,
					Description: "The requested asset.",
					ContentType: "text/css; charset=utf-8",
					Schema: func(*schemaReflector) map[string]any {
						return map[string]any{"type": "string"}
					},
				},
			}, 404),
			Handler: ServeDocsAsset,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/auth-check",
			OperationID: "checkAuth",
			Summary:     "Check the current app user",
			Description: "Returns the authenticated user, so a client can verify a stored token without touching a session.",
			Tag:         tagSystem,
			Security:    securityUser,
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The token belongs to an app user.", AuthCheckResponse{}),
			}, 401, 403),
			Handler: CheckAuth,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/sessions",
			OperationID: "createSession",
			Summary:     "Create or refresh a recording session",
			Description: "Creates the server-side session for a recording and stores its meta.json. Repeating the call with the same " +
				"client session id resumes an unfinished upload and returns 200.",
			Tag:      tagSessions,
			Security: securityUser,
			Body:     metaBody("The recording meta.json, validated field by field."),
			Responses: withErrors([]routeResponse{
				jsonResponse(201, "The session was created.", SessionUploadResponse{}),
				jsonResponse(200, "The session already existed and was refreshed.", SessionUploadResponse{}),
			}, 400, 401, 403),
			Handler: CreateSession,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/sessions",
			OperationID: "listSessions",
			Summary:     "List the caller's sessions",
			Tag:         tagSessions,
			Security:    securityUser,
			Params: []routeParam{
				queryInt("page", "Page number, starting at 1.", 1, 1, 1000000),
				queryInt("perPage", "Items per page.", 30, 1, 100),
				queryEnum("state", "Only return sessions in this state.", []string{"recording", "stopped", "forced_stop", "interrupted"}),
				queryEnum("upload_status", "Only return sessions with this upload status.", []string{"none", "pending", "uploaded", "failed"}),
				queryString("from", "Only return sessions started at or after this instant. RFC 3339, `YYYY-MM-DD HH:MM:SS.sssZ` or `YYYY-MM-DD`."),
				queryString("to", "Only return sessions started at or before this instant. Same formats as `from`."),
				queryEnum("sort", "Sort order, default `-started_at`.", []string{
					"started_at", "-started_at", "created", "-created", "updated", "-updated", "duration_s", "-duration_s",
				}),
			},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "One page of session summaries.", SessionListView{}),
			}, 400, 401, 403),
			Handler: ListSessions,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/sessions/{id}",
			OperationID: "getSession",
			Summary:     "Get one session with its upload, analyses and jobs",
			Tag:         tagSessions,
			Security:    securityUser,
			Params:      []routeParam{pathParam("id", "Session record id.")},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The session detail.", SessionDetailView{}),
			}, 401, 403, 404),
			Handler: GetSession,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/sessions/{id}/upload",
			OperationID: "getUpload",
			Summary:     "Get the upload manifest of a session",
			Tag:         tagUploads,
			Security:    securityUser,
			Params:      []routeParam{pathParam("id", "Session record id.")},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The session and its upload manifest.", SessionUploadResponse{}),
			}, 401, 403, 404),
			Handler: GetUpload,
		},
		{
			Method:      http.MethodPut,
			Path:        "/api/taiga/sessions/{id}/upload/meta",
			OperationID: "putUploadMeta",
			Summary:     "Replace the meta.json of a session",
			Description: "Used while a recording grows so the server sees the final state and end time before completion.",
			Tag:         tagUploads,
			Security:    securityUser,
			Params:      []routeParam{pathParam("id", "Session record id.")},
			Body:        metaBody("The recording meta.json, validated field by field."),
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The session and its updated upload manifest.", SessionUploadResponse{}),
			}, 400, 401, 403, 404, 409),
			Handler: PutMeta,
		},
		{
			Method:      http.MethodPut,
			Path:        "/api/taiga/sessions/{id}/upload/file/{file}",
			OperationID: "putUploadFile",
			Summary:     "Append a chunk to a recording file",
			Description: "Appends raw bytes at the given offset and validates the newly written frames or events. A mismatch between " +
				"`offset` and the stored length returns 409 with the expected offset in the message.",
			Tag:      tagUploads,
			Security: securityUser,
			Params: []routeParam{
				pathParam("id", "Session record id."),
				{
					Name:        "file",
					In:          "path",
					Required:    true,
					Description: "Recording file to append to. meta.json is uploaded through the meta routes.",
					Schema:      map[string]any{"type": "string", "enum": []string{eventsFileName, hrFileName, ecgFileName, accFileName}},
				},
				{
					Name:        "offset",
					In:          "query",
					Required:    true,
					Description: "Byte offset the chunk starts at; it must equal the current file length.",
					Schema:      map[string]any{"type": "integer", "minimum": 0},
				},
			},
			Body: &routeBody{
				ContentType: "application/octet-stream",
				Description: "The raw chunk. Chunk size is limited by the `max_chunk_bytes` value in the upload manifest.",
				Schema: func(*schemaReflector) map[string]any {
					return map[string]any{"type": "string", "format": "binary"}
				},
			},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The chunk was appended and validated.", AppendResult{}),
			}, 400, 401, 403, 404, 409, 413),
			Handler: PutFile,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/sessions/{id}/upload/complete",
			OperationID: "completeUpload",
			Summary:     "Finish an upload",
			Description: "Validates every file, keeps the valid prefix of a corrupt tail, and marks the session uploaded. Warnings are " +
				"reported in `upload_error`.",
			Tag:      tagUploads,
			Security: securityUser,
			Params:   []routeParam{pathParam("id", "Session record id.")},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The upload is complete.", SessionUploadResponse{}),
			}, 400, 401, 403, 404, 409, 422),
			Handler: CompleteUpload,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/sessions/{id}/analysis",
			OperationID: "requestAnalyses",
			Summary:     "Queue analyses for an uploaded session",
			Description: "Creates one analysis and one job per requested model, version and parameter set. Repeating an identical " +
				"request returns the existing analysis and job.",
			Tag:      tagAnalysis,
			Security: securityUser,
			Params:   []routeParam{pathParam("id", "Session record id.")},
			Body:     jsonBody(AnalysisRequest{}, "Up to 8 model/version entries, or the single-model fields."),
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The analyses and their jobs.", AnalysisRequestResponse{}),
			}, 400, 401, 403, 404, 409),
			Handler: RequestAnalyses,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/analyses/{id}",
			OperationID: "getAnalysis",
			Summary:     "Get one analysis result",
			Tag:         tagAnalysis,
			Security:    securityUser,
			Params:      []routeParam{pathParam("id", "Analysis record id.")},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The analysis, including its result once done.", AnalysisView{}),
			}, 401, 403, 404),
			Handler: GetAnalysis,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/jobs/{id}/retry",
			OperationID: "retryJob",
			Summary:     "Requeue a failed analysis job",
			Tag:         tagAnalysis,
			Security:    securityUser,
			Params:      []routeParam{pathParam("id", "Job record id.")},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The requeued job and its analysis.", RetryJobResponse{}),
			}, 401, 403, 404, 409),
			Handler: RetryJob,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/algorithms",
			OperationID: "listAlgorithms",
			Summary:     "List the algorithms the connected workers offer",
			Description: "Grouped by model id and version, with the workers that can run each one and the default parameters from the " +
				"worker capabilities report.",
			Tag:      tagAnalysis,
			Security: securityUser,
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The available algorithms.", AlgorithmsResponse{}),
			}, 401, 403),
			Handler: ListAlgorithms,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/worker/jobs/claim",
			OperationID: "claimJob",
			Summary:     "Claim the next queued job",
			Description: "Leases one queued job to the calling worker and marks its analysis running. Returns `job: null` when the " +
				"queue is empty.",
			Tag:      tagWorkers,
			Security: securityWorker,
			Body:     jsonBody(ClaimRequest{}, "Worker identity, lease length and optional algorithm filter."),
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The claimed job, or null when nothing is queued.", ClaimJobsResponse{}),
			}, 400, 401, 403),
			Handler: ClaimJobs,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/worker/jobs/{id}/result",
			OperationID: "submitJobResult",
			Summary:     "Report a job result",
			Description: "Stores the analysis result of a done job, or records the failure. A retryable failure is requeued with " +
				"backoff, up to `TAIGA_JOB_MAX_ATTEMPTS`.",
			Tag:      tagWorkers,
			Security: securityWorker,
			Params:   []routeParam{pathParam("id", "Job record id.")},
			Body:     jsonBody(JobResultRequest{}, "The worker identity, the outcome and, for done jobs, the analysis result."),
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The updated job.", JobResultResponse{}),
			}, 400, 401, 403, 404),
			Handler: ResultJob,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/worker/jobs/requeue",
			OperationID: "requeueJobs",
			Summary:     "Requeue jobs whose lease expired",
			Tag:         tagWorkers,
			Security:    securityWorker,
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The number of requeued jobs.", RequeueJobsResponse{}),
			}, 401, 403),
			Handler: RequeueJobs,
		},
		{
			Method:      http.MethodPost,
			Path:        "/api/taiga/worker/capabilities",
			OperationID: "reportCapabilities",
			Summary:     "Publish the algorithms this worker can run",
			Description: "Replaces the worker's capabilities and refreshes its last-seen timestamp.",
			Tag:         tagWorkers,
			Security:    securityWorker,
			Body:        jsonBody(CapabilitiesRequest{}, "Worker version and up to 100 algorithm descriptors."),
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The capabilities were stored.", CapabilitiesResponse{}),
			}, 400, 401, 403),
			Handler: ReportCapabilities,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/worker/sessions/{id}/manifest",
			OperationID: "getWorkerManifest",
			Summary:     "Get the upload manifest for a leased job",
			Description: "Only the worker that holds a running, unexpired lease on the job can read a session's manifest.",
			Tag:         tagWorkers,
			Security:    securityWorker,
			Params: []routeParam{
				pathParam("id", "Session record id."),
				requiredQuery("job", "Job record id held by this worker."),
				requiredQuery("worker", "The worker identity the job is leased to."),
			},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The job, worker and upload manifest.", WorkerManifestResponse{}),
			}, 400, 401, 403, 404),
			Handler: WorkerManifest,
		},
		{
			Method:      http.MethodGet,
			Path:        "/api/taiga/worker/sessions/{id}/files/{file}",
			OperationID: "getWorkerFile",
			Summary:     "Download a recording file for a leased job",
			Tag:         tagWorkers,
			Security:    securityWorker,
			Params: []routeParam{
				pathParam("id", "Session record id."),
				{
					Name:        "file",
					In:          "path",
					Required:    true,
					Description: "Recording file to download.",
					Schema: map[string]any{"type": "string", "enum": []string{
						metaFileName, eventsFileName, hrFileName, ecgFileName, accFileName,
					}},
				},
				requiredQuery("job", "Job record id held by this worker."),
				requiredQuery("worker", "The worker identity the job is leased to."),
			},
			Responses: withErrors([]routeResponse{
				{
					Status:      200,
					Description: "The raw recording file.",
					ContentType: "application/octet-stream",
					Schema: func(*schemaReflector) map[string]any {
						return map[string]any{"type": "string", "format": "binary"}
					},
				},
			}, 400, 401, 403, 404),
			Handler: WorkerFile,
		},
	}
}

// RegisterRoutes mounts every declared route on the app router and applies the
// auth middleware the entry asks for.
func RegisterRoutes(router *router.Router[*core.RequestEvent]) {
	for _, route := range Routes() {
		mounted := router.Route(route.Method, route.Path, route.Handler)
		if middleware := routeMiddleware(route.Security); middleware != nil {
			mounted.Bind(middleware)
		}
	}
}

func routeMiddleware(security string) *hook.Handler[*core.RequestEvent] {
	switch security {
	case securityUser:
		return apis.RequireAuth()
	case securityWorker:
		return apis.RequireAuth("workers")
	}
	return nil
}

var (
	documentOnce sync.Once
	document     map[string]any
)

// OpenAPIDocument returns the generated document. It is built once per process.
func OpenAPIDocument() map[string]any {
	documentOnce.Do(func() {
		document = buildOpenAPIDocument()
	})
	return document
}

// ServeOpenAPI serves the generated document at GET /api/taiga/openapi.json.
func ServeOpenAPI(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, OpenAPIDocument())
}

// GetHealth is the public liveness probe the app and deployment scripts use.
func GetHealth(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, HealthResponse{
		Status:  "ok",
		Service: "taiga-sleep",
		Time:    nowString(),
	})
}

// CheckAuth reports the authenticated user so a client can validate a stored
// token without touching a session.
func CheckAuth(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, AuthCheckResponse{
		OK:         true,
		ID:         e.Auth.Id,
		Collection: e.Auth.Collection().Name,
	})
}

func buildOpenAPIDocument() map[string]any {
	reflector := newSchemaReflector()
	// Keep the shared error shape in components even though operations only
	// reference it through responses.
	reflector.schemaOf(reflect.TypeOf(ErrorResponse{}))

	paths := map[string]any{}
	for _, route := range Routes() {
		path, _ := paths[route.Path].(map[string]any)
		if path == nil {
			path = map[string]any{}
			paths[route.Path] = path
		}
		path[strings.ToLower(route.Method)] = buildOperation(reflector, route)
	}

	return map[string]any{
		"openapi": openAPIVersion,
		"info": map[string]any{
			"title":       openAPITitle,
			"version":     openAPIDocVersion,
			"description": apiDescription,
			"license":     map[string]any{"name": "GPL-3.0"},
		},
		"servers": []any{
			map[string]any{"url": "/", "description": "The host serving this document."},
		},
		"tags": []any{
			map[string]any{"name": tagSystem, "description": "Health, auth check and this document."},
			map[string]any{"name": tagSessions, "description": "Recording sessions owned by the app user."},
			map[string]any{"name": tagUploads, "description": "Append-only recording uploads."},
			map[string]any{"name": tagAnalysis, "description": "Analysis requests, results and retries."},
			map[string]any{"name": tagWorkers, "description": "Routes used by the analysis worker service account."},
		},
		"paths": paths,
		"components": map[string]any{
			"schemas": reflector.defs,
			"securitySchemes": map[string]any{
				securityUser: map[string]any{
					"type":        "apiKey",
					"in":          "header",
					"name":        "Authorization",
					"description": "PocketBase token of an app user, from POST /api/collections/users/auth-with-password. Send the token value without a Bearer prefix.",
				},
				securityWorker: map[string]any{
					"type":        "apiKey",
					"in":          "header",
					"name":        "Authorization",
					"description": "PocketBase token of the worker service account, from POST /api/collections/workers/auth-with-password. Send the token value without a Bearer prefix.",
				},
			},
		},
	}
}

func buildOperation(reflector *schemaReflector, route openAPIRoute) map[string]any {
	operation := map[string]any{
		"operationId": route.OperationID,
		"summary":     route.Summary,
		"tags":        []string{route.Tag},
	}
	if route.Description != "" {
		operation["description"] = route.Description
	}
	if route.Security != "" {
		operation["security"] = []any{map[string]any{route.Security: []string{}}}
	}
	if len(route.Params) > 0 {
		params := make([]any, 0, len(route.Params))
		for _, param := range route.Params {
			entry := map[string]any{
				"name":     param.Name,
				"in":       param.In,
				"required": param.Required,
				"schema":   param.Schema,
			}
			if param.Description != "" {
				entry["description"] = param.Description
			}
			params = append(params, entry)
		}
		operation["parameters"] = params
	}
	if route.Body != nil {
		contentType := route.Body.ContentType
		if contentType == "" {
			contentType = "application/json"
		}
		requestBody := map[string]any{
			"required": true,
			"content":  map[string]any{contentType: map[string]any{"schema": route.Body.Schema(reflector)}},
		}
		if route.Body.Description != "" {
			requestBody["description"] = route.Body.Description
		}
		operation["requestBody"] = requestBody
	}

	responses := map[string]any{}
	for _, response := range route.Responses {
		entry := map[string]any{"description": response.Description}
		if response.Schema != nil {
			contentType := response.ContentType
			if contentType == "" {
				contentType = "application/json"
			}
			entry["content"] = map[string]any{contentType: map[string]any{"schema": response.Schema(reflector)}}
		}
		responses[strconv.Itoa(response.Status)] = entry
	}
	operation["responses"] = responses
	return operation
}

func jsonResponse(status int, description string, value any) routeResponse {
	schemaOf := func(r *schemaReflector) map[string]any {
		return r.schemaOf(reflect.TypeOf(value))
	}
	return routeResponse{Status: status, Description: description, Schema: schemaOf}
}

func jsonBody(value any, description string) *routeBody {
	return &routeBody{
		Description: description,
		Schema: func(r *schemaReflector) map[string]any {
			return r.schemaOf(reflect.TypeOf(value))
		},
	}
}

// metaBody documents the `{"meta": <meta.json>}` envelope. The handlers keep
// the nested value as raw JSON so meta.json is stored byte for byte, so the
// schema is assembled here from the validated RecordingMeta type.
func metaBody(description string) *routeBody {
	return &routeBody{
		Description: description,
		Schema: func(r *schemaReflector) map[string]any {
			return map[string]any{
				"type": "object",
				"properties": map[string]any{
					"meta": r.schemaOf(reflect.TypeOf(RecordingMeta{})),
				},
				"required": []string{"meta"},
			}
		},
	}
}

func withErrors(responses []routeResponse, statuses ...int) []routeResponse {
	for _, status := range statuses {
		responses = append(responses, routeResponse{
			Status:      status,
			Description: errorDescription(status),
			Schema: func(r *schemaReflector) map[string]any {
				return r.schemaOf(reflect.TypeOf(ErrorResponse{}))
			},
		})
	}
	return responses
}

func errorDescription(status int) string {
	switch status {
	case 400:
		return "The request body or query is invalid."
	case 401:
		return "Missing or expired token."
	case 403:
		return "The token belongs to the wrong collection or does not own this resource."
	case 404:
		return "The record does not exist or is not visible to the caller."
	case 409:
		return "The request conflicts with the current session or job state."
	case 413:
		return "The chunk or recording exceeds the configured size limit."
	case 422:
		return "The recording has no valid frames."
	}
	return "Error."
}

func pathParam(name, description string) routeParam {
	return routeParam{
		Name:        name,
		In:          "path",
		Required:    true,
		Description: description,
		Schema:      map[string]any{"type": "string"},
	}
}

func requiredQuery(name, description string) routeParam {
	return routeParam{
		Name:        name,
		In:          "query",
		Required:    true,
		Description: description,
		Schema:      map[string]any{"type": "string"},
	}
}

func queryString(name, description string) routeParam {
	return routeParam{
		Name:        name,
		In:          "query",
		Description: description,
		Schema:      map[string]any{"type": "string"},
	}
}

func queryInt(name, description string, defaultValue, minimum, maximum int) routeParam {
	return routeParam{
		Name:        name,
		In:          "query",
		Description: description,
		Schema: map[string]any{
			"type":    "integer",
			"default": defaultValue,
			"minimum": minimum,
			"maximum": maximum,
		},
	}
}

func queryEnum(name, description string, values []string) routeParam {
	return routeParam{
		Name:        name,
		In:          "query",
		Description: description,
		Schema:      map[string]any{"type": "string", "enum": values},
	}
}
