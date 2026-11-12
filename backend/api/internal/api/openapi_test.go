package api

import (
	"encoding/json"
	"io/fs"
	"os"
	"regexp"
	"strings"
	"testing"
)

// The document is generated at startup and served from memory, so there is no
// committed artifact to keep in sync. These tests only guard the generator
// itself: a malformed document or an unstyled docs page would otherwise only be
// noticed in a browser.

func TestOpenAPIDocumentIsValid(t *testing.T) {
	document := OpenAPIDocument()

	if version, _ := document["openapi"].(string); version != "3.1.0" {
		t.Fatalf("openapi version = %v, want 3.1.0", document["openapi"])
	}
	info, ok := document["info"].(map[string]any)
	if !ok || info["title"] == "" || info["version"] == "" {
		t.Fatalf("info is incomplete: %v", document["info"])
	}
	components, _ := document["components"].(map[string]any)
	schemes, _ := components["securitySchemes"].(map[string]any)
	if _, ok := schemes[securityBearer]; !ok {
		t.Fatalf("security scheme %q is missing", securityBearer)
	}

	paths, ok := document["paths"].(map[string]any)
	if !ok || len(paths) == 0 {
		t.Fatalf("document has no paths")
	}

	operationIDs := map[string]bool{}
	pathParameter := regexp.MustCompile(`\{([A-Za-z0-9_]+)\}`)

	// The documented auth routes share the id namespace with the mounted ones.
	for _, route := range AuthRoutes() {
		if operationIDs[route.OperationID] {
			t.Errorf("operationId %q is used twice", route.OperationID)
		}
		operationIDs[route.OperationID] = true
	}

	for _, route := range Routes() {
		path, ok := paths[route.Path].(map[string]any)
		if !ok {
			t.Errorf("%s %s is not in the document", route.Method, route.Path)
			continue
		}
		raw, ok := path[strings.ToLower(route.Method)]
		if !ok {
			t.Errorf("%s %s has no operation", route.Method, route.Path)
			continue
		}
		operation, ok := raw.(map[string]any)
		if !ok {
			t.Errorf("%s %s operation is malformed", route.Method, route.Path)
			continue
		}

		id, _ := operation["operationId"].(string)
		if id == "" {
			t.Errorf("%s %s has no operationId", route.Method, route.Path)
		} else if operationIDs[id] {
			t.Errorf("operationId %q is used twice", id)
		}
		operationIDs[id] = true

		responses, _ := operation["responses"].(map[string]any)
		if len(responses) == 0 {
			t.Errorf("%s %s documents no responses", route.Method, route.Path)
		}
		for status, entry := range responses {
			body, _ := entry.(map[string]any)
			if body["description"] == "" {
				t.Errorf("%s %s response %s has no description", route.Method, route.Path, status)
			}
		}

		declaredParams := map[string]bool{}
		if params, ok := operation["parameters"].([]any); ok {
			for _, item := range params {
				param, _ := item.(map[string]any)
				name, _ := param["name"].(string)
				declaredParams[name] = true
				if param["in"] == "path" && param["required"] != true {
					t.Errorf("%s %s path parameter %q is not required", route.Method, route.Path, name)
				}
			}
		}
		for _, match := range pathParameter.FindAllStringSubmatch(route.Path, -1) {
			if !declaredParams[match[1]] {
				t.Errorf("%s %s does not document path parameter %q", route.Method, route.Path, match[1])
			}
		}

		if route.Access != accessPublic {
			security, _ := operation["security"].([]any)
			if len(security) == 0 {
				t.Errorf("%s %s has no security requirement", route.Method, route.Path)
			}
		}
	}

	for _, route := range Routes() {
		if !strings.HasPrefix(route.Path, apiPrefix+"/") {
			t.Errorf("%s %s is outside %s", route.Method, route.Path, apiPrefix)
		}
		if route.Handler == nil {
			t.Errorf("%s %s has no handler", route.Method, route.Path)
		}
	}

	// PocketBase serves the auth routes, so they are documented without being mounted,
	// and the document still has to describe them: they are what a client calls first.
	for _, route := range AuthRoutes() {
		path, ok := paths[route.Path].(map[string]any)
		if !ok {
			t.Errorf("%s %s is not in the document", route.Method, route.Path)
			continue
		}
		operation, ok := path[strings.ToLower(route.Method)].(map[string]any)
		if !ok {
			t.Errorf("%s %s has no operation", route.Method, route.Path)
			continue
		}
		if operation["operationId"] == "" || operation["requestBody"] == nil && route.Body != nil {
			t.Errorf("%s %s is incompletely documented", route.Method, route.Path)
		}
		if route.Handler != nil {
			t.Errorf("%s %s is documented as external but has a handler", route.Method, route.Path)
		}
	}

	health, _ := paths["/v1/health"].(map[string]any)["get"].(map[string]any)
	if _, ok := health["security"]; ok {
		t.Errorf("health must stay public")
	}
}

func TestOpenAPIDocumentIsDeterministic(t *testing.T) {
	encode := func() string {
		data, err := json.MarshalIndent(OpenAPIDocument(), "", "  ")
		if err != nil {
			t.Fatalf("marshal document: %v", err)
		}
		return string(data)
	}
	if encode() != encode() {
		t.Fatalf("the generated document is not deterministic")
	}
}

func TestDocsPageServesVendoredSwaggerUI(t *testing.T) {
	for _, want := range []string{
		docsAssetPrefix + "swagger-ui.css",
		docsAssetPrefix + "swagger-ui-bundle.js",
		`url: "` + openAPIPath + `"`,
		"SwaggerUIBundle(",
	} {
		if !strings.Contains(docsPage, want) {
			t.Errorf("docs page does not reference %q", want)
		}
	}
	if _, ok := docsAssets["swagger-ui.css"]; !ok || docsAssets["swagger-ui.css"] != "text/css; charset=utf-8" {
		t.Errorf("CSS content type is wrong: %v", docsAssets)
	}

	for name := range docsAssets {
		data, err := fs.ReadFile(swaggerUI, "swaggerui/"+name)
		if err != nil {
			t.Errorf("read %s: %v", name, err)
			continue
		}
		if len(data) == 0 {
			t.Errorf("%s is empty", name)
		}
	}

	if _, err := os.Stat("swaggerui/LICENSE"); err != nil {
		t.Errorf("the vendored Swagger UI license is missing: %v", err)
	}
}

func TestNoCommittedOpenAPISpec(t *testing.T) {
	// The document is generated per process. A checked-in copy would silently
	// drift from the routes, so it must not come back.
	if _, err := os.Stat("../../openapi.json"); err == nil {
		t.Fatalf("backend/api/openapi.json should not exist: the document is generated at runtime")
	}
}
