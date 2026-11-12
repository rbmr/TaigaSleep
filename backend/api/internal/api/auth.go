package api

import (
	"net/http"

	"github.com/pocketbase/pocketbase/core"
)

type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type AccountView struct {
	ID    string `json:"id"`
	Email string `json:"email"`
}

type TokenResponse struct {
	Token string `json:"token"`
	// Role is "user" for an app account and "worker" for an analysis worker.
	Role string      `json:"role"`
	User AccountView `json:"user"`
}

type MeResponse struct {
	ID    string `json:"id"`
	Email string `json:"email"`
	Role  string `json:"role"`
}

func tokenFor(record *core.Record) (TokenResponse, error) {
	token, err := record.NewAuthToken()
	if err != nil {
		return TokenResponse{}, err
	}
	role := roleUser
	if record.Collection().Name == "workers" {
		role = roleWorker
	}
	return TokenResponse{Token: token, Role: role, User: AccountView{ID: record.Id, Email: record.Email()}}, nil
}

func Login(e *core.RequestEvent) error {
	var body LoginRequest
	const expected = "email and password are required"
	if err := decodeBody(e, bodyLimitSmall, &body, expected, false); err != nil {
		return err
	}
	if body.Email == "" || body.Password == "" {
		return badRequest("invalid_body", expected)
	}
	var account *core.Record
	for _, collection := range []string{"users", "workers"} {
		record, err := e.App.FindAuthRecordByEmail(collection, body.Email)
		if err == nil && record.ValidatePassword(body.Password) {
			account = record
			break
		}
	}
	if account == nil {
		return unauthorized("the email or password is wrong")
	}
	response, err := tokenFor(account)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, response)
}

func Refresh(e *core.RequestEvent) error {
	response, err := tokenFor(e.Auth)
	if err != nil {
		return err
	}
	return e.JSON(http.StatusOK, response)
}

func Me(e *core.RequestEvent) error {
	return e.JSON(http.StatusOK, MeResponse{ID: e.Auth.Id, Email: e.Auth.Email(), Role: roleOf(e)})
}
