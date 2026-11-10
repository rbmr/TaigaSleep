package api

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

// An access token is a read-only credential a user makes for their own assistant or
// script. It is a random secret with a fixed prefix; the backend stores only its
// SHA-256 hash, so it can be shown once and never read back.

const (
	// tokenPrefix makes an access token recognisable and keeps it apart from a login token.
	tokenPrefix = "ts_"
	// tokenMaxLength bounds what is hashed; a real token is 46 characters.
	tokenMaxLength = 128
	// contextTokenOwner holds the account an access token belongs to on the request.
	contextTokenOwner = "taiga_access_token_owner"
)

// TokenView is one access token as the API shows it. The token itself is set only in
// the answer to its creation; afterwards the server cannot show it again.
type TokenView struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Hint    string `json:"hint"`
	Created string `json:"created"`
	Token   string `json:"token,omitempty"`
}

type TokenList struct {
	Items []TokenView `json:"items"`
}

// TokenRequest is the body of a token's creation: the name the user gives it.
type TokenRequest struct {
	Name string `json:"name"`
}

func tokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// newAccessToken returns a fresh secret. The prefix says what it is; the rest is
// random, so it cannot be guessed.
func newAccessToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return tokenPrefix + base64.RawURLEncoding.EncodeToString(raw), nil
}

// presentedToken is the bearer token of the request, without the scheme prefix.
func presentedToken(e *core.RequestEvent) string {
	value := strings.TrimSpace(e.Request.Header.Get("Authorization"))
	if len(value) > 7 && strings.EqualFold(value[:7], "Bearer ") {
		value = strings.TrimSpace(value[7:])
	}
	return value
}

// accessTokenOwner returns the owner of the access token the request was made with,
// or "" when it carries none. The hash lookup is by index; comparing the hash is what
// authenticates the token.
func accessTokenOwner(e *core.RequestEvent) string {
	presented := presentedToken(e)
	if presented == "" || len(presented) > tokenMaxLength {
		return ""
	}
	record, err := e.App.FindFirstRecordByFilter("access_tokens", "hash = {:hash}",
		dbx.Params{"hash": tokenHash(presented)})
	if err != nil {
		return ""
	}
	return record.GetString("owner")
}

// loadAccessToken turns an access token into the account it belongs to. It runs after
// PocketBase's own token loading, so a login token is never read as an access token.
func loadAccessToken(e *core.RequestEvent) error {
	if e.Auth == nil {
		if owner := accessTokenOwner(e); owner != "" {
			e.Set(contextTokenOwner, owner)
		}
	}
	return e.Next()
}

func serializeToken(record *core.Record) TokenView {
	return TokenView{
		ID:      record.Id,
		Name:    record.GetString("name"),
		Hint:    record.GetString("hint"),
		Created: derefString(rfc3339(record.GetString("created"))),
	}
}

func CreateToken(e *core.RequestEvent) error {
	var body TokenRequest
	if err := decodeBody(e, bodyLimitSmall, &body, `the body must be {"name": "..."}`, false); err != nil {
		return err
	}
	name := strings.TrimSpace(body.Name)
	if name == "" || len(name) > 60 {
		return badRequest("invalid_body", "name must be 1 to 60 characters")
	}
	token, err := newAccessToken()
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
	record.Set("hash", tokenHash(token))
	record.Set("hint", token[len(token)-4:])
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
	if err := e.App.Delete(record); err != nil {
		return err
	}
	return e.NoContent(http.StatusNoContent)
}
