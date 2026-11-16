#!/usr/bin/env python3
"""Decide which CI jobs apply to a change.

The aggregate gate in .github/workflows/ci.yml has to know which checks a pull
request needs: a documentation change runs none of the build jobs, a change to
the app runs the app jobs, a change to the worker runs the worker, the Python
and the integration jobs, and so on. A skipped job is then a legitimate skip
instead of a required check that never reported.

Run by the `changes` job, and locally as
`python3 tools/ci_areas.py --base origin/main --head HEAD` to see what a branch
would run.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys

# The areas a job can be gated on, in the order the workflow names them.
AREAS = ("app", "go", "worker", "python", "integration")

# A change to these rebuilds or rechecks the app: its sources, its Gradle
# inputs, the package table ArchitectureTest enforces, the detekt setup that
# checks its style, and the Python files its unit tests run (the two reader tools
# and the worker's frame module that read_recording.py imports).
APP_PREFIXES = (
    "app/",
    "gradle/",
    "tools/detekt/",
)
APP_FILES = (
    "gradle.properties",
    "docs/architecture.md",
    "tools/read_recording.py",
    "tools/read_diagnostics.py",
    "backend/worker/taiga_worker/frames.py",
)
APP_SUFFIXES = (".gradle.kts",)

GO_PREFIX = "backend/api/"
WORKER_PREFIX = "backend/worker/"
BACKEND_PREFIX = "backend/"
PYTHON_PREFIXES = ("backend/worker/", "tools/")
PYTHON_FILES = ("tools/ruff.toml",)
WORKFLOW_PREFIX = ".github/workflows/"

# What each area gates, for the self-test and the log line.
DESCRIPTIONS = {
    "app": "app tests, Android lint and the Kotlin check",
    "go": "Go tests, go vet, staticcheck and govulncheck",
    "worker": "worker unit tests",
    "python": "ruff check and ruff format --check",
    "integration": "the API and worker integration suite",
}


def areas_of(path: str) -> set[str]:
    """The areas one changed path belongs to."""
    if path.startswith(WORKFLOW_PREFIX):
        # A workflow change is a CI change: the checks it edits have to run,
        # because nothing else validates them.
        return set(AREAS)
    if path.endswith(".md"):
        # Prose only matters where a check reads it: ArchitectureTest enforces
        # the package table in docs/architecture.md.
        return {"app"} if path in APP_FILES else set()
    areas: set[str] = set()
    if path.startswith(APP_PREFIXES) or path in APP_FILES or path.endswith(APP_SUFFIXES):
        areas.add("app")
    if path.startswith(GO_PREFIX):
        areas.add("go")
    if path.startswith(WORKER_PREFIX):
        areas.add("worker")
    if path.startswith(PYTHON_PREFIXES) or path in PYTHON_FILES:
        areas.add("python")
    if path.startswith(BACKEND_PREFIX):
        areas.add("integration")
    return areas


def areas_of_paths(paths: list[str]) -> set[str]:
    areas: set[str] = set()
    for path in paths:
        areas |= areas_of(path)
    return areas


def changed_paths(base: str, head: str) -> list[str]:
    """The paths that differ between base and head, with their merge base."""
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"git diff {base}...{head} failed:\n{result.stderr.strip()}")
    paths = set(path for path in result.stdout.splitlines() if path)
    if head == "HEAD":
        # A local run before the work is committed should still say what CI
        # would do: tools/check_version_bump.py reads the worktree for the same
        # reason.
        for arguments in (["diff", "--name-only"], ["ls-files", "--others", "--exclude-standard"]):
            local = subprocess.run(["git", *arguments], capture_output=True, text=True, check=True)
            paths |= {path for path in local.stdout.splitlines() if path}
    return sorted(paths)


SELF_TEST_CASES: tuple[tuple[list[str], set[str]], ...] = (
    ([], set()),
    (["docs/vision.md", "README.md"], set()),
    (["app/src/main/java/com/rbmr/taigasleep/MainActivity.kt"], {"app"}),
    (["app/lint-baseline.xml"], {"app"}),
    (["gradle/libs.versions.toml"], {"app"}),
    (["tools/read_recording.py"], {"app", "python"}),
    (["tools/read_diagnostics.py"], {"app", "python"}),
    (["backend/worker/taiga_worker/frames.py"], {"app", "worker", "python", "integration"}),
    (["build.gradle.kts"], {"app"}),
    (["gradle.properties"], {"app"}),
    (["docs/architecture.md"], {"app"}),
    (["tools/detekt/baseline.xml"], {"app", "python"}),
    (["tools/release_plan.py"], {"python"}),
    (["tools/ruff.toml"], {"python"}),
    (["backend/api/queue.go"], {"go", "integration"}),
    (["backend/api/go.mod"], {"go", "integration"}),
    (["backend/worker/taiga_worker/worker.py"], {"worker", "python", "integration"}),
    (["backend/tests/test_integration.py"], {"integration"}),
    (["backend/README.md"], set()),
    (["tools/README.md"], set()),
    (["backend/version.properties"], {"integration"}),
    (["docs/versioning.md", "app/version.properties"], {"app"}),
    ([".github/workflows/ci.yml"], set(AREAS)),
    ([".github/dependabot.yml"], set()),
)


def self_test() -> int:
    failures = 0
    for paths, expected in SELF_TEST_CASES:
        actual = areas_of_paths(paths)
        if actual != expected:
            failures += 1
            print(
                f"  {paths or ['(nothing)']}: expected {sorted(expected)}, got {sorted(actual)}",
                file=sys.stderr,
            )
    if failures:
        print(f"{failures} case(s) failed", file=sys.stderr)
        return 1
    print(f"{len(SELF_TEST_CASES)} cases pass")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base",
        metavar="REF",
        help="the commit or ref the change is compared against; without it every area runs",
    )
    parser.add_argument("--head", metavar="REF", default="HEAD", help="the commit to inspect")
    parser.add_argument(
        "--github-output",
        metavar="PATH",
        help="also write GitHub Actions outputs here",
    )
    parser.add_argument("--self-test", action="store_true", help="run the case table and exit")
    arguments = parser.parse_args()

    if arguments.self_test:
        return self_test()

    areas: set[str] = set(AREAS)
    summary = "every area runs"
    if arguments.base:
        try:
            paths = changed_paths(arguments.base, arguments.head)
        except SystemExit as failure:
            print(failure, file=sys.stderr)
            print("the comparison failed, so every area runs", file=sys.stderr)
        else:
            areas = areas_of_paths(paths)
            summary = f"{len(paths)} changed path(s) inspected"

    for area in AREAS:
        print(f"{area}: {'yes' if area in areas else 'no'} ({DESCRIPTIONS[area]})")

    if arguments.github_output:
        with open(arguments.github_output, "a", encoding="utf-8") as output:
            for area in AREAS:
                output.write(f"{area}={'true' if area in areas else 'false'}\n")

    print(summary)
    if "GITHUB_STEP_SUMMARY" in os.environ:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write("| Area | Runs | What it checks |\n| --- | --- | --- |\n")
            for area in AREAS:
                mark = "yes" if area in areas else "no"
                summary.write(f"| `{area}` | {mark} | {DESCRIPTIONS[area]} |\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
