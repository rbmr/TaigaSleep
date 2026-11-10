# Releases

Every merge into `main` publishes one release per component whose version changed:
a signed APK for the app and versioned images for the backend. The version rules
live in [`docs/versioning.md`](versioning.md); this document is about the
artifacts and the signing key.

## What a push to `main` publishes

[`.github/workflows/release.yml`](../.github/workflows/release.yml) reads
`app/version.properties` and `backend/version.properties`, then runs the tracks
that have a new version.

| Artifact | Where |
| --- | --- |
| Newest release APK | `https://github.com/rbmr/TaigaSleep/releases/latest/download/taiga-sleep.apk` |
| Its checksum | `https://github.com/rbmr/TaigaSleep/releases/latest/download/taiga-sleep.apk.sha256` |
| A specific app build | `https://github.com/rbmr/TaigaSleep/releases/download/app-v0.2.0/taiga-sleep.apk` |
| API image | `ghcr.io/rbmr/taigasleep-api:v0.1.0` and `:latest` |
| Worker image | `ghcr.io/rbmr/taigasleep-worker:v0.1.0` and `:latest` |

- `app-v<version>` releases carry the APK and its SHA-256 and are marked latest,
  so `releases/latest/download/...` always serves the newest app.
- `backend-v<version>` releases have no assets. They mark the commit the images
  were built from and are deliberately not marked latest.
- A backend-only merge does not rebuild the APK; an app-only merge does not
  republish the images.
- The image `:latest` tag follows the newest backend build; pin `:v<version>` for a
  reproducible deployment.

Releases are serialised and never cancelled, so a run cannot leave a tag without
its assets. A version whose tag already exists on another commit fails the
workflow; a rerun on the same commit skips it.

The first workflow run creates both container packages, which GitHub makes
**private** by default. Open each package's settings once and change its
visibility to public so `docker compose pull` works without logging in:

- <https://github.com/users/rbmr/packages/container/taigasleep-api/settings>
- <https://github.com/users/rbmr/packages/container/taigasleep-worker/settings>

## App packages

| Build | Package | Version |
| --- | --- | --- |
| Release | `com.rbmr.taigasleep` | from `app/version.properties`, for example `0.2.0` |
| Debug | `com.rbmr.taigasleep.debug` | the same, with a `-debug` suffix |

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

Android keeps the app's data when the new `versionCode` is higher, and refuses a
`versionCode` that goes backwards. The code is derived from the version
(`major * 1000000 + minor * 1000 + patch`), so it only ever increases; a build
with the same version can be reinstalled over another one.

## Upgrade notes

[`backend/README.md`](../backend/README.md#upgrade) is the upgrade procedure.
Notes here say what that procedure cannot: what to do after upgrading a
particular release, if anything. They are listed newest first, one subsection
per release, titled with its tag. The operator reads the entry for the version
being installed before starting.

Template:

```markdown
### backend-v0.7.0

- What changed: <the result field, deployment step or other change>.
- After upgrading: <the command or app action, or "nothing">.
- Without it: <what the operator sees if the step is skipped>.
```

Rules:

- A change to the stored analysis result (a field, its type, its meaning or the
  set of statistics) must add a note. When the algorithm version is not bumped,
  existing analyses keep their old payload and are not recomputed, so the note
  must say they have to be deleted and requested again and must link to the
  stale-results steps in the backend upgrade section. A version bump avoids the
  stale payload, because a request then creates a new analysis, but existing
  nights still need a request; say that too.
- A release with no operator action needs no entry; no entry means nothing to do
  beyond the upgrade procedure.
- The note is written with the release, not after a user reports the problem.

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
tests, builds and verifies the APK, and builds both images, but does not push the
images or create a tag or release. That is the way to check a signing or Docker
change before merging it.

## The pull request check

`.github/workflows/version.yml` runs `tools/check_version_bump.py` on every pull
request into `main` and fails unless each changed component has a strictly greater
version. Make it a required status check in branch protection, together with the
app tests and the backend workflow, so a change cannot merge unreleased and
unbuilt.

## Not done yet

- No reproducible backend digest is recorded in the release body; `:latest` is
  rebuilt in place, so pin `:v<version>` when you need one.
