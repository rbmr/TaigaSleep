package api

import (
	"github.com/pocketbase/pocketbase/core"
)

// Roles an endpoint can require. A request's role comes from the collection its
// login token belongs to: users are the people who own recordings, workers analyse
// them. A personal access token is a third role: it reads its owner's data and may
// not write.
const (
	roleUser   = "user"
	roleWorker = "worker"
	roleToken  = "token"
)

// access says who may call a route. The empty value is public.
type access string

const (
	accessPublic access = ""
	accessUser   access = "user"
	accessWorker access = "worker"
	// accessAny is a user or a worker: a personal access token is refused.
	accessAny access = "any"
	// accessRead is a user or a personal access token of that user.
	accessRead access = "read"
	// accessReadAny adds a worker with a lease, which is what reading one recording or
	// analysis takes.
	accessReadAny access = "read-any"
)

func roleOf(e *core.RequestEvent) string {
	if e.Auth != nil {
		switch e.Auth.Collection().Name {
		case "users":
			return roleUser
		case "workers":
			return roleWorker
		}
		return ""
	}
	if owner, ok := e.Get(contextTokenOwner).(string); ok && owner != "" {
		return roleToken
	}
	return ""
}

// callerID is the account whose data the request works on: the account of the login
// token, or the owner of the personal access token.
func callerID(e *core.RequestEvent) string {
	if e.Auth != nil {
		return e.Auth.Id
	}
	if owner, ok := e.Get(contextTokenOwner).(string); ok {
		return owner
	}
	return ""
}

// authorize enforces the route's access. A token that is present but belongs to
// neither collection (a PocketBase superuser's, for instance) is refused too.
func authorize(e *core.RequestEvent, required access) error {
	if required == accessPublic {
		return nil
	}
	role := roleOf(e)
	if e.Auth == nil && role == "" {
		// No token at all, or an access token the server does not accept (revoked).
		return unauthorized("a valid token is required")
	}
	if role == "" {
		return forbidden("this token cannot use the API")
	}
	switch {
	case required == accessUser && role != roleUser:
		return forbidden("a user account is required")
	case required == accessWorker && role != roleWorker:
		return forbidden("a worker account is required")
	case required == accessAny && role == roleToken:
		return forbidden("an access token cannot use this endpoint")
	case required == accessRead && role != roleUser && role != roleToken:
		return forbidden("a user account is required")
	case required == accessReadAny && role != roleUser && role != roleWorker && role != roleToken:
		return forbidden("a user account is required")
	}
	return nil
}
