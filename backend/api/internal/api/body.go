package api

import (
	"encoding/json"
	"errors"
	"io"

	"github.com/pocketbase/pocketbase/core"

	"github.com/rbmr/TaigaSleep/backend/api/internal/results"
)

// The size limits of the JSON bodies endpoints accept. A request body is only
// buffered up to its endpoint's limit, plus the one byte that lets an oversized
// result be recognised instead of silently truncated.
const (
	// bodyLimitSmall covers a login, a token and a recording patch.
	bodyLimitSmall = 4 * 1024
	// bodyLimitQueue covers what a worker sends while it runs an analysis.
	bodyLimitQueue = 64 * 1024
	// bodyLimitAnnouncement covers a worker announcing up to 100 algorithms.
	bodyLimitAnnouncement = 512 * 1024
	// bodyLimitResult is the one large body the API takes. It is the same limit
	// the stored result has, so the two cannot disagree.
	bodyLimitResult = results.MaxBytes
)

// errResultTooLarge is returned when a complete request reads past bodyLimitResult.
// The handler fails the analysis instead of leaving it running until its lease ends.
var errResultTooLarge = errors.New("analysis result is too large")

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

// decodeResultBody decodes a complete request and reports a body that grows past
// bodyLimitResult, which decodeBody's limit would otherwise turn into a truncated
// body. The caller fails the analysis for it.
func decodeResultBody(e *core.RequestEvent, body any, expected string) error {
	raw, err := io.ReadAll(io.LimitReader(e.Request.Body, bodyLimitResult+1))
	if err != nil {
		return badRequest("invalid_body", expected)
	}
	if int64(len(raw)) > bodyLimitResult {
		return errResultTooLarge
	}
	if err := json.Unmarshal(raw, body); err != nil {
		return badRequest("invalid_body", expected)
	}
	return nil
}
