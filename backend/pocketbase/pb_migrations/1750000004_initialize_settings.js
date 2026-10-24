/// <reference path="../pb_data/types.d.ts" />

migrate((app) => {
  const settings = app.settings();

  settings.meta.appName = "Taiga Sleep";
  settings.logs.maxDays = 7;
  settings.logs.logAuthId = true;

  return app.save(settings);
}, (app) => {
  // Keep the data. Settings are restored by re-running the previous release.
});
