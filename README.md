<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/taiga-sleep-lockup-horizontal-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="assets/taiga-sleep-lockup-horizontal-black.svg">
    <img src="assets/taiga-sleep-lockup-horizontal-black.svg" alt="Taiga Sleep" width="420">
  </picture>
</p>

# Taiga Sleep

An open-source Android sleep tracker for Polar heart-rate sensors.

Taiga Sleep records overnight data locally on the phone and can optionally
upload it to a self-hosted analysis service for sleep staging and history.
The first supported sensor is the Polar H10 (HR, ECG, accelerometer).

**Status:** early development. The Android app shell is being built.

## Planned architecture

- **Android app:** Kotlin, Jetpack Compose, local-first recordings.
- **Optional backend:** PocketBase (auth, SQLite, files) and a Python analysis worker.
- **Analysis:** open-source ECG sleep staging (wav2sleep / SleepECG).

The app works offline. The backend is only needed for uploading recordings and
viewing processed results.

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

The current app shell contains the three-tab navigation, theme, and placeholder
screens. Recording, history sync, and the backend integration are next.

## Contributing

- Changes land through pull requests. Do not push directly to `main` after the
  initial commit.
- Use [Conventional Commits](https://www.conventionalcommits.org/).
- Never commit recordings, tokens, or other health data.

## License

GPL-3.0. See [LICENSE](LICENSE).

Taiga Sleep is not a medical device.
