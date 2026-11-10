package api

import (
	"encoding/json"
	"errors"
	"io"

	"github.com/pocketbase/pocketbase/core"
)

// The size limits of the JSON bodies endpoints accept. A request body is never
// buffered beyond its endpoint's limit.
const (
	// bodyLimitSmall covers a login, a token and a recording patch.
	bodyLimitSmall = 4 * 1024
	// bodyLimitAnalysis covers an analysis request, whose params travel with it.
	bodyLimitAnalysis = 32 * 1024
	// bodyLimitQueue covers what a worker sends while it runs an analysis.
	bodyLimitQueue = 64 * 1024
	// bodyLimitAnnouncement covers a worker announcing up to 100 algorithms.
	bodyLimitAnnouncement = 512 * 1024
	// bodyLimitResult covers a finished analysis, the one large body the API takes.
	bodyLimitResult = 8 * 1024 * 1024
)

// decodeBody decodes the JSON body of the request into body, reading at most
// limit bytes. optional is set by endpoints whose body may be left out: an empty
// body then leaves body at its zero value instead of failing. A body that is
// malformed or not the object the endpoint takes is answered with invalid_body
// and expected as the message, so every endpoint fails a bad body the same way.
func decodeBody(e *core.RequestEvent, limit int64, body any, expected string, optional bool) error {
	if optional && e.Request.ContentLength == 0 {
		return nil
	}
	err := json.NewDecoder(io.LimitReader(e.Request.Body, limit)).Decode(body)
	if err != nil && !(optional && errors.Is(err, io.EOF)) {
		return badRequest("invalid_body", expected)
	}
	return nil
}
