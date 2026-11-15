# Continuous integration

Every pull request into `main` runs the checks below, and the `gate` check is the
one required besides `version bump` and `release plan`. A green gate means the
tests and the static checks that apply to the change passed, so a merge cannot
slip past them.

## The gate

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) always runs. Its `changes`
job asks [`tools/ci_areas.py`](../tools/ci_areas.py) which areas the change
touches, and every other job is gated on one area. The `gate` job then needs all
of them and is green only when each is `success` or was skipped for its area.
Two rules make it trustworthy:

- The gate runs with `if: always()`, so a failed job leaves a red gate rather
  than a required check that never reports.
- `changes` has to succeed. Without it every job would skip, which would
  otherwise look exactly like a change that needs no checks.

`tools/ci_areas.py --self-test` holds the table of what belongs to which area, and
`python3 tools/ci_areas.py --base origin/main` prints what a branch would run,
including uncommitted work. A change to a workflow file runs every area, because
nothing else validates the checks themselves.

| Job | Applies to | Runs |
| --- | --- | --- |
| `App tests and Android lint` | the app and its Gradle inputs | `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` |
| `Kotlin static analysis` | the app, `tools/detekt/` | `tools/check_kotlin.sh` |
| `Go tests` | `backend/api/` | `go vet`, `go test` |
| `Go static analysis` | `backend/api/` | `staticcheck`, `govulncheck` |
| `Worker tests` | `backend/worker/` | `uv run --frozen pytest` |
| `Python lint` | `backend/worker/`, `tools/` | `ruff check`, `ruff format --check` |
| `Integration tests` | `backend/` | the API and worker integration suite |
| `Workflow lint` | every change | `actionlint`, including shellcheck on `run:` scripts |

## Static checks and their baselines

Each check passes on `main` either because the code already passes or because the
existing findings are recorded:

- **Kotlin.** `tools/check_kotlin.sh` runs detekt with
  [`tools/detekt/detekt.yml`](../tools/detekt/detekt.yml) and
  [`tools/detekt/baseline.xml`](../tools/detekt/baseline.xml). The app's style
  predates any tool, so the baseline records what exists today instead of
  reformatting the app. `MagicNumber` is off in the config, and Compose functions
  are exempt from the naming rule. A new finding fails the run; delete its line
  from the baseline only when you fix it.
- **Go.** `go vet`, `staticcheck` and `govulncheck` run with no baseline: the
  findings they had are fixed. govulncheck reads the same `go.mod` as the build,
  so a standard-library advisory is fixed by the Go patch release the runner
  installs, and a module advisory by a `go get`.
- **Python.** [`tools/ruff.toml`](../tools/ruff.toml) covers the worker and
  `tools/`. The worker's
  dev dependencies pin ruff, so `uv run --frozen --project backend/worker ruff`
  is the same version CI uses.
- **Android lint** keeps its own baseline in `app/lint-baseline.xml`.

## Dependencies and scanning

- [`.github/dependabot.yml`](../.github/dependabot.yml) updates Gradle, Go
  modules, the worker's Python project, the two Dockerfiles and the pinned
  actions. Patch and minor updates are grouped per ecosystem; a major one gets its
  own pull request. A dependency change is a change to that component, so its
  version still has to be bumped.
- Third-party actions are pinned by commit with the version in a trailing
  comment, and Dependabot keeps the pins current.
- [`.github/workflows/codeql.yml`](../.github/workflows/codeql.yml) scans Kotlin,
  Go and Python after every merge, on every pull request and once a week. Alerts
  are in the repository's **Security → Code scanning** view. Kotlin needs a
  build, so that job compiles the debug APK for the extractor.

## Running the checks locally

```bash
# app
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:lintDebug
./tools/check_kotlin.sh

# Go
cd backend/api && go vet ./... && go test -count=1 ./...
go install honnef.co/go/tools/cmd/staticcheck@2026.2.2
go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
export PATH="$(go env GOPATH)/bin:$PATH"
staticcheck ./... && govulncheck ./...

# worker and tools
cd backend/worker && uv sync --frozen && uv run --frozen pytest
cd ../.. && uv run --project backend/worker --frozen ruff check --config tools/ruff.toml \
  backend/worker tools
uv run --project backend/worker --frozen ruff format --check \
  --config tools/ruff.toml backend/worker tools

# workflows
actionlint

# what the gate would run for this branch
python3 tools/ci_areas.py --self-test
python3 tools/ci_areas.py --base origin/main
```

`tools/check_kotlin.sh` downloads detekt once into
`${XDG_CACHE_HOME:-$HOME/.cache}/taiga-sleep` and verifies its checksum. The
actionlint and shellcheck versions CI uses are the latest releases; install them
with your package manager, or take the release binary from
`github.com/rhysd/actionlint`.
