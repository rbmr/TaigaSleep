// Package version carries the backend release version and the API contract
// revision.
//
// backend/version.properties is the source of truth for release tooling, and a
// unit test keeps these constants in step with it. The app compares API with the
// requires_api value it was built with; see docs/versioning.md.
package version

const (
	// Number is the backend release version. It is reported by the health route
	// and used for the image and release tags.
	Number = "0.1.0"

	// API is the app/backend contract revision. Bump it whenever a change makes
	// a released app fail against this API.
	API = 1
)
