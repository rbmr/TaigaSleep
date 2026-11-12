package api

import (
	"github.com/pocketbase/pocketbase/core"
)

// Roles an endpoint can require. A request's role comes from the auth collection its
// token belongs to: users are the people who own recordings, workers analyse them. An
// access token is a third role: it reads its owner's data and may not write.
const (
	roleUser   = "user"
	roleWorker = "worker"
	roleToken  = "token"
)

// access says who may call a route. The empty value is public. PocketBase's auth
// middleware enforces it with the collections the route lists.
type access string

const (
	accessPublic access = ""
	accessUser   access = "user"
	accessWorker access = "worker"
	// accessAny is a user or a worker: an access token is refused.
	accessAny access = "any"
	// accessRead is a user or an access token of that user.
	accessRead access = "read"
	// accessReadAny adds a worker with a lease, which is what reading one recording or
	// analysis takes.
	accessReadAny access = "read-any"
)

// collections is the allow-list of apis.RequireAuth: the auth collections whose tokens
// may call a route with this access. Empty means public.
func (a access) collections() []string {
	switch a {
	case accessUser:
		return []string{"users"}
	case accessWorker:
		return []string{"workers"}
	case accessAny:
		return []string{"users", "workers"}
	case accessRead:
		return []string{"users", "access_tokens"}
	case accessReadAny:
		return []string{"users", "access_tokens", "workers"}
	}
	return nil
}

func roleOf(e *core.RequestEvent) string {
	if e.Auth == nil {
		return ""
	}
	switch e.Auth.Collection().Name {
	case "users":
		return roleUser
	case "workers":
		return roleWorker
	case "access_tokens":
		return roleToken
	}
	return ""
}

// callerID is the account whose data the request works on: the account of the login
// token, or the owner of the access token.
func callerID(e *core.RequestEvent) string {
	if e.Auth == nil {
		return ""
	}
	if e.Auth.Collection().Name == "access_tokens" {
		return e.Auth.GetString("owner")
	}
	return e.Auth.Id
}
