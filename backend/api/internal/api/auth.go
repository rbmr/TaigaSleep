package api

import "net/http"

// Users and workers log in through PocketBase's auth routes for their collections.
// PocketBase serves them, so they are documented here but not mounted by Routes(); the
// document would otherwise be missing what every client calls before anything else.

// AuthPasswordRequest is the body of PocketBase's auth-with-password route.
type AuthPasswordRequest struct {
	Identity string `json:"identity"`
	Password string `json:"password"`
}

// AuthResponse is what PocketBase answers on login and on a token refresh.
type AuthResponse struct {
	Token string `json:"token"`
	// Record is the account the token belongs to.
	Record AuthAccount `json:"record"`
}

type AuthAccount struct {
	ID    string `json:"id"`
	Email string `json:"email"`
}

// AuthRoutes are the auth routes clients use, documented but served by PocketBase.
func AuthRoutes() []openAPIRoute {
	return []openAPIRoute{
		{
			Method: http.MethodPost, Path: "/api/collections/users/auth-with-password",
			OperationID: "loginUser", Tag: tagAuth,
			Summary: "Log in as a user",
			Description: "PocketBase's password login for the `users` collection. Works for app accounts only; " +
				"repeated attempts from one address are throttled.",
			Access: accessPublic,
			Body:   jsonBody(AuthPasswordRequest{}, "The account's email and password."),
			Responses: withErrors([]routeResponse{jsonResponse(200, "The token and the account's record.", AuthResponse{})},
				400, 429),
		},
		{
			Method: http.MethodPost, Path: "/api/collections/users/auth-refresh",
			OperationID: "refreshUserToken", Tag: tagAuth,
			Summary:     "Exchange a user token for a fresh one",
			Description: "Also how a client checks that a stored token is still valid: the answer names its account.",
			Access:      accessUser,
			Responses:   withErrors([]routeResponse{jsonResponse(200, "A new token and its account.", AuthResponse{})}, 401, 403),
		},
		{
			Method: http.MethodPost, Path: "/api/collections/workers/auth-with-password",
			OperationID: "loginWorker", Tag: tagAuth,
			Summary:     "Log in as the worker account",
			Description: "PocketBase's password login for the `workers` collection.",
			Access:      accessPublic,
			Body:        jsonBody(AuthPasswordRequest{}, "The worker account's email and password."),
			Responses: withErrors([]routeResponse{jsonResponse(200, "The token and the worker's record.", AuthResponse{})},
				400, 429),
		},
		{
			Method: http.MethodPost, Path: "/api/collections/workers/auth-refresh",
			OperationID: "refreshWorkerToken", Tag: tagAuth,
			Summary:     "Exchange a worker token for a fresh one",
			Description: "The worker's equivalent of the user token refresh.",
			Access:      accessWorker,
			Responses:   withErrors([]routeResponse{jsonResponse(200, "A new token and the worker's record.", AuthResponse{})}, 401, 403),
		},
	}
}
