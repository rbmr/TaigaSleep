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

Each logical area is one job. There is no matrix and no shard tooling: a test
suite that can split its work does so inside its own job, where the split costs
no extra checkout, runner start or dependency setup.

| Job | Applies to | Runs |
| --- | --- | --- |
| `App unit tests` | the app and its Gradle inputs | `:app:testDebugUnitTest`, in four test JVMs |
| `Android lint` | the app and its Gradle inputs | `:app:lintDebug` |
| `App build` | the app and its Gradle inputs | `:app:assembleDebug`: packaging, dex and manifest merging |
| `Kotlin static analysis` | the app, `tools/detekt/` | `tools/check_kotlin.sh` |
| `Go tests` | `backend/api/` | `go vet` and `go test ./...` |
| `Go static analysis` | `backend/api/` | `staticcheck`, `govulncheck` |
| `Worker tests` | `backend/worker/` | `uv run --frozen pytest` in four workers, then `tests/test_realtime.py` on its own |
| `Python lint` | `backend/worker/`, `tools/` | `ruff check`, `ruff format --check` |
| `Integration tests` | `backend/` | the API and worker integration suite in four pytest workers |
| `Workflow lint` | every change | `actionlint`, including shellcheck on `run:` scripts |

The jobs are independent of each other, so the wall clock is the slowest of
them rather than their sum. The three app jobs are declared first, immediately after
`changes`, because they are the longest and a runner should start them as early
as the queue allows. Unit tests and lint do not need the APK, so packaging is
its own job instead of time added to the other two.

## How the parallelism is configured

The runner has 4 vCPUs and about 16 GB of memory, and every count below is
chosen for that.

- **Kotlin and Android.** `org.gradle.parallel` and `org.gradle.caching` are on
  in [`gradle.properties`](../gradle.properties). The unit tests, Android lint
  and the debug APK are three jobs, because none of them needs another's
  output: lint and the tests do not need the APK, and packaging (dex and
  manifest merging) is the slow step that would otherwise sit on the critical
  path of the other two. Each job recompiles the sources, which is cheap beside
  that. The unit tests run in several JVMs at once
  (`maxParallelForks` in [`app/build.gradle.kts`](../app/build.gradle.kts), at
  most four and fewer on a smaller machine). The JVM is the isolation boundary:
  every test class stays whole in one JVM and no class shares process state with
  another, so splitting the classes over JVMs changes nothing about what a test
  can see. Android lint analyzes the main and the test sources as separate
  tasks, which Gradle schedules beside the rest of the build. The build cache
  is what lets a re-run reuse compilation, packaging and lint outputs instead
  of repeating the work. A cached result is only correct if every file a task
  reads is declared as an input, so a test that reads a file outside `app/src`
  needs that file listed there (today `docs/architecture.md`,
  `tools/read_diagnostics.py`, `tools/read_recording.py` and the worker's
  `frames.py` that it imports), and `tools/ci_areas.py` must run the app jobs
  when one of those files changes. The Gradle daemon keeps its 2 GB heap and a
  test JVM its default heap, so four of them together stay well inside the
  runner's 16 GB.
- **Go.** `go test ./...` runs one process per package, and `-p` (by default the
  runner's core count) runs several packages at once. The API's tests live in
  one package, so the tests that share no state call `t.Parallel()` and run
  beside each other in that process, up to `-parallel` (again the core count by
  default). The tests that cannot share a process with another stay sequential:
  they set an environment variable, replace a package-level function, measure
  wall-clock time, or drive realtime sockets and timers. Go runs those before it
  resumes the parallel ones, so a parallel test never overlaps a sequential test
  that is changing the environment. One job runs the whole module; splitting it
  into shards bought no time, because most of each shard's run was checkout and
  setup on a fresh runner.
- **Python.** The worker's tests are independent, so
  `uv run --frozen pytest -n 4` runs them in four processes (pytest-xdist, in the
  worker's dev dependencies). `tests/test_realtime.py` drives real sockets and
  timers and is timing-sensitive, so it runs afterwards on its own instead of
  beside the rest.
- **Integration.** The suite runs in one job with `pytest -n 4`. Every pytest
  worker is a separate process, so the session fixture starts its own API server
  on an operating-system-assigned port with its own temporary data directory and
  its own worker processes: the workers share no server, data or port. Within a
  worker the tests still share that worker's server and run one after the other,
  with the helpers that clear the queue before a test that needs it. The port,
  the data directory and the recording ids are unique per run, so the four
  workers cannot collide.

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
# app (CI runs these as three parallel jobs)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./tools/check_kotlin.sh

# Go
cd backend/api && go vet ./... && go test -count=1 ./...
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

# the integration suite, in four workers as CI runs it
cd backend && uv run --project worker --frozen pytest -n 4 tests/test_integration.py

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
