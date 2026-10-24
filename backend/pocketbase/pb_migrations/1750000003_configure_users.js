/// <reference path="../pb_data/types.d.ts" />

// Single-user install: public registration is disabled (createRule null means
// superuser-only), while the account can still manage itself and log in.
migrate((app) => {
  const collection = app.findCollectionByNameOrId("_pb_users_auth_");

  unmarshal({
    createRule: null,
    authAlert: {
      enabled: false,
    },
    authToken: {
      duration: 2592000,
    },
  }, collection);

  return app.save(collection);
}, (app) => {
  const collection = app.findCollectionByNameOrId("_pb_users_auth_");

  unmarshal({
    createRule: "",
    authAlert: {
      enabled: true,
    },
    authToken: {
      duration: 432000,
    },
  }, collection);

  return app.save(collection);
});
