package api

import (
	"github.com/pocketbase/pocketbase/core"
)

// Roles an endpoint can require. A request's role comes from the collection its
// token belongs to: users are the people who own recordings, workers analyse them.
const (
	roleUser   = "user"
	roleWorker = "worker"
)

// access says who may call a route. The empty value is public.
type access string

const (
	accessPublic access = ""
	accessUser   access = "user"
	accessWorker access = "worker"
	accessAny    access = "any"
)

func roleOf(e *core.RequestEvent) string {
	if e.Auth == nil {
		return ""
	}
	switch e.Auth.Collection().Name {
	case "users":
		return roleUser
	case "workers":
		return roleWorker
	}
	return ""
}

// authorize enforces the route's access. A token that is present but belongs to
// neither collection (a PocketBase superuser's, for instance) is refused too.
func authorize(e *core.RequestEvent, required access) error {
	if required == accessPublic {
		return nil
	}
	if e.Auth == nil {
		return unauthorized("a valid token is required")
	}
	role := roleOf(e)
	switch {
	case role == "":
		return forbidden("this token cannot use the API")
	case required == accessUser && role != roleUser:
		return forbidden("a user account is required")
	case required == accessWorker && role != roleWorker:
		return forbidden("a worker account is required")
	}
	return nil
}
