package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// An access token lets a user's own assistant or script read that user's recordings,
// analyses and results. It cannot write. Only the hash of the token is stored, so the
// token itself is shown once, when it is created.
func init() {
	m.Register(upAccessTokens, downAccessTokens)
}

func upAccessTokens(app core.App) error {
	users, err := app.FindCollectionByNameOrId("_pb_users_auth_")
	if err != nil {
		return err
	}
	tokens := core.NewBaseCollection("access_tokens", "pbc_2984622541")
	tokens.Fields.Add(
		&core.RelationField{
			Name:          "owner",
			Required:      true,
			CollectionId:  users.Id,
			CascadeDelete: true,
			MinSelect:     1,
			MaxSelect:     1,
		},
		// A name the user chose, so tokens can be told apart.
		&core.TextField{Name: "name", Required: true, Max: 60},
		// The SHA-256 hash of the token, hex. The token is never stored.
		&core.TextField{Name: "hash", Required: true, Max: 64},
		// The last characters of the token, to recognise it in the list.
		&core.TextField{Name: "hint", Max: 12},
		&core.AutodateField{Name: "created", OnCreate: true},
	)
	tokens.AddIndex("idx_access_tokens_hash", true, "hash", "")
	tokens.AddIndex("idx_access_tokens_owner", false, "owner", "")
	return app.Save(tokens)
}

func downAccessTokens(app core.App) error {
	collection, err := app.FindCollectionByNameOrId("access_tokens")
	if err != nil {
		return nil
	}
	return app.Delete(collection)
}
