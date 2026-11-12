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
	// accessTokenDuration is how long a token stays valid. A token is meant to be used
	// until the user revokes it, so this is long enough not to be a surprise.
	accessTokenDuration = 10 * 365 * 24 * time.Hour
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

// tokenHint is the last characters of a token, which is enough to tell two apart in a
// list without storing the secret.
func tokenHint(token string) string {
	if len(token) <= 4 {
		return token
	}
	return token[len(token)-4:]
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
	collection, err := e.App.FindCollectionByNameOrId("access_tokens")
	if err != nil {
		return err
	}
	record := core.NewRecord(collection)
	record.Set("owner", e.Auth.Id)
	record.Set("name", name)
	// PocketBase requires an auth record to have a password. Nobody logs in with this
	// collection, so a random one keeps the field filled without being a credential.
	record.SetRandomPassword()
	// The record has to exist before it can carry a token: the token is signed with its
	// id and token key, both of which are made when it is saved.
	if err := e.App.Save(record); err != nil {
		return err
	}
	token, err := record.NewStaticAuthToken(accessTokenDuration)
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
