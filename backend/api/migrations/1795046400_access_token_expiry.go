package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// An access token may be made with a lifetime, so its record carries the moment it
// stops working. An empty value is a token that never expires, which is what every
// token made before this field existed is.
func init() {
	m.Register(upAccessTokenExpiry, downAccessTokenExpiry)
}

func upAccessTokenExpiry(app core.App) error {
	tokens, err := app.FindCollectionByNameOrId("access_tokens")
	if err != nil {
		return err
	}
	tokens.Fields.Add(&core.DateField{Name: "expires_at"})
	return app.Save(tokens)
}

func downAccessTokenExpiry(app core.App) error {
	tokens, err := app.FindCollectionByNameOrId("access_tokens")
	if err != nil {
		return err
	}
	tokens.Fields.RemoveByName("expires_at")
	return app.Save(tokens)
}
