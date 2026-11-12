package api

import (
	"os"
	"slices"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// applyDeploymentSettings writes the settings the TAIGA_* variables configure and
// reports whether anything changed, so the bootstrap saves only when it has to.
//
// The trusted proxy headers depend on the deployment, so they are written every time: no
// header is trusted unless one is named, and the client address cannot be chosen by the
// caller. The app name and the app URL belong to the operator in the dashboard, the
// rate-limit rules and the backup schedule are written once by the migrations, and the
// bootstrap does not touch them.
func applyDeploymentSettings(settings *core.Settings) bool {
	changed := false

	headers := headerList(os.Getenv("TAIGA_TRUSTED_PROXY_HEADERS"))
	if !slices.Equal(settings.TrustedProxy.Headers, headers) {
		settings.TrustedProxy.Headers = headers
		changed = true
	}

	return changed
}

// headerList reads a comma-separated list of header names. Without one, clients
// connect directly and no header is trusted.
func headerList(raw string) []string {
	var headers []string
	for _, part := range strings.Split(raw, ",") {
		if part = strings.TrimSpace(part); part != "" {
			headers = append(headers, part)
		}
	}
	return headers
}
