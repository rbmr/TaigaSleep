package api

import (
	"net/http"
	"reflect"

	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/hook"
	"github.com/pocketbase/pocketbase/tools/router"
)

// Register wires the API into a PocketBase app: the routes, the clean-up of a deleted
// recording's files, the deployment settings and the lease sweeper.
func Register(app core.App) {
	app.OnServe().BindFunc(func(se *core.ServeEvent) error {
		RegisterRoutes(se.Router)
		return se.Next()
	})

	app.OnRecordAfterDeleteSuccess("recordings").BindFunc(func(e *core.RecordEvent) error {
		removeRecordingDir(e.App, e.Record.Id)
		return e.Next()
	})
	registerEvents(app)

	app.OnBootstrap().BindFunc(func(e *core.BootstrapEvent) error {
		// The settings live in the database, so the bootstrap has to finish before
		// they can be read and saved.
		if err := e.Next(); err != nil {
			return err
		}
		settings := e.App.Settings()
		if applyDeploymentSettings(settings) {
			if err := e.App.Save(settings); err != nil {
				return err
			}
		}
		return nil
	})

	app.Cron().MustAdd("taiga_requeue_expired_analyses", "* * * * *", func() {
		var requeued int64
		err := app.RunInTransaction(func(txApp core.App) error {
			var err error
			requeued, err = requeueExpired(txApp)
			return err
		})
		if err == nil && requeued > 0 {
			app.Logger().Info("taiga: expired analysis leases", "count", requeued)
		}
	})
}

// RegisterRoutes mounts every declared route. Each handler runs behind the route's
// access check and the error shape, so no endpoint can skip either.
func RegisterRoutes(router *router.Router[*core.RequestEvent]) {
	// PocketBase's rate limiter answers before the route handlers run, so its error
	// never reaches handle(). Bound just outside the limiter, this turns it into the
	// one error shape of this API; the limiter has a Priority of -1000.
	router.Bind(&hook.Handler[*core.RequestEvent]{
		Id:       "taigaMiddlewareError",
		Priority: apis.DefaultRateLimitMiddlewarePriority - 1,
		Func:     middlewareError,
	})
	for _, route := range Routes() {
		mounted := router.Route(route.Method, route.Path, handle(route.Handler))
		// PocketBase loads the token and refuses a token from another collection; the
		// handler then works on the role that is left.
		if collections := route.Access.collections(); len(collections) > 0 {
			mounted.Bind(apis.RequireAuth(collections...))
		}
	}
}

var (
	recordingStates    = []string{"recording", "stopped", "forced_stop", "interrupted", "fetch_pending"}
	analysisStatusEnum = []string{statusQueued, statusRunning, statusSucceeded, statusFailed}
)

func idParam() routeParam {
	return pathParam("id", "The id of the recording.")
}

func fileParams() []routeParam {
	return []routeParam{
		idParam(),
		{
			Name: "name", In: "path", Required: true,
			Description: "A data file of the recording.",
			Schema:      map[string]any{"type": "string", "enum": dataFiles},
		},
	}
}

func binaryBody(description string) *routeBody {
	return &routeBody{
		ContentType: "application/octet-stream",
		Description: description,
		Schema:      func(*schemaReflector) map[string]any { return map[string]any{"type": "string", "format": "binary"} },
	}
}

func binaryResponse(status int, description string) routeResponse {
	return routeResponse{
		Status: status, Description: description, ContentType: "application/octet-stream",
		Schema: func(*schemaReflector) map[string]any { return map[string]any{"type": "string", "format": "binary"} },
	}
}

func htmlResponse(description string) routeResponse {
	return routeResponse{
		Status: 200, Description: description, ContentType: "text/html; charset=utf-8",
		Schema: func(*schemaReflector) map[string]any { return map[string]any{"type": "string"} },
	}
}

// Routes returns the API manifest.
func Routes() []openAPIRoute {
	uploadOffset := "The stored size of the file, which is also where the next chunk must start."
	return []openAPIRoute{
		// --- system ---
		{
			Method: http.MethodGet, Path: apiPrefix + "/health", OperationID: "getHealth", Tag: tagSystem,
			Summary:     "Check that the API is up",
			Description: "Liveness only: it answers while the process can serve requests.",
			Responses:   []routeResponse{jsonResponse(200, "The API is up.", HealthResponse{})},
			Handler:     GetHealth,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/version", OperationID: "getVersion", Tag: tagSystem,
			Summary: "Report the backend version and API revision",
			Description: "A client compares `api` with the revision it was built for before it does anything else; " +
				"only equal values are compatible.",
			Responses: []routeResponse{jsonResponse(200, "The versions of this build.", VersionResponse{})},
			Handler:   GetVersion,
		},
		{
			Method: http.MethodGet, Path: openAPIPath, OperationID: "getOpenAPI", Tag: tagSystem,
			Summary:     "Return this OpenAPI document",
			Description: "Generated from the route manifest when the process starts, so it always matches the routes this build serves.",
			Responses:   []routeResponse{jsonResponse(200, "The OpenAPI 3.1 document.", map[string]any{})},
			Handler:     ServeOpenAPI,
		},
		{
			Method: http.MethodGet, Path: docsPath, OperationID: "getDocs", Tag: tagSystem,
			Summary:     "Browse this API in Swagger UI",
			Description: "Interactive documentation of the running build. It loads the OpenAPI document and needs no internet access.",
			Responses:   []routeResponse{htmlResponse("The Swagger UI page.")},
			Handler:     ServeDocs,
		},
		{
			Method: http.MethodGet, Path: docsPath + "/{asset}", OperationID: "getDocsAsset", Tag: tagSystem,
			Summary:     "Static assets for the documentation page",
			Description: "The vendored Swagger UI release the documentation page loads. Not intended to be called directly.",
			Params: []routeParam{{
				Name: "asset", In: "path", Required: true, Description: "Vendored asset name.",
				Schema: map[string]any{"type": "string", "enum": docsAssetNames()},
			}},
			Responses: withErrors([]routeResponse{{
				Status: 200, Description: "The requested asset.", ContentType: "text/css; charset=utf-8",
				Schema: func(*schemaReflector) map[string]any { return map[string]any{"type": "string"} },
			}}, 404),
			Handler: ServeDocsAsset,
		},

		// --- access tokens ---
		{
			Method: http.MethodPost, Path: apiPrefix + "/tokens", OperationID: "createToken", Tag: tagTokens,
			Summary: "Create a read-only access token",
			Description: "A token a user's own assistant or script can read with. It reads the account's recordings " +
				"(metadata, not the raw files), analyses and results, and every write is refused. The token is shown " +
				"once, in this answer.",
			Access: accessUser,
			Body:   jsonBody(TokenRequest{}, "A name for the token, so it can be told apart in the list."),
			Responses: withErrors([]routeResponse{{
				Status: 201, Description: "The token, this once.", Headers: map[string]string{"Location": "The token's URL."},
				Schema: func(r *schemaReflector) map[string]any { return r.schemaOf(reflect.TypeOf(TokenView{})) },
			}}, 400, 401, 403),
			Handler: CreateToken,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/tokens", OperationID: "listTokens", Tag: tagTokens,
			Summary:     "List the account's access tokens",
			Description: "Their names, hints and creation times. A token itself is never shown again.",
			Access:      accessUser,
			Responses:   withErrors([]routeResponse{jsonResponse(200, "The tokens.", TokenList{})}, 401, 403),
			Handler:     ListTokens,
		},
		{
			Method: http.MethodDelete, Path: apiPrefix + "/tokens/{id}", OperationID: "deleteToken", Tag: tagTokens,
			Summary:     "Revoke an access token",
			Description: "The token stops working at once.",
			Access:      accessUser,
			Params:      []routeParam{pathParam("id", "The id of the token.")},
			Responses:   withErrors([]routeResponse{emptyResponse(204, "Revoked.")}, 401, 403, 404),
			Handler:     DeleteToken,
		},

		// --- recordings ---
		{
			Method: http.MethodGet, Path: apiPrefix + "/recordings", OperationID: "listRecordings", Tag: tagRecordings,
			Summary: "List the caller's recordings",
			Access:  accessRead,
			Params: append([]routeParam{
				queryEnum("state", "Only recordings in this state.", recordingStates),
				queryBool("complete", "Only complete (or only incomplete) recordings."),
				queryString("from", "Only recordings that started at or after this time (RFC 3339 or a date)."),
				queryString("to", "Only recordings that started at or before this time."),
				queryEnum("sort", "Order of the list; default `-started_at`.",
					[]string{"started_at", "-started_at", "created", "-created", "duration_s", "-duration_s"}),
			}, limitOffsetParams()...),
			Responses: withErrors([]routeResponse{jsonResponse(200, "A page of recordings.", RecordingList{})}, 400, 401, 403),
			Handler:   ListRecordings,
		},
		{
			Method: http.MethodPut, Path: apiPrefix + "/recordings/{id}", OperationID: "putRecording", Tag: tagRecordings,
			Summary: "Create a recording, or replace its metadata",
			Description: "The body is the recording's `meta.json`, whose `session_id` must equal the id in the path. " +
				"The client chooses the id (1 to 40 letters, digits, `_` or `-`), so repeating the request after a lost " +
				"response is safe. A complete recording can no longer change.",
			Access: accessUser,
			Params: []routeParam{idParam()},
			Body:   jsonBody(RecordingMeta{}, "The recording's meta.json."),
			Responses: withErrors([]routeResponse{
				{Status: 201, Description: "The recording was created.", Headers: map[string]string{"Location": "The recording's URL."},
					Schema: func(r *schemaReflector) map[string]any { return r.schemaOf(reflect.TypeOf(RecordingView{})) }},
				jsonResponse(200, "The recording existed and its metadata was replaced.", RecordingView{}),
			}, 400, 401, 403, 409, 413, 422),
			Handler: PutRecording,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/recordings/{id}", OperationID: "getRecording", Tag: tagRecordings,
			Summary:     "Get a recording",
			Description: "A worker may read a recording while it holds the lease of a running analysis of it.",
			Access:      accessReadAny,
			Params:      []routeParam{idParam()},
			Responses:   withErrors([]routeResponse{jsonResponse(200, "The recording.", RecordingView{})}, 401, 403, 404),
			Handler:     GetRecording,
		},
		{
			Method: http.MethodPatch, Path: apiPrefix + "/recordings/{id}", OperationID: "patchRecording", Tag: tagRecordings,
			Summary: "Mark a recording complete",
			Description: "The only change a recording accepts: `{\"complete\": true}` seals it once every file has been " +
				"uploaded. It is refused while the recording is still in progress or when it has no stream data. A complete " +
				"recording cannot be reopened.",
			Access:    accessUser,
			Params:    []routeParam{idParam()},
			Body:      jsonBody(RecordingPatch{}, "The change."),
			Responses: withErrors([]routeResponse{jsonResponse(200, "The recording.", RecordingView{})}, 400, 401, 403, 404, 409, 422),
			Handler:   PatchRecording,
		},
		{
			Method: http.MethodDelete, Path: apiPrefix + "/recordings/{id}", OperationID: "deleteRecording", Tag: tagRecordings,
			Summary:     "Delete a recording",
			Description: "Removes the recording, its files and its analyses.",
			Access:      accessUser,
			Params:      []routeParam{idParam()},
			Responses:   withErrors([]routeResponse{emptyResponse(204, "Deleted.")}, 401, 403, 404),
			Handler:     DeleteRecording,
		},

		// --- recording files ---
		{
			Method: http.MethodGet, Path: apiPrefix + "/recordings/{id}/files", OperationID: "listFiles", Tag: tagFiles,
			Summary:     "List a recording's files",
			Description: "Sizes are stored bytes, which are compressed bytes for a gzip file.",
			Access:      accessAny,
			Params:      []routeParam{idParam()},
			Responses:   withErrors([]routeResponse{jsonResponse(200, "The files that exist.", FileList{})}, 401, 403, 404),
			Handler:     ListFiles,
		},
		{
			Method: http.MethodHead, Path: apiPrefix + "/recordings/{id}/files/{name}", OperationID: "headFile", Tag: tagFiles,
			Summary:     "Find out where to resume an upload",
			Description: "`Upload-Offset` is the stored size and `Content-Encoding` the file's encoding. 404 until the file has its first chunk.",
			Access:      accessAny,
			Params:      fileParams(),
			Responses: withErrors([]routeResponse{{
				Status: 200, Description: "The file exists.",
				Headers: map[string]string{headerUploadOffset: uploadOffset, "Content-Encoding": "gzip for a compressed file."},
			}}, 401, 403, 404),
			Handler: HeadFile,
		},
		{
			Method: http.MethodPatch, Path: apiPrefix + "/recordings/{id}/files/{name}", OperationID: "appendFile", Tag: tagFiles,
			Summary: "Append a chunk to a file",
			Description: "`Upload-Offset` must equal the stored size (0 creates the file); otherwise the answer is 409 " +
				"`offset_mismatch` with the current offset in `Upload-Offset`. Send `Content-Encoding: gzip` for a chunk " +
				"that is one complete gzip member, or nothing for plain bytes. A file's encoding is fixed by its first chunk. " +
				"A chunk that would push the account's stored recordings beyond its quota (TAIGA_USER_QUOTA_GB) is refused " +
				"with 413 `storage_quota_exceeded`. " +
				"The backend stores the bytes as they are; checking frames is the worker's job.",
			Access: accessUser,
			Params: append(fileParams(),
				headerParam(headerUploadOffset, "Where this chunk starts.", true),
				headerParam("Content-Encoding", "`gzip` when the chunk is a gzip member.", false)),
			Body: binaryBody("The chunk."),
			Responses: withErrors([]routeResponse{{
				Status: 204, Description: "The chunk was appended.",
				Headers: map[string]string{headerUploadOffset: "The new stored size of the file."},
			}}, 400, 401, 403, 404, 409, 413),
			Handler: PatchFile,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/recordings/{id}/files/{name}", OperationID: "getFile", Tag: tagFiles,
			Summary: "Download a file",
			Description: "A gzip file is inflated unless the request says `Accept-Encoding: gzip`, in which case it is sent as " +
				"stored with `Content-Encoding: gzip`. A worker may read it while it holds a lease on the recording.",
			Access:    accessAny,
			Params:    append(fileParams(), headerParam("Accept-Encoding", "`gzip` to receive a compressed file as stored.", false)),
			Responses: withErrors([]routeResponse{binaryResponse(200, "The file's bytes.")}, 401, 403, 404),
			Handler:   GetFile,
		},
		{
			Method: http.MethodDelete, Path: apiPrefix + "/recordings/{id}/files/{name}", OperationID: "deleteFile", Tag: tagFiles,
			Summary:     "Delete one file",
			Description: "For restarting a file. Refused once the recording is complete.",
			Access:      accessUser,
			Params:      fileParams(),
			Responses:   withErrors([]routeResponse{emptyResponse(204, "Deleted.")}, 401, 403, 404, 409),
			Handler:     DeleteFile,
		},

		// --- analyses ---
		{
			Method: http.MethodPost, Path: apiPrefix + "/recordings/{id}/analyses", OperationID: "createAnalysis", Tag: tagAnalyses,
			Summary: "Request an analysis",
			Description: "Creates a queued analysis, or returns the existing one for the same recording, algorithm, version and " +
				"params. A failed existing analysis is replaced by a fresh queued one, which is how a retry is made. The version " +
				"defaults to the newest a worker offers; a worker that stopped announcing itself (TAIGA_WORKER_TTL_S, 900 s) is ignored unless none is left.",
			Access: accessUser,
			Params: []routeParam{idParam()},
			Body:   jsonBody(AnalysisRequest{}, "The algorithm to run."),
			Responses: withErrors([]routeResponse{
				{Status: 201, Description: "A new analysis was queued.", Headers: map[string]string{"Location": "The analysis' URL."},
					Schema: func(r *schemaReflector) map[string]any { return r.schemaOf(reflect.TypeOf(AnalysisView{})) }},
				jsonResponse(200, "The analysis already existed.", AnalysisView{}),
			}, 400, 401, 403, 404, 409, 422),
			Handler: CreateAnalysis,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/recordings/{id}/analyses", OperationID: "listRecordingAnalyses", Tag: tagAnalyses,
			Summary: "List the analyses of a recording",
			Access:  accessRead,
			Params: append([]routeParam{
				idParam(),
				queryEnum("status", "Only analyses in this status.", analysisStatusEnum),
				queryEnum("expand", "`result` includes the full result of each analysis.", []string{"result"}),
				queryEnum("sort", "Order of the list; default `-created`.", []string{"created", "-created"}),
			}, limitOffsetParams()...),
			Responses: withErrors([]routeResponse{jsonResponse(200, "A page of analyses.", AnalysisList{})}, 400, 401, 403, 404),
			Handler:   ListRecordingAnalyses,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/analyses", OperationID: "listAnalyses", Tag: tagAnalyses,
			Summary:     "List analyses across recordings",
			Description: "A user sees their own; a worker sees only the analyses it holds the lease of.",
			Access:      accessReadAny,
			Params: append([]routeParam{
				queryString("recording", "Only analyses of this recording."),
				queryEnum("status", "Only analyses in this status.", analysisStatusEnum),
				queryEnum("expand", "`result` includes the full result of each analysis.", []string{"result"}),
				queryEnum("sort", "Order of the list; default `-created`.", []string{"created", "-created"}),
			}, limitOffsetParams()...),
			Responses: withErrors([]routeResponse{jsonResponse(200, "A page of analyses.", AnalysisList{})}, 400, 401, 403),
			Handler:   ListAnalyses,
		},
		{
			Method: http.MethodGet, Path: apiPrefix + "/analyses/{id}", OperationID: "getAnalysis", Tag: tagAnalyses,
			Summary:     "Get an analysis with its result",
			Description: "A worker may read an analysis while it holds the lease of it.",
			Access:      accessReadAny,
			Params:      []routeParam{pathParam("id", "The id of the analysis.")},
			Responses:   withErrors([]routeResponse{jsonResponse(200, "The analysis.", AnalysisView{})}, 401, 403, 404),
			Handler:     GetAnalysis,
		},
		{
			Method: http.MethodDelete, Path: apiPrefix + "/analyses/{id}", OperationID: "deleteAnalysis", Tag: tagAnalyses,
			Summary:     "Delete an analysis",
			Description: "A running analysis is cancelled: the worker's next call about it gets 404.",
			Access:      accessUser,
			Params:      []routeParam{pathParam("id", "The id of the analysis.")},
			Responses:   withErrors([]routeResponse{emptyResponse(204, "Deleted.")}, 401, 403, 404),
			Handler:     DeleteAnalysis,
		},

		// --- events ---
		{
			Method: http.MethodGet, Path: apiPrefix + "/events", OperationID: "streamEvents", Tag: tagEvents,
			Summary: "Stream changes as Server-Sent Events",
			Description: "A `text/event-stream` that stays open. `analysis` events (`{id, recording, status}`) and `recording` " +
				"events (`{id, change}`) tell a user that something they show has changed; a worker receives `analysis` events " +
				"with status `queued`, which is its signal to claim. Events carry no data and are not replayed: fetch the " +
				"resource, and treat a reconnect as \"refetch what I show\". A comment line is sent every 20 seconds.",
			Access: accessAny,
			Responses: withErrors([]routeResponse{{
				Status: 200, Description: "The event stream.", ContentType: "text/event-stream",
				Schema: func(r *schemaReflector) map[string]any {
					return map[string]any{
						"type":        "string",
						"description": "`event: analysis` with an AnalysisEvent, or `event: recording` with a RecordingEvent, as JSON `data`.",
					}
				},
			}}, 401, 403),
			Handler: StreamEvents,
		},

		// --- algorithms ---
		{
			Method: http.MethodGet, Path: apiPrefix + "/algorithms", OperationID: "listAlgorithms", Tag: tagAlgorithms,
			Summary:     "List the algorithms workers can run",
			Description: "One entry per algorithm id and version that a registered worker has announced.",
			Access:      accessUser,
			Responses:   withErrors([]routeResponse{jsonResponse(200, "The algorithms.", AlgorithmList{})}, 401, 403),
			Handler:     ListAlgorithms,
		},

		// --- worker ---
		{
			Method: http.MethodPut, Path: apiPrefix + "/workers/me", OperationID: "putWorker", Tag: tagWorkers,
			Summary:     "Announce what this worker can run",
			Description: "Replaces the worker's announcement and refreshes its last-seen time.",
			Access:      accessWorker,
			Body:        jsonBody(WorkerAnnouncement{}, "The worker and its algorithms."),
			Responses:   withErrors([]routeResponse{emptyResponse(204, "Recorded.")}, 400, 401, 403),
			Handler:     PutWorker,
		},
		{
			Method: http.MethodPost, Path: apiPrefix + "/analyses/claim", OperationID: "claimAnalysis", Tag: tagWorkers,
			Summary: "Take the next queued analysis",
			Description: "Atomically takes the oldest queued analysis the worker can run and marks it running for a lease. " +
				"Answers 204 when there is nothing to do. Expired leases are put back in the queue first.",
			Access: accessWorker,
			Body:   &routeBody{Optional: true, Description: "Which algorithms to take, and how long to lease.", Schema: jsonBody(ClaimRequest{}, "").Schema},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The claimed analysis.", AnalysisView{}),
				emptyResponse(204, "Nothing is queued."),
			}, 400, 401, 403),
			Handler: ClaimAnalysis,
		},
		{
			Method: http.MethodPost, Path: apiPrefix + "/analyses/{id}/lease", OperationID: "renewAnalysisLease", Tag: tagWorkers,
			Summary: "Extend the lease on a running analysis",
			Description: "Sets the lease to now plus the lease length, so a job that runs longer than one lease keeps its " +
				"analysis. Only the worker that holds a live lease may renew it; the work being reassigned or its lease " +
				"having expired is 409, and an analysis that no longer exists is 404.",
			Access: accessWorker,
			Params: []routeParam{pathParam("id", "The id of the analysis.")},
			Body:   &routeBody{Optional: true, Description: "How long to extend the lease.", Schema: jsonBody(LeaseRequest{}, "").Schema},
			Responses: withErrors([]routeResponse{
				jsonResponse(200, "The analysis.", AnalysisView{}),
			}, 400, 401, 403, 404, 409),
			Handler: RenewAnalysis,
		},
		{
			Method: http.MethodPost, Path: apiPrefix + "/analyses/{id}/complete", OperationID: "completeAnalysis", Tag: tagWorkers,
			Summary: "Store the result of an analysis",
			Description: "Marks the analysis succeeded. A result that does not match the analysis or breaks the contract " +
				"envelope fails it for good and is answered with 422.",
			Access:    accessWorker,
			Params:    []routeParam{pathParam("id", "The id of the analysis.")},
			Body:      jsonBody(CompleteRequest{}, "The result."),
			Responses: withErrors([]routeResponse{jsonResponse(200, "The analysis.", AnalysisView{})}, 400, 401, 403, 404, 409, 422),
			Handler:   CompleteAnalysis,
		},
		{
			Method: http.MethodPost, Path: apiPrefix + "/analyses/{id}/fail", OperationID: "failAnalysis", Tag: tagWorkers,
			Summary: "Report that an analysis failed",
			Description: "A retryable failure goes back to the queue until its attempts are used up; otherwise the analysis " +
				"is failed.",
			Access:    accessWorker,
			Params:    []routeParam{pathParam("id", "The id of the analysis.")},
			Body:      jsonBody(FailRequest{}, "The error."),
			Responses: withErrors([]routeResponse{jsonResponse(200, "The analysis.", AnalysisView{})}, 400, 401, 403, 404, 409),
			Handler:   FailAnalysis,
		},
	}
}
