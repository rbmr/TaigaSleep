package api

import (
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/router"
)

// ErrorResponse is the body of every error from every endpoint. Code is a stable
// lowercase identifier a client may switch on; Message is for a person.
type ErrorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
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

// handle turns a handler's error into the one error shape. It is applied to every
// route by RegisterRoutes, so no endpoint can answer in another format.
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
			var pbErr *router.ApiError
			if errors.As(err, &pbErr) && pbErr.Status < http.StatusInternalServerError {
				apiErr = newError(pbErr.Status, statusCode(pbErr.Status), pbErr.Message)
			} else {
				e.App.Logger().Error("taiga: request failed", "method", e.Request.Method, "path", e.Request.URL.Path, "error", err)
				apiErr = newError(http.StatusInternalServerError, "internal", "internal error")
			}
		}
		for name, value := range apiErr.Headers {
			e.Response.Header().Set(name, value)
		}
		if e.Request.Method == http.MethodHead {
			// HEAD answers carry no body, so the status alone has to say it.
			e.Response.WriteHeader(apiErr.Status)
			return nil
		}
		return e.JSON(apiErr.Status, ErrorResponse{Code: apiErr.Code, Message: apiErr.Message})
	}
}

// middlewareError turns an error from a middleware that runs before the route handlers
// into the one error shape. PocketBase's rate limiter is the one that answers this way;
// anything outside /v1 keeps its own shape.
func middlewareError(e *core.RequestEvent) error {
	err := e.Next()
	if err == nil || e.Written() || !strings.HasPrefix(e.Request.URL.Path, apiPrefix+"/") {
		return err
	}
	var pbErr *router.ApiError
	if !errors.As(err, &pbErr) {
		return err
	}
	message := pbErr.Message
	if pbErr.Status == http.StatusTooManyRequests {
		message = "too many requests, try again later"
	}
	return e.JSON(pbErr.Status, ErrorResponse{Code: statusCode(pbErr.Status), Message: message})
}

func statusCode(status int) string {
	switch status {
	case http.StatusBadRequest:
		return "bad_request"
	case http.StatusUnauthorized:
		return "unauthorized"
	case http.StatusForbidden:
		return "forbidden"
	case http.StatusNotFound:
		return "not_found"
	case http.StatusConflict:
		return "conflict"
	case http.StatusRequestEntityTooLarge:
		return "too_large"
	case http.StatusUnprocessableEntity:
		return "unprocessable"
	case http.StatusTooManyRequests:
		return "too_many_requests"
	}
	return "error"
}
