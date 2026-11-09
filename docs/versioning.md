# Versioning

Taiga Sleep versions the app and the backend separately, and one more number ties
them together: the API contract.

```
app/version.properties        backend/version.properties
  version=0.2.0               version=0.1.0
  requires_api=1              api=1
```

| Field | Meaning |
| --- | --- |
| `app.version`, `backend.version` | The release version of that component, `MAJOR.MINOR.PATCH`. |
| `backend.api` | The contract revision the backend speaks. |
| `app.requires_api` | The contract revision this build of the app needs. |

Each `version` file is the only place its number is written. The app derives
`versionName` from it and builds `BuildConfig.VERSION_NAME`, `VERSION_CODE` and
`API_VERSION` from it; the backend reports its values from
`backend/api/internal/version/version.go`, and a unit test fails if the constants
and the file disagree.

## The release version

- Bump it in every pull request that changes that component. The required status
  check on `main` fails otherwise; see `tools/check_version_bump.py`.
- The comparison is semver and strict: the new version must be greater than the
  one on `main`. Nothing enforces which of major, minor or patch you pick.
- Every part stays below 1000. The app derives its `versionCode` as
  `major * 1_000_000 + minor * 1_000 + patch`, and Gradle refuses to build when a
  part is out of range, so two versions can never produce the same code.
- A version number is never reused. The release workflow refuses a tag that
  already exists on another commit.

`app/version.properties` and `backend/version.properties` are read by Gradle, the
Go API, the release workflow and the PR check, so a version bump is a one-file
change with no follow-up edits.

## The API contract

`api` counts contract revisions, not releases. Bump it when a change makes an
already released app fail against the backend: a renamed or removed field, a
changed shape, a new required parameter. Additive changes that old apps ignore do
not need a bump.

The app compares the server's `api` from the public `GET /v1/version` response
with the `requires_api` value it was built with, on every connect, login, resume
and upload. Equal values are the only compatible pair, so a mismatch has a clear
message: update the server when the server is older, update the app when the app
is older. The values are also logged in the `remote_check` diagnostics event.

`requires_api` and `api` must match in `main`, so the branch always ships an app
and a backend that agree. The PR check enforces that too.

## Beta policy

The project is in beta, and this is deliberate:

- The app and the backend both stay at `0.x.y` until they are ready for `1.0.0`.
- Breaking the app/backend contract is the default when it keeps the code clean.
  Do not contort the code, keep compatibility shims or preserve old fields to
  avoid a bump: bump `api` instead, and update the app in the same pull request.
- There are no migrations and no deprecation windows. The backend may change its
  schema or its routes without carrying old data or old clients forward. When a
  self-hoster needs to migrate, that is a manual step for now.
- `api` therefore moves often. That is expected, not a sign of instability.

`1.0.0` later is an ordinary bump: the check compares against `main`, not against
tags, and `versionCode` stays increasing across the boundary (`0.9.9` is 9009,
`1.0.0` is 1000000). There is nothing to reset or delete, and the `0.x` history
stays in the tag list, which is what a project that grew up in beta looks like.

## Releases

Merging to `main` publishes one release per component whose version changed:

| Tag | Artifacts | Marked latest |
| --- | --- | --- |
| `app-v0.2.0` | `taiga-sleep.apk`, `taiga-sleep.apk.sha256` | yes |
| `backend-v0.1.0` | no assets; the images are tagged from the same commit | no |

- Only the app release is marked latest, so
  `https://github.com/rbmr/TaigaSleep/releases/latest/download/taiga-sleep.apk`
  always serves the newest APK.
- Backend images are pushed as `:v0.1.0` and `:latest`. Pin the version tag for a
  reproducible deployment.
- What gets published is decided by `tools/release_plan.py` from the version
  files and the tags, not from the diff. A missing tag is published, a tag for
  the current version is skipped, and a version that is already released on
  another commit fails the merge instead of overwriting it. So a backend-only
  merge does not rebuild the APK, an app-only merge does not republish the
  images, and a version that was bumped but never released is published by the
  next merge.
- A rerun of the same commit skips both components, because their tags point at
  that commit.

Docs, CI and tooling changes need no version bump and publish nothing.

The app APK is built with R8 minification and resource shrinking. The keep rules
for what R8 cannot see on its own live in `app/proguard-rules.pro`.

### The plan runs before the merge

The same plan is the `release plan` check on every pull request, so the checks on
the pull request are the only place to look: green means the merge publishes
cleanly, red means it would fail on `main`. `version bump` and `release plan` are
both required checks on `main`.

That matters because the two rules are easy to confuse. Bumping only the app is
correct for an app-only change, and the backend keeps a version that was released
by an earlier merge. Reading that as "the backend tag already exists" is exactly
the mistake the check now catches before the merge instead of after it. Running
it locally works too:

```bash
python3 tools/release_plan.py --self-test   # the decision table
python3 tools/release_plan.py               # the plan for this branch
```

## What the version appears in

- Settings, About subtitle: `Taiga Sleep 0.2.0`.
- About screen: version name and code, from `BuildConfig`.
- Diagnostics `process_start`: version name, version code, build type and git
  commit.
- Diagnostics `remote_check`: the server's version and API revision.
- `/v1/version`: `version` and `api`.
- Each recording's `meta.json`, through the same build values.

## Out of scope for now

- Migrations, deprecation windows and a minimum supported app version served by
  the backend. Those matter once there are users you cannot ask to update.
- Conventional-commit driven bumps such as release-please. The explicit file bump
  is stricter and simpler to reason about.

## Doing a release by hand

```bash
# edit the version, then check the same rules the PR check runs
python3 tools/check_version_bump.py

# the debug build reports the same numbers
./gradlew :app:assembleDebug
```

`versionCode` comes from the file; nothing else needs to change.
