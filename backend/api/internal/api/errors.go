package api

import (
	"errors"
	"fmt"
	"net/http"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/router"
)

// ErrorResponse is the body of every error. It is PocketBase's own error shape, so
// the record API and the custom routes answer alike: Status is the HTTP status,
// Message is for a person, and Data carries the API's stable lowercase code under
// "code" (or PocketBase's per-field validation errors).
type ErrorResponse struct {
	Status  int            `json:"status"`
	Message string         `json:"message"`
	Data    map[string]any `json:"data"`
}

// apiError is what handlers return for a failure the client caused or can act on.
// Anything else a handler returns is logged and answered as a 500.
type apiError struct {
	Status  int
	Code    string
	Message string
	// Headers are set on the error response, e.g. Upload-Offset on a conflict.
	Headers map[string]string
}

func (e *apiError) Error() string { return fmt.Sprintf("%d %s: %s", e.Status, e.Code, e.Message) }

func newError(status int, code, message string) *apiError {
	return &apiError{Status: status, Code: code, Message: message}
}

func badRequest(code, message string) *apiError {
	return newError(http.StatusBadRequest, code, message)
}
func unauthorized(message string) *apiError {
	return newError(http.StatusUnauthorized, "unauthorized", message)
}
func forbidden(message string) *apiError {
	return newError(http.StatusForbidden, "forbidden", message)
}
func notFound(what string) *apiError {
	return newError(http.StatusNotFound, "not_found", what+" not found")
}
func conflict(code, message string) *apiError {
	return newError(http.StatusConflict, code, message)
}
func tooLarge(code, message string) *apiError {
	return newError(http.StatusRequestEntityTooLarge, code, message)
}
func unprocessable(code, message string) *apiError {
	return newError(http.StatusUnprocessableEntity, code, message)
}

// handle turns a handler's error into PocketBase's error shape, the same shape the
// record API answers with: {"status", "message", "data"}. The API's stable code is
// under data.code, alongside the field errors PocketBase itself reports for a
// record route. It is applied to every custom route by RegisterRoutes, so no
// endpoint can answer in another format.
func handle(handler func(*core.RequestEvent) error) func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		// Nothing the API answers is meant to be cached; a handler that serves a
		// static asset overrides this.
		e.Response.Header().Set("Cache-Control", "no-store")
		err := handler(e)
		if err == nil {
			return nil
		}
		var apiErr *apiError
		if !errors.As(err, &apiErr) {
			// PocketBase's own error already has the shape; anything else is ours.
			var pbErr *router.ApiError
			if errors.As(err, &pbErr) {
				if pbErr.Status >= http.StatusInternalServerError {
					e.App.Logger().Error("taiga: request failed", "method", e.Request.Method, "path", e.Request.URL.Path, "error", err)
				}
				return pbErr
			}
			e.App.Logger().Error("taiga: request failed", "method", e.Request.Method, "path", e.Request.URL.Path, "error", err)
			return e.InternalServerError("internal error", nil)
		}
		for name, value := range apiErr.Headers {
			e.Response.Header().Set(name, value)
		}
		apiResponse := e.Error(apiErr.Status, apiErr.Message, nil)
		apiResponse.Data = map[string]any{"code": apiErr.Code}
		return apiResponse
	}
}
