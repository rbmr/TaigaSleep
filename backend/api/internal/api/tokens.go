package api

import (
	"net/http"
	"strings"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

// An access token is a read-only credential a user makes for their own assistant or
// script. It is a PocketBase static auth token of the access_tokens collection: the
// record is its identity, so deleting the record is what revokes it.

const (
	// noExpiryDuration is how long a token made without a lifetime stays valid. Without
	// a lifetime a token is meant to be used until the user revokes it, so this is long
	// enough not to be a surprise.
	noExpiryDuration = 10 * 365 * 24 * time.Hour
)

// tokenLifetime is one lifetime a creation may ask for: the value the request names,
// and how long the token then works. A zero duration is no expiry.
type tokenLifetime struct {
	value    string
	duration time.Duration
}

// tokenLifetimes are the lifetimes a token may be made with. Omitting the lifetime is
// the same as asking for no expiry, which is what a client from before the choice got.
var tokenLifetimes = []tokenLifetime{
	{"7d", 7 * 24 * time.Hour},
	{"30d", 30 * 24 * time.Hour},
	{"90d", 90 * 24 * time.Hour},
	{"1y", 365 * 24 * time.Hour},
	{"never", 0},
}

// resolveLifetime reads the lifetime a creation asked for. An empty value is the
// default and means no expiry; anything else has to be one of the offered lifetimes.
func resolveLifetime(requested string) (tokenLifetime, error) {
	if requested == "" {
		return tokenLifetime{value: "never"}, nil
	}
	for _, lifetime := range tokenLifetimes {
		if lifetime.value == requested {
			return lifetime, nil
		}
	}
	return tokenLifetime{}, badRequest("invalid_lifetime", "lifetime must be 7d, 30d, 90d, 1y or never")
}

// TokenView is one access token as the API shows it. The token itself is set only in
// the answer to its creation; afterwards the server cannot show it again. ExpiresAt is
// empty for a token that never expires, and Expired says the expiry has passed, which
// is what makes the list show it as no longer usable.
type TokenView struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Hint      string  `json:"hint"`
	Created   string  `json:"created"`
	ExpiresAt *string `json:"expires_at"`
	Expired   bool    `json:"expired"`
	Token     string  `json:"token,omitempty"`
}

type TokenList struct {
	Items []TokenView `json:"items"`
}

// TokenRequest is the body of a token's creation: the name the user gives it, and how
// long it should work. The lifetime is optional; leaving it out keeps the token valid
// until it is revoked.
type TokenRequest struct {
	Name     string `json:"name"`
	Lifetime string `json:"lifetime,omitempty"`
}

// tokenHint is the last characters of a token, which is enough to tell two apart in a
// list without storing the secret.
func tokenHint(token string) string {
	if len(token) <= 4 {
		return token
	}
	return token[len(token)-4:]
}

// tokenExpired says whether a token's stored expiry has passed. A token without one
// never expires.
func tokenExpired(record *core.Record) bool {
	stored := record.GetString("expires_at")
	if stored == "" {
		return false
	}
	expires, err := time.Parse(pbDateLayout, stored)
	if err != nil {
		// An expiry that cannot be read is not trusted to keep the token alive.
		return true
	}
	return !time.Now().Before(expires)
}

// dropExpiredAccessToken removes the identity of an access token whose expiry has
// passed before any route sees it. The request is then answered as one with a token
// that does not exist: the custom routes refuse it with 401 and the record API sees a
// caller without a token.
func dropExpiredAccessToken(e *core.RequestEvent) error {
	if e.Auth != nil && e.Auth.Collection().Name == "access_tokens" && tokenExpired(e.Auth) {
		e.Auth = nil
	}
	return e.Next()
}

func serializeToken(record *core.Record) TokenView {
	return TokenView{
		ID:        record.Id,
		Name:      record.GetString("name"),
		Hint:      record.GetString("hint"),
		Created:   derefString(rfc3339(record.GetString("created"))),
		ExpiresAt: rfc3339(record.GetString("expires_at")),
		Expired:   tokenExpired(record),
	}
}

func CreateToken(e *core.RequestEvent) error {
	var body TokenRequest
	if err := decodeBody(e, bodyLimitSmall, &body, `the body must be {"name": "...", "lifetime": "..."}`, false); err != nil {
		return err
	}
	name := strings.TrimSpace(body.Name)
	if name == "" || len(name) > 60 {
		return badRequest("invalid_body", "name must be 1 to 60 characters")
	}
	lifetime, err := resolveLifetime(strings.TrimSpace(body.Lifetime))
	if err != nil {
		return err
	}
	collection, err := e.App.FindCollectionByNameOrId("access_tokens")
	if err != nil {
		return err
	}
	record := core.NewRecord(collection)
	record.Set("owner", e.Auth.Id)
	record.Set("name", name)
	if lifetime.duration > 0 {
		record.Set("expires_at", pbDateMillis(time.Now().Add(lifetime.duration).UnixMilli()))
	}
	// PocketBase requires an auth record to have a password. Nobody logs in with this
	// collection, so a random one keeps the field filled without being a credential.
	record.SetRandomPassword()
	// The record has to exist before it can carry a token: the token is signed with its
	// id and token key, both of which are made when it is saved.
	if err := e.App.Save(record); err != nil {
		return err
	}
	duration := lifetime.duration
	if duration == 0 {
		duration = noExpiryDuration
	}
	token, err := record.NewStaticAuthToken(duration)
	if err != nil {
		return err
	}
	record.Set("hint", tokenHint(token))
	if err := e.App.Save(record); err != nil {
		return err
	}
	view := serializeToken(record)
	view.Token = token
	e.Response.Header().Set("Location", "/v1/tokens/"+record.Id)
	return e.JSON(http.StatusCreated, view)
}

func ListTokens(e *core.RequestEvent) error {
	records, err := e.App.FindAllRecords("access_tokens", dbx.HashExp{"owner": e.Auth.Id})
	if err != nil {
		return err
	}
	items := make([]TokenView, 0, len(records))
	for _, record := range records {
		items = append(items, serializeToken(record))
	}
	return e.JSON(http.StatusOK, TokenList{Items: items})
}

func DeleteToken(e *core.RequestEvent) error {
	record, err := e.App.FindRecordById("access_tokens", e.Request.PathValue("id"))
	if err != nil || record.GetString("owner") != e.Auth.Id {
		return notFound("token")
	}
	// Deleting the record is what invalidates the token: PocketBase looks the record up
	// on every request.
	if err := e.App.Delete(record); err != nil {
		return err
	}
	return e.NoContent(http.StatusNoContent)
}
