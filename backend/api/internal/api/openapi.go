package api

import (
	"reflect"
	"strconv"
	"strings"
	"sync"

	"github.com/pocketbase/pocketbase/core"
)

// The route manifest in routes.go is the single declaration of the API: it registers
// the handlers and it generates the OpenAPI 3.1 document that /v1/openapi.json
// serves. Adding a route means adding one entry there; a route that is registered but
// not declared, or documented but not registered, cannot happen because both come
// from the same table.

const (
	apiPrefix         = "/v1"
	openAPIPath       = apiPrefix + "/openapi.json"
	docsPath          = apiPrefix + "/docs"
	openAPIVersion    = "3.1.0"
	openAPITitle      = "Taiga Sleep API"
	openAPIDocVersion = "1.0.0"

	securityBearer = "bearerToken"

	tagSystem     = "system"
	tagAuth       = "auth"
	tagTokens     = "tokens"
	tagRecordings = "recordings"
	tagFiles      = "recording files"
	tagAnalyses   = "analyses"
	tagAlgorithms = "algorithms"
	tagRealtime   = "realtime"
	tagWorkers    = "worker"
)

const apiDescription = `The HTTP API of the self-hosted Taiga Sleep backend. The design and its conventions
are in docs/api.md of the repository.

Log in through PocketBase's auth route of the account's collection
(` + "`POST /api/collections/users/auth-with-password`" + ` for an app account,
` + "`POST /api/collections/workers/auth-with-password`" + ` for the worker) and send the token as
` + "`Authorization: Bearer <token>`" + `. Refresh it with that collection's ` + "`auth-refresh`" + ` route.
Each operation says which role may call it: a **user** (an app account) or a **worker**
(the analysis service). A user can make a read-only access token for their own assistant
with ` + "`POST /v1/tokens`" + `; it reads their recordings, analyses and results and is
refused on every write.

Recordings and analyses are read and written through PocketBase's record API at
` + "`/api/collections/{collection}/records`" + `, whose rules decide what the caller sees; this
document describes those routes from the collection schema. Changes to them arrive through
PocketBase's realtime stream at ` + "`/api/realtime`" + ` (see ` + "`x-realtime`" + `). Everything else is under
` + "`/v1`" + `. Every error has PocketBase's shape,
` + "`{\"status\", \"message\", \"data\"}`" + `; the API's stable code is in ` + "`data.code`" + `.

Browse this document in Swagger UI at /v1/docs, or fetch it as JSON.`

// realtimeDescription is the protocol of PocketBase's realtime API, which is where a
// client learns that a recording or an analysis changed. It has no route of its own in
// this document, because PocketBase serves it and its payloads are records; the topics
// are listed under x-realtime so a client knows what to subscribe to.
const realtimeDescription = `PocketBase's realtime API is a Server-Sent Events stream served by the backend itself.
Open it with the account's token (GET /api/realtime, ` + "`Accept: text/event-stream`" + `); the first
message is PB_CONNECT, whose data is {"clientId": "..."}. Subscribe with a POST to
/api/realtime carrying that clientId and the topics below, with the same token and from the
same client address. Every change to a record the caller may read is delivered as a message
named after its topic, with data {"action": "create|update|delete", "record": {...}}.

Nothing is replayed: a change that happened before the subscription is lost, so a client
refetches what it shows on every (re)connect. A connection is closed after 30 minutes at the
latest and after 5 minutes without a message, so a client reconnects and subscribes again.`

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
	Optional    bool
	// Schema is called with the document's schema reflector so request bodies
	// can reference the same component definitions as responses.
	Schema func(*schemaReflector) map[string]any
}

type routeResponse struct {
	Status      int
	Description string
	ContentType string
	Schema      func(*schemaReflector) map[string]any
	// Headers are the response headers worth documenting, name to description.
	Headers map[string]string
}

// openAPIRoute declares one route: how it is mounted and how it is documented. Both
// use the same entry.
type openAPIRoute struct {
	Method      string
	Path        string
	OperationID string
	Summary     string
	Description string
	Tag         string
	Access      access
	Params      []routeParam
	Body        *routeBody
	Responses   []routeResponse
	Handler     func(*core.RequestEvent) error
}

var (
	documentMu     sync.Mutex
	routesDocument map[string]any
	recordDocument map[string]any
)

// OpenAPIDocument returns the generated document of the custom routes. It is built
// once per process; the record API entries need the collection schema, so a running
// server uses openAPIDocument instead.
func OpenAPIDocument() map[string]any {
	documentMu.Lock()
	defer documentMu.Unlock()
	if routesDocument == nil {
		routesDocument = buildOpenAPIDocument(nil)
	}
	return routesDocument
}

// prepareOpenAPIDocument builds the document with the record API entries at startup,
// once the migrations have created the collections.
func prepareOpenAPIDocument(app core.App) {
	documentMu.Lock()
	defer documentMu.Unlock()
	recordDocument = buildOpenAPIDocument(app)
}

// openAPIDocument returns the document of the running server, with the record API's
// routes generated from the collection schema.
func openAPIDocument(app core.App) map[string]any {
	if app == nil {
		return OpenAPIDocument()
	}
	documentMu.Lock()
	defer documentMu.Unlock()
	if recordDocument == nil {
		recordDocument = buildOpenAPIDocument(app)
	}
	return recordDocument
}

func accessDescription(required access) string {
	switch required {
	case accessUser:
		return "Requires a user token."
	case accessWorker:
		return "Requires a worker token."
	case accessAny:
		return "Requires a user or a worker token."
	case accessRules:
		return "Requires a token the collection's rules accept: a user or an access token of the recording's owner for their own data, or a worker token while it holds a live lease of an analysis."
	}
	return "Public."
}

func buildOpenAPIDocument(app core.App) map[string]any {
	reflector := newSchemaReflector()
	// Keep the shared error shape in components even though operations only
	// reference it through responses.
	reflector.schemaOf(reflect.TypeOf(ErrorResponse{}))
	// A recording's meta.json keeps the type it is stored as.
	reflector.schemaOf(reflect.TypeOf(RecordingMeta{}))

	recordRoutes, recordSchemas := recordAPIRoutes(app)

	paths := map[string]any{}
	for _, route := range append(append(Routes(), AuthRoutes()...), recordRoutes...) {
		path, _ := paths[route.Path].(map[string]any)
		if path == nil {
			path = map[string]any{}
			paths[route.Path] = path
		}
		path[strings.ToLower(route.Method)] = buildOperation(reflector, route)
	}
	for name, schema := range recordSchemas {
		reflector.defs[name] = schema
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
			map[string]any{"name": tagSystem, "description": "Health, version and this document."},
			map[string]any{"name": tagAuth, "description": "Logging in and checking a token."},
			map[string]any{"name": tagTokens, "description": "Read-only access tokens for your own assistant or script."},
			map[string]any{"name": tagRecordings, "description": "Recordings and their metadata, through PocketBase's record API."},
			map[string]any{"name": tagFiles, "description": "The raw data files of a recording."},
			map[string]any{"name": tagAnalyses, "description": "Analyses of recordings: request, read and delete through the record API, filled in by the queue."},
			map[string]any{"name": tagRealtime, "description": "PocketBase's realtime stream of the changes to recordings and analyses."},
			map[string]any{"name": tagAlgorithms, "description": "What can analyse a recording."},
			map[string]any{"name": tagWorkers, "description": "Used by the analysis worker."},
		},
		"paths": paths,
		// Realtime has no route of this document to call: PocketBase serves it and its
		// messages carry records. The topics are documented here for a client that
		// needs to know what to subscribe to.
		"x-realtime": map[string]any{
			"description": realtimeDescription,
			"topics": []any{
				map[string]any{
					"topic":       "recordings/*",
					"audience":    "A user or an access token, for the recordings it may read.",
					"description": "A recording was created, changed or deleted. Actions are `create`, `update` and `delete`.",
				},
				map[string]any{
					"topic":       "analyses/*",
					"audience":    "A user or an access token for its own analyses; a worker for queued analyses and for the analysis it holds a lease of.",
					"description": "An analysis was created, changed or deleted. A worker treats a queued one as work to claim.",
				},
			},
		},
		"components": map[string]any{
			"schemas": reflector.defs,
			"securitySchemes": map[string]any{
				securityBearer: map[string]any{
					"type":        "http",
					"scheme":      "bearer",
					"description": "The token from PocketBase's auth route of the account's collection, or a read-only access token from POST /v1/tokens.",
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
	description := strings.TrimSpace(route.Description + "\n\n" + accessDescription(route.Access))
	operation["description"] = description
	if route.Access != accessPublic {
		operation["security"] = []any{map[string]any{securityBearer: []string{}}}
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
			"required": !route.Body.Optional,
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
		if len(response.Headers) > 0 {
			headers := map[string]any{}
			for name, text := range response.Headers {
				headers[name] = map[string]any{"description": text, "schema": map[string]any{"type": "string"}}
			}
			entry["headers"] = headers
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

func emptyResponse(status int, description string) routeResponse {
	return routeResponse{Status: status, Description: description}
}

func jsonBody(value any, description string) *routeBody {
	return &routeBody{
		Description: description,
		Schema: func(r *schemaReflector) map[string]any {
			return r.schemaOf(reflect.TypeOf(value))
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
		return "The request is malformed or a parameter is invalid."
	case 401:
		return "Missing, invalid or expired credentials."
	case 403:
		return "The token's role may not call this endpoint."
	case 404:
		return "The resource does not exist or is not visible to the caller."
	case 409:
		return "The resource is in a state that does not allow this request."
	case 413:
		return "The request exceeds a configured size limit."
	case 422:
		return "The request is well-formed but its content is not acceptable."
	case 429:
		return "Too many attempts."
	}
	return "Error."
}

func pathParam(name, description string) routeParam {
	return routeParam{Name: name, In: "path", Required: true, Description: description, Schema: map[string]any{"type": "string"}}
}

func headerParam(name, description string, required bool) routeParam {
	return routeParam{Name: name, In: "header", Required: required, Description: description, Schema: map[string]any{"type": "string"}}
}
