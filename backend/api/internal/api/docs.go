package api

import (
	"embed"
	"io/fs"
	"net/http"

	"github.com/pocketbase/pocketbase/core"
)

// Swagger UI is vendored so the documentation page works without internet
// access and without a second service. It is unmodified upstream release
// 5.34.0, Apache-2.0, see swaggerui/LICENSE.
//
//go:embed swaggerui/swagger-ui.css swaggerui/swagger-ui-bundle.js
var swaggerUI embed.FS

const docsAssetPrefix = docsPath + "/"

// docsPage is the Swagger UI shell. It points at the generated document of the
// running build, so the page is never out of date with the routes it describes.
const docsPage = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Taiga Sleep API</title>
    <link rel="stylesheet" href="` + docsAssetPrefix + `swagger-ui.css" />
    <style>
      body { margin: 0; background: #fafafa; }
    </style>
  </head>
  <body>
    <div id="swagger-ui"></div>
    <script src="` + docsAssetPrefix + `swagger-ui-bundle.js"></script>
    <script>
      window.ui = SwaggerUIBundle({
        url: "` + openAPIPath + `",
        dom_id: "#swagger-ui",
        deepLinking: true,
        docExpansion: "list",
        displayRequestDuration: true,
        validatorUrl: null,
        presets: [SwaggerUIBundle.presets.apis],
      });
    </script>
  </body>
</html>
`

// ServeDocs serves the interactive documentation page.
func ServeDocs(e *core.RequestEvent) error {
	return e.HTML(http.StatusOK, docsPage)
}

// ServeDocsAsset serves the vendored Swagger UI files the page needs.
func ServeDocsAsset(e *core.RequestEvent) error {
	name := e.Request.PathValue("asset")
	contentType, ok := docsAssets[name]
	if !ok {
		return e.NotFoundError("asset not found", nil)
	}
	data, err := fs.ReadFile(swaggerUI, "swaggerui/"+name)
	if err != nil {
		return e.InternalServerError("asset is unavailable", err)
	}
	// The assets only change with the binary, which changes the ETag.
	e.Response.Header().Set("Cache-Control", "public, max-age=86400")
	e.Response.Header().Set("ETag", docsAssetETag)
	return e.Blob(http.StatusOK, contentType, data)
}

var docsAssets = map[string]string{
	"swagger-ui.css":       "text/css; charset=utf-8",
	"swagger-ui-bundle.js": "text/javascript; charset=utf-8",
}

// docsAssetETag is a stable identifier for the vendored asset set.
const docsAssetETag = `"swagger-ui-5.34.0"`

// docsAssetNames lists the served asset names for the route manifest.
func docsAssetNames() []string {
	return []string{"swagger-ui.css", "swagger-ui-bundle.js"}
}
