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

### backend-v0.6.8

- What changed: a worker can read only the analysis it holds the lease of, with that
  analysis's recording and files; `GET /v1/analyses` now returns only those for a worker.
  A new `TAIGA_USER_QUOTA_GB` caps the bytes one account's recordings may hold, 10 GB by
  default; an upload beyond it is refused with `413 storage_quota_exceeded`, which the
  app shows as "Server storage full".
- After upgrading: nothing, unless the quota should differ. Set `TAIGA_USER_QUOTA_GB` in
  the API's environment before starting it; `0` removes the limit. The other limits keep
  their defaults.
- Without it: the default applies, so an account cannot upload past 10 GB of stored
  recordings. A worker that reads an analysis outside its lease now gets `404`, so a
  worker and an API from different versions are not a supported pair.

### backend-v0.6.7

- What changed: the default `taigasleep-worker` image now contains the wav2sleep ECG
  stager, CPU-only PyTorch and the pinned checkpoint, so it is about 1.63 GB instead of
  243 MB. Compose raises the job limit from 768 to 2048 MB and the container limit from
  1 GB to 2 GB. A wav2sleep analysis peaks near 0.75 GB of RSS; the job needs about 2 GB
  of address space and the host about 4 GB of RAM and 3 GB of free disk.
- After upgrading: pull or rebuild the worker image and check the host has the memory and
  disk above. On a smaller host, build with `WITH_WAV2SLEEP=false` (or set it in
  `deploy/.env`) to keep the baseline-only image and the old limits, as described under
  [Worker image](../backend/README.md#worker-image).
- Without it: the old image keeps working but offers only baseline. If the new default
  image is pulled on a host below the requirement, the container or the analysis is
  stopped by the memory limit. Existing analyses are unchanged; a new analysis uses
  wav2sleep because the worker now reports it and the app prefers it.

### backend-v0.6.6

- What changed: a result now uses analysis contract version 6, which no longer carries a
  `total_duration_s` item. Recording length is a property of the recording, so the app reads it
  from the recording's own metadata. The baseline algorithm keeps its version, so an existing
  analysis is not recomputed by itself.
- After upgrading: delete the analyses of every recording analysed earlier and request them again,
  as described under
  [stale analysis results](../backend/README.md#stale-analysis-results-after-an-upgrade).
- Without it: those nights keep a stored result with the retired item. The app ignores it, so the
  night screen still shows the times and the length from the recording itself.

### backend-v0.6.5

- What changed: the data-quality score no longer uses stage confidence, and the sleep score is
  withheld when the data quality is below 50 of 100, with the reason `not_enough_good_data`. A
  result now uses analysis contract version 5. The baseline algorithm stays at `1.2.0`, so an
  existing analysis is not recomputed by itself.
- After upgrading: delete the analyses of every recording analysed earlier and request them
  again, as described under
  [stale analysis results](../backend/README.md#stale-analysis-results-after-an-upgrade).
- Without it: those nights keep a data-quality score that still includes stage confidence and a
  sleep score computed without the quality gate, so they do not match a new analysis of the same
  recording.

### backend-v0.6.4

- What changed: the baseline stager is now version `1.2.0` and reports wake and sleep only. It no
  longer claims deep, light, REM or NREM sleep and declares them unsupported, and it no longer
  carries per-epoch confidence, so the app shows no such stage figures or stage confidence for a
  baseline result. The deep and REM rules and their parameters are gone; an analysis that passes
  `deep_hr_ratio`, `rem_hr_std_bpm` or `rem_movement_g` now fails with `invalid_params`.
- After upgrading: a new analysis request uses `1.2.0` and gets the wake/sleep result. Nights
  analysed with baseline `1.1.0` before the upgrade keep their old payload until you delete their
  analyses and request them again, as described under
  [stale analysis results](../backend/README.md#stale-analysis-results-after-an-upgrade).
- Without it: those nights still show deep, light and REM figures and fixed per-epoch confidences
  that the current algorithm does not produce.

### backend-v0.6.3

- What changed: several stored figures now mean something else without a change to their ids or
  the algorithm version. Sleep efficiency is total sleep divided by scored time and is absent when
  less than half of a recording could be scored, sleep latency starts at the first scored epoch,
  HRV uses sleep epochs only and never takes a difference across a gap in the RR data, and movement
  is measured from the night's median accelerometer magnitude instead of a fixed 1 g.
- After upgrading: delete the analyses of every recording analysed earlier and request them again,
  as described under
  [stale analysis results](../backend/README.md#stale-analysis-results-after-an-upgrade).
- Without it: those nights keep the figures computed with the earlier definitions, so they do not
  match a new analysis of the same recording.

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
