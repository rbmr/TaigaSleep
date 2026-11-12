package api

import (
	"github.com/pocketbase/pocketbase/core"
)

// The record API of recordings and analyses is PocketBase's, so its documentation
// is generated from the collection schema at startup rather than written by hand:
// every field, type, enum, bound and required flag in the document is the one the
// server validates against. Only what a collection cannot describe — the routes,
// their query parameters and their purpose — is stated here.

// recordCollections lists the collections whose record API the document describes.
// The schema name is the singular the routes reference; the noun is the word the
// summaries use.
var recordCollections = []struct {
	name     string
	singular string
	noun     string
	listID   string
}{
	{name: "recordings", singular: "Recording", noun: "recording", listID: "listRecordings"},
	{name: "analyses", singular: "Analysis", noun: "analysis", listID: "listAnalyses"},
}

func recordAPIRoutes(app core.App) ([]openAPIRoute, map[string]any) {
	if app == nil {
		return nil, nil
	}
	routes := []openAPIRoute{}
	schemas := map[string]any{}
	for _, entry := range recordCollections {
		collection, err := app.FindCollectionByNameOrId(entry.name)
		if err != nil {
			continue
		}
		schemas[entry.singular] = recordSchemaOf(collection)
		schemas[entry.singular+"List"] = recordListSchema(entry.singular)

		base := "/api/collections/" + collection.Name + "/records"
		recordRef := func(*schemaReflector) map[string]any {
			return map[string]any{"$ref": "#/components/schemas/" + entry.singular}
		}
		listRef := func(*schemaReflector) map[string]any {
			return map[string]any{"$ref": "#/components/schemas/" + entry.singular + "List"}
		}
		idParams := []routeParam{{
			Name: "id", In: "path", Required: true,
			Description: "The record id.",
			Schema:      map[string]any{"type": "string"},
		}}
		noun := entry.noun
		routes = append(routes,
			openAPIRoute{
				Method: "GET", Path: base, OperationID: entry.listID,
				Tag:     tagFor(entry.name),
				Summary: "List " + entry.name + " the caller may see",
				Description: "PocketBase's record API, filtered by the collection's list rule: a user sees their own, " +
					"an access token its owner's, and a worker a recording (or an analysis of one) only while it holds a " +
					"live lease. Filtering, sorting, pagination and relation expansion are PocketBase's.",
				Access:    accessRules,
				Params:    recordListParams(),
				Responses: withErrors([]routeResponse{{Status: 200, Description: "A page of " + entry.name + ".", Schema: listRef}}, 400, 401, 403, 429),
			},
			openAPIRoute{
				Method: "POST", Path: base, OperationID: "create" + entry.singular,
				Tag:         tagFor(entry.name),
				Summary:     "Create a " + noun,
				Description: recordCreateDescription(entry.name),
				Access:      accessRules,
				Body:        &routeBody{Description: "The record's fields.", Schema: recordRef},
				Responses: withErrors([]routeResponse{{Status: 200, Description: "The stored " + noun + ".", Schema: recordRef}},
					400, 401, 403, 409, 413, 422, 429),
			},
			openAPIRoute{
				Method: "GET", Path: base + "/{id}", OperationID: "get" + entry.singular,
				Tag:         tagFor(entry.name),
				Summary:     "Get a " + noun,
				Description: "One record the caller's view rule accepts; anything else is 404 so ids do not leak.",
				Access:      accessRules,
				Params:      append(idParams, recordViewParams()...),
				Responses:   withErrors([]routeResponse{{Status: 200, Description: "The " + noun + ".", Schema: recordRef}}, 401, 403, 404),
			},
			openAPIRoute{
				Method: "PATCH", Path: base + "/{id}", OperationID: "update" + entry.singular,
				Tag:         tagFor(entry.name),
				Summary:     "Change a " + noun,
				Description: recordUpdateDescription(entry.name),
				Access:      accessRules,
				Params:      idParams,
				Body:        &routeBody{Description: "The fields to change.", Schema: recordRef},
				Responses: withErrors([]routeResponse{{Status: 200, Description: "The changed " + noun + ".", Schema: recordRef}},
					400, 401, 403, 404, 409, 422),
			},
			openAPIRoute{
				Method: "DELETE", Path: base + "/{id}", OperationID: "delete" + entry.singular,
				Tag:         tagFor(entry.name),
				Summary:     "Delete a " + noun,
				Description: recordDeleteDescription(entry.name),
				Access:      accessRules,
				Params:      idParams,
				Responses:   withErrors([]routeResponse{emptyResponse(204, "Deleted.")}, 401, 403, 404),
			},
		)
	}
	return routes, schemas
}

func tagFor(collection string) string {
	if collection == "analyses" {
		return tagAnalyses
	}
	return tagRecordings
}

func recordCreateDescription(collection string) string {
	if collection == "recordings" {
		return "The body carries the recording's `id` and its `meta.json`; the owner, state, device, times, " +
			"duration and file totals are derived from the metadata by the server. `meta.session_id` must equal " +
			"the id. Repeating the create of an open recording replaces its metadata, so a retry after a lost " +
			"answer is safe; a sealed recording is refused."
	}
	return "The body is `recording`, `algorithm`, `version` and `params`. The version defaults to the newest a " +
		"worker offers. The same request returns the analysis that already exists for the same recording, " +
		"algorithm, version and parameters; a failed one is replaced, which is how a retry is made. The " +
		"recording must be sealed, and the parameters must match the algorithm's announced schema."
}

func recordUpdateDescription(collection string) string {
	if collection == "recordings" {
		return "`meta` replaces the recording's metadata while it is open; `complete: true` seals it once every " +
			"file has been uploaded. A recording that is still being recorded or whose rest is on the tracker " +
			"cannot be sealed, and a sealed recording can no longer change."
	}
	return "An analysis cannot be changed through the record API: the worker queue's own routes fill it in. " +
		"The update rule therefore allows no client."
}

func recordDeleteDescription(collection string) string {
	if collection == "recordings" {
		return "Removes the recording, its files, its analyses and its bytes from the account's storage total."
	}
	return "Removes the analysis. Deleting a running one cancels it: the worker's next call about it is 404. " +
		"Analyses of a deleted recording go with it."
}

func recordListParams() []routeParam {
	return []routeParam{
		{Name: "page", In: "query", Description: "Page number, 1-based.", Schema: map[string]any{"type": "integer", "minimum": 1}},
		{Name: "perPage", In: "query", Description: "Records per page.", Schema: map[string]any{"type": "integer", "minimum": 1, "maximum": 500}},
		{Name: "sort", In: "query", Description: "Comma-separated fields, `-` for descending.", Schema: map[string]any{"type": "string"}},
		{Name: "filter", In: "query", Description: "PocketBase filter expression, combined with the collection's list rule.", Schema: map[string]any{"type": "string"}},
		{Name: "expand", In: "query", Description: "Relation fields to expand.", Schema: map[string]any{"type": "string"}},
		{Name: "fields", In: "query", Description: "Only these fields, comma-separated.", Schema: map[string]any{"type": "string"}},
		{Name: "skipTotal", In: "query", Description: "Skip the total count.", Schema: map[string]any{"type": "boolean"}},
	}
}

func recordViewParams() []routeParam {
	return []routeParam{
		{Name: "expand", In: "query", Description: "Relation fields to expand.", Schema: map[string]any{"type": "string"}},
		{Name: "fields", In: "query", Description: "Only these fields, comma-separated.", Schema: map[string]any{"type": "string"}},
	}
}

func recordListSchema(singular string) map[string]any {
	return map[string]any{
		"type":     "object",
		"required": []string{"page", "perPage", "totalItems", "totalPages", "items"},
		"properties": map[string]any{
			"page":       map[string]any{"type": "integer"},
			"perPage":    map[string]any{"type": "integer"},
			"totalItems": map[string]any{"type": "integer"},
			"totalPages": map[string]any{"type": "integer"},
			"items": map[string]any{
				"type":  "array",
				"items": map[string]any{"$ref": "#/components/schemas/" + singular},
			},
		},
	}
}

// recordSchemaOf turns a collection into the JSON Schema of one record. It is the
// same schema for a request and a response: a client sends the fields it may set and
// reads the ones the server derives.
func recordSchemaOf(collection *core.Collection) map[string]any {
	properties := map[string]any{}
	required := []string{}
	for _, field := range collection.Fields {
		if field.GetHidden() {
			continue
		}
		schema, isRequired := fieldSchema(field)
		if schema == nil {
			continue
		}
		properties[field.GetName()] = schema
		if isRequired {
			required = append(required, field.GetName())
		}
	}
	schema := map[string]any{"type": "object", "properties": properties}
	if len(required) > 0 {
		schema["required"] = required
	}
	return schema
}

func fieldSchema(field core.Field) (map[string]any, bool) {
	switch typed := field.(type) {
	case *core.TextField:
		schema := map[string]any{"type": "string"}
		if typed.Min > 0 {
			schema["minLength"] = typed.Min
		}
		if typed.Max > 0 {
			schema["maxLength"] = typed.Max
		}
		if typed.Pattern != "" {
			schema["pattern"] = typed.Pattern
		}
		return schema, typed.Required
	case *core.NumberField:
		kind := "number"
		if typed.OnlyInt {
			kind = "integer"
		}
		schema := map[string]any{"type": kind}
		if typed.Min != nil {
			schema["minimum"] = *typed.Min
		}
		if typed.Max != nil {
			schema["maximum"] = *typed.Max
		}
		return schema, typed.Required
	case *core.SelectField:
		values := make([]any, 0, len(typed.Values))
		for _, value := range typed.Values {
			values = append(values, value)
		}
		return map[string]any{"type": "string", "enum": values}, typed.Required
	case *core.BoolField:
		return map[string]any{"type": "boolean"}, false
	case *core.DateField:
		return map[string]any{"type": "string", "format": "date-time"}, typed.Required
	case *core.AutodateField:
		return map[string]any{"type": "string", "format": "date-time"}, false
	case *core.RelationField:
		if typed.MaxSelect > 1 {
			return map[string]any{"type": "array", "items": map[string]any{"type": "string"}}, typed.Required
		}
		return map[string]any{"type": "string"}, typed.Required
	case *core.JSONField:
		return map[string]any{"description": "A JSON value."}, typed.Required
	}
	return nil, false
}
