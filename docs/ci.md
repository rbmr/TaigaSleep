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
| `App unit tests` | the app and its Gradle inputs | `:app:testDebugUnitTest`, in four test JVMs |
| `App build and Android lint` | the app and its Gradle inputs | `:app:assembleDebug`, `:app:lintDebug` |
| `Kotlin static analysis` | the app, `tools/detekt/` | `tools/check_kotlin.sh` |
| `Go tests` | `backend/api/` | `go vet` and the tests of every package except `internal/api` |
| `Go tests (1/4)` … `Go tests (4/4)` | `backend/api/` | that shard of the `internal/api` tests |
| `Go static analysis` | `backend/api/` | `staticcheck`, `govulncheck` |
| `Worker tests` | `backend/worker/` | `uv run --frozen pytest` in four workers, then `tests/test_realtime.py` on its own |
| `Python lint` | `backend/worker/`, `tools/` | `ruff check`, `ruff format --check` |
| `Integration tests (1/4)` … `(4/4)` | `backend/` | that shard of the API and worker integration suite, with its own server |
| `Workflow lint` | every change | `actionlint`, including shellcheck on `run:` scripts |

## How the parallelism is configured

The runner has 4 vCPUs and about 16 GB of memory, and every count below is
chosen for that: four Gradle test JVMs, four Go shards and four pytest workers.
The jobs are independent of each other, so the wall clock is the slowest of
them rather than their sum.

- **Gradle.** `org.gradle.parallel` and `org.gradle.caching` are on in
  [`gradle.properties`](../gradle.properties). The app's unit tests run in
  several JVMs at once (`maxParallelForks` in
  [`app/build.gradle.kts`](../app/build.gradle.kts), at most four and fewer on a
  smaller machine). The JVM is the isolation boundary: every test class stays
  whole in one JVM and no class shares process state with another, so splitting
  the classes over JVMs changes nothing about what a test can see. The build
  cache is what lets a re-run, and the app jobs on later runs, reuse compilation
  and test outputs instead of repeating the work. A cached test result is only
  correct if every file a test reads is declared as an input of the test task,
  so a test that reads a file outside `app/src` needs that file listed there
  (today `docs/architecture.md`, `tools/read_diagnostics.py`,
  `tools/read_recording.py` and the worker's `frames.py` that it imports), and
  `tools/ci_areas.py` must run the app jobs when one of those files changes. The
  Gradle daemon keeps its 2 GB heap and a test JVM its default heap, so four of
  them together stay well inside the runner's 16 GB.
- **Go.** `go test` already runs packages side by side, but `internal/api` is one
  package and the largest part of the suite. Its tests cannot safely call
  `t.Parallel()`: many of them set package-level variables or environment
  variables, which would race with a test beside them in the same process. They
  can run in separate processes, so
  [`tools/go_test_shard.py`](../tools/go_test_shard.py) splits the package's test
  names over the matrix. The split is deterministic, and the `Go tests` job runs
  the script's `--self-test`, which fails when a test is in no shard or in two.
  The other packages are unchanged and run in one job together with `go vet`.
- **Python.** The worker's tests are independent, so
  `uv run --frozen pytest -n 4` runs them in four processes (pytest-xdist, in the
  worker's dev dependencies). `tests/test_realtime.py` drives real sockets and
  timers and is timing-sensitive, so it runs afterwards on its own instead of
  beside the rest. The integration suite shares one API server between the tests
  of a process, so it is sharded across jobs instead of run in parallel inside
  one: [`tools/pytest_shard.py`](../tools/pytest_shard.py) lists the collected
  tests and gives each job a contiguous slice of them in collection order, so
  every job starts its own server, data directory and worker processes, its
  tests run in the order the whole suite runs them, and the shards together are
  the whole suite.

Every job has a `timeout-minutes` (20 in `ci.yml`, 40 for the CodeQL analyses), so
a job that hangs fails after that long instead of running for GitHub's default of
six hours. A test that waits for a signal from the code under test carries its own
short guard for the same reason.

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
  `tools/`. The worker's dev dependencies pin ruff, so
  `uv run --frozen --project backend/worker ruff` is the same version CI uses.
- **Android lint** keeps its own baseline in `app/lint-baseline.xml`.

## Dependencies and scanning

- [`.github/dependabot.yml`](../.github/dependabot.yml) updates Gradle, Go
  modules, the worker's Python project, the two Dockerfiles and the pinned
  actions. Patch and minor updates are grouped per ecosystem; a major one gets its
  own pull request. A dependency change is a change to that component, so its
  version still has to be bumped: a Gradle, Go, Python or Docker update pull
  request stays red on `version bump` until that is added, which is what keeps a
  dependency change from being published without a release.
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
python3 ../../tools/go_test_shard.py --self-test
python3 ../../tools/go_test_shard.py --index 0 --shards 4
go install honnef.co/go/tools/cmd/staticcheck@2026.2.2
go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
export PATH="$(go env GOPATH)/bin:$PATH"
staticcheck ./... && govulncheck ./...

# worker and tools
cd backend/worker && uv sync --frozen
uv run --frozen pytest -n 4 --ignore tests/test_realtime.py
uv run --frozen pytest tests/test_realtime.py
cd ../.. && uv run --project backend/worker --frozen ruff check --config tools/ruff.toml \
  backend/worker tools
uv run --project backend/worker --frozen ruff format --check \
  --config tools/ruff.toml backend/worker tools

# one shard of the integration suite
cd backend
mapfile -t tests < <(uv run --project worker --frozen python \
  ../tools/pytest_shard.py --index 0 --shards 4 tests/test_integration.py)
uv run --project worker --frozen pytest -v "${tests[@]}"

# workflows
actionlint

# what the gate would run for this branch
python3 tools/ci_areas.py --self-test
python3 tools/ci_areas.py --base origin/main
```

`tools/check_kotlin.sh` downloads detekt once into
`${XDG_CACHE_HOME:-$HOME/.cache}/taiga-sleep` and verifies its checksum. CI
installs actionlint 1.7.12 from its release assets after checking the published
checksum, and uses the shellcheck the runner image ships, so install both
locally to see every finding the workflow job sees.
