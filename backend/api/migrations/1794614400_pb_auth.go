package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Auth is PocketBase's: users and workers log in through the auth routes of their
// collections, and an access token is an auth record whose static token PocketBase
// issues. The collection is rebuilt as an auth collection because the old one stored a
// hash of a secret this API checked itself; a static token cannot be derived from such
// a hash, so existing tokens stop working and have to be made again.
// accessTokensCollectionID is the access token collection's stable id.
const accessTokensCollectionID = "pbc_2984622541"

func init() {
	m.Register(upPocketBaseAuth, downPocketBaseAuth)
}

func upPocketBaseAuth(app core.App) error {
	for _, name := range []string{"_pb_users_auth_", "workers"} {
		collection, err := app.FindCollectionByNameOrId(name)
		if err != nil {
			return err
		}
		// Sign-up stays closed: the operator creates accounts in the dashboard.
		collection.PasswordAuth.Enabled = true
		collection.PasswordAuth.IdentityFields = []string{"email"}
		// The password is the whole login: an account is not asked for a second factor,
		// an emailed code or an OAuth2 provider.
		collection.MFA.Enabled = false
		collection.OTP.Enabled = false
		collection.OAuth2.Enabled = false
		collection.AuthAlert.Enabled = false
		if err := app.Save(collection); err != nil {
			return err
		}
	}

	if old, err := app.FindCollectionByNameOrId("access_tokens"); err == nil {
		if err := app.Delete(old); err != nil {
			return err
		}
	}

	users, err := app.FindCollectionByNameOrId("_pb_users_auth_")
	if err != nil {
		return err
	}
	tokens := core.NewAuthCollection("access_tokens", accessTokensCollectionID)
	tokens.ListRule, tokens.ViewRule = nil, nil
	tokens.CreateRule, tokens.UpdateRule, tokens.DeleteRule = nil, nil, nil
	tokens.ManageRule = nil
	// A token is not a password login: an access token is only ever its static token.
	tokens.PasswordAuth.Enabled = false
	tokens.AuthAlert.Enabled = false
	// PocketBase's auth routes are not for this collection; a static token is still
	// verified, which does not consult the auth rule.
	tokens.AuthRule = nil
	// An access token has no address of its own; its record is identified by the name
	// the user gave it.
	if field, ok := tokens.Fields.GetByName("email").(*core.EmailField); ok {
		field.Required = false
	}
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
		// The last characters of the token, to recognise it in the list.
		&core.TextField{Name: "hint", Max: 12},
		&core.AutodateField{Name: "created", OnCreate: true},
	)
	tokens.AddIndex("idx_access_tokens_owner", false, "owner", "")
	return app.Save(tokens)
}

func downPocketBaseAuth(app core.App) error {
	for _, name := range []string{"_pb_users_auth_", "workers"} {
		collection, err := app.FindCollectionByNameOrId(name)
		if err != nil {
			return err
		}
		collection.PasswordAuth.Enabled = false
		if err := app.Save(collection); err != nil {
			return err
		}
	}
	if collection, err := app.FindCollectionByNameOrId("access_tokens"); err == nil {
		return app.Delete(collection)
	}
	return nil
}
