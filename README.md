<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/taiga-sleep-lockup-horizontal-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="assets/taiga-sleep-lockup-horizontal-light.svg">
    <img src="assets/taiga-sleep-lockup-horizontal-light.svg" alt="Taiga Sleep" width="420">
  </picture>
</p>

# Taiga Sleep

An open-source Android sleep tracker for Polar heart-rate sensors.

Taiga Sleep records overnight data locally on the phone and can optionally
upload it to a self-hosted analysis service for sleep staging and history.
The first supported sensor is the Polar H10 (HR, ECG, accelerometer).

**Status:** early development. Recording, uploads, History and the result
screens are in place; the analysis algorithms are still experimental.

## Architecture

- **Android app:** Kotlin, Jetpack Compose, local-first recordings.
- **Optional backend:** Go PocketBase API (auth, SQLite, files) and a Python
  analysis worker.
- **Analysis:** a built-in reference stager, plus the optional wav2sleep ECG
  model from #23. SleepECG is a follow-up (#22).

The app works offline. The backend is only needed for uploading recordings and
viewing processed results. A night can be recorded, exported and kept entirely
on the phone.

## Uploads and results

History lists every local recording and, when connected, remote recordings whose
local copy is gone. A night can be uploaded manually at any time, or
automatically when **Upload automatically** is enabled. The default is enabled,
**Wi-Fi only**, without requiring charging; charging can be required as well.
Uploads run in WorkManager, resume from the server's byte offset after a network
drop or app restart, and keep their progress on the individual History rows.

Processed nights open a detail screen with a sleep stages diagram, stage breakdown,
metrics and quality breakdown. A metric the sensor could not measure stays visible but
blank with a "Not supported by this device" note. Results are downloaded and
kept on the phone as soon as they are available. Local raw recordings are kept
after upload for now; holding a History row or using the night-detail menu can export or delete a recording everywhere. See [`docs/app-uploads.md`](docs/app-uploads.md).

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

The current app contains the three-tab navigation, local recording, History,
the optional remote login, upload state and the processed-night detail screen.

## Backend

The optional self-hosted backend lives in [`backend/`](backend/README.md). It is
a PocketBase API (SQLite, users, owner-only collections, durable job queue) plus
a small Python worker supervisor. See the backend README for setup,
configuration, deployment, backup and restore.

## Contributing

- Changes land through pull requests. Do not push directly to `main` after the
  initial commit.
- Use [Conventional Commits](https://www.conventionalcommits.org/).
- Never commit recordings, tokens, or other health data.

## License

GPL-3.0. See [LICENSE](LICENSE).

Taiga Sleep is not a medical device.
