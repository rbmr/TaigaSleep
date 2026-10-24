/// <reference path="../pb_data/types.d.ts" />

// Service accounts for the analysis worker. These accounts cannot be created
// through the public API (createRule is null) and cannot read or write records
// directly. They only authenticate to the /api/taiga/worker/* routes.
migrate((app) => {
  const collection = new Collection({
    id: "pbc_1639792033",
    name: "workers",
    type: "auth",
    system: false,
    listRule: null,
    viewRule: null,
    createRule: null,
    updateRule: null,
    deleteRule: null,
    authRule: "",
    manageRule: null,
    authAlert: {
      enabled: false,
    },
    passwordAuth: {
      enabled: true,
      identityFields: ["email"],
    },
    otp: {
      enabled: false,
    },
    mfa: {
      enabled: false,
    },
    authToken: {
      duration: 2592000,
    },
    fields: [
      {
        name: "name",
        type: "text",
        max: 100,
      },
    ],
  });

  return app.save(collection);
}, (app) => {
  return app.delete(app.findCollectionByNameOrId("workers"));
});
