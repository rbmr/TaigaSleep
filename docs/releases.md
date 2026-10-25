# Releases

Taiga Sleep has no version numbers yet. Every push to `main` replaces one rolling
GitHub release and republishes the backend images from the same commit. Semantic
versioning, per-release tags, changelogs and a version-bump check are tracked in
[#13](https://github.com/rbmr/TaigaSleep/issues/13).

## What a push to `main` publishes

[`.github/workflows/release.yml`](../.github/workflows/release.yml) runs the app
unit tests, builds the signed release APK and the two backend images, then
replaces the `latest` release.

| Artifact | Where |
| --- | --- |
| Release APK | `https://github.com/rbmr/TaigaSleep/releases/download/latest/taiga-sleep.apk` |
| Checksum | `https://github.com/rbmr/TaigaSleep/releases/download/latest/taiga-sleep.apk.sha256` |
| API image | `ghcr.io/rbmr/taigasleep-api:latest` |
| Worker image | `ghcr.io/rbmr/taigasleep-worker:latest` |

The release body names the commit it was built from. Older builds are not kept:
the `latest` tag and its assets are deleted and recreated, so download links stay
stable and always serve the newest build.

The workflow is serialised: a newer push cancels an older run, so the release
always matches the tip of `main`.

The first workflow run creates both container packages, which GitHub makes
**private** by default. Open each package's settings once and change its
visibility to public so `docker compose pull` works without logging in:

- <https://github.com/users/rbmr/packages/container/taigasleep-api/settings>
- <https://github.com/users/rbmr/packages/container/taigasleep-worker/settings>

## App packages

| Build | Package | Version |
| --- | --- | --- |
| Release | `com.rbmr.taigasleep` | `0.1.0` |
| Debug | `com.rbmr.taigasleep.debug` | `0.1.0-debug` |

The different package names let both install on one phone and keep separate
recordings and settings, so a test build can talk to a development backend
without touching the real install.

Debug and release APKs are also signed with different keys. Because the package
names differ, that normally does not matter. A device that still has an old debug
install of `com.rbmr.taigasleep` (from before the split) must uninstall it once
before the release APK can take over that package name; uninstalling deletes the
app's data, so export any recordings first.

## Install and verify

```bash
sha256sum -c taiga-sleep.apk.sha256
adb install -r taiga-sleep.apk
```

On the phone, opening the APK requires allowing installs from the app that
downloaded it. There is no store listing.

Android accepts a new APK with the same `versionCode` as the installed one and
keeps the app data, so the rolling release can be reinstalled repeatedly. A
`versionCode` that goes backwards is refused; that is one of the reasons the
release workflow never invents version codes yet.

## Signing key

The release key is not in the repository. CI reads it from repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The PKCS#12 keystore, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias, `taiga` |
| `ANDROID_KEY_PASSWORD` | Key password (same as the keystore password for PKCS#12) |

The workflow decodes the keystore into `$RUNNER_TEMP` and passes the values to
Gradle through environment variables. Nothing is written to the workspace, so the
key cannot end up in a build artifact or a cache.

**Back the key up.** A lost key means every existing install has to be uninstalled
before it can be updated, which deletes that user's local recordings. Keep the
`.jks` file and its password together, offline or in a password manager.

### Creating a replacement key

Only needed if the key is lost or deliberately rotated; rotating it has the same
consequence as losing it. Do this in a private directory:

```bash
keytool -genkeypair -noprompt \
  -keystore taiga-release.jks -storetype PKCS12 \
  -alias taiga -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<password>' -keypass '<password>' \
  -dname 'CN=Taiga Sleep, O=rbmr, C=DE'

base64 -w0 taiga-release.jks > taiga-release.b64
gh secret set ANDROID_KEYSTORE_BASE64 < taiga-release.b64
printf '%s' '<password>' | gh secret set ANDROID_KEYSTORE_PASSWORD
printf '%s' taiga | gh secret set ANDROID_KEY_ALIAS
printf '%s' '<password>' | gh secret set ANDROID_KEY_PASSWORD
rm taiga-release.b64
```

Check the keystore before uploading with
`keytool -list -keystore taiga-release.jks`.

### Signing locally

`./gradlew :app:assembleRelease` signs only when it can find a key. Either create
a gitignored `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/taiga-release.jks
storePassword=<password>
keyAlias=taiga
keyPassword=<password>
```

or set `TAIGA_KEYSTORE_FILE`, `TAIGA_KEYSTORE_PASSWORD`, `TAIGA_KEY_ALIAS` and
`TAIGA_KEY_PASSWORD`; the environment wins when both are present. Without a key
the build produces an unsigned APK, which is fine for `testDebugUnitTest` and
`assembleDebug` but is not installable.

## Testing the workflow without publishing

Run the `release` workflow manually on a branch (`workflow_dispatch`). It runs the
tests, builds and verifies the signed APK, and builds both images, but does not
push the images or touch the `latest` release. That is the way to check a signing
or Docker change before merging it.

## Not done yet

- No semantic version numbers, tags, changelogs or version-bump checks (#13).
- Minification and R8 are off, so the release APK is unminified until it has been
  tested against a real sensor.
- No reproducible backend digest is recorded in the release body; `latest` is
  rebuilt in place.
