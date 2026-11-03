#!/usr/bin/env python3
"""Decide which components a merge into main publishes, and fail on a reused version.

Run by .github/workflows/release.yml on every push to main, and by
.github/workflows/version.yml on every pull request. Running the same decision in
both places means a green pull request is a merge that will publish cleanly. See
docs/versioning.md.

The rules:

- A missing tag is published. That is the normal new-version case, and it also
  heals a version that was bumped but never released.
- A tag that points at the commit being built is already published, so it is
  skipped and a rerun is harmless.
- A tag that exists on another commit is skipped when the version did not change
  against the base. That is the normal steady state after an app-only or
  backend-only merge: the other component keeps a version that was released
  earlier.
- The same tag on another commit is an error when the version did change, because
  that is a version number being reused.

Usage:

    python3 tools/release_plan.py                     # compare against origin/main
    python3 tools/release_plan.py --base <commit>     # compare against any commit
    python3 tools/release_plan.py --force-both        # manual runs build both tracks
    python3 tools/release_plan.py --github-output "$GITHUB_OUTPUT"
    python3 tools/release_plan.py --self-test
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass

BASE_REF = os.environ.get("BASE_REF", "origin/main")
APP_FILE = "app/version.properties"
BACKEND_FILE = "backend/version.properties"
COMPONENTS = ("app", "backend")


class PlanFailed(Exception):
    """The merge cannot be published; the message is shown to the contributor."""


@dataclass(frozen=True)
class Decision:
    component: str
    version: str
    release: bool
    reason: str

    def row(self) -> str:
        action = "release" if self.release else "skip"
        return f"{self.component}: {self.version} ({action}, {self.reason})"


def decide(
    component: str,
    version: str,
    base_version: str | None,
    tag_commit: str | None,
    head_commit: str | None,
) -> Decision:
    """Whether to publish [component] at [version], or why that is impossible."""
    if tag_commit is None:
        return Decision(component, version, True, "no release yet")
    if head_commit is not None and tag_commit == head_commit:
        return Decision(component, version, False, "already published for this commit")
    if base_version != version:
        raise PlanFailed(
            f"{component}-v{version} already exists on another commit, but "
            f"{version_of(component)} moved {base_version or 'nothing'} -> {version}: "
            "never reuse a version number, bump it instead"
        )
    return Decision(component, version, False, "unchanged since the base")


def version_of(component: str) -> str:
    return APP_FILE if component == "app" else BACKEND_FILE


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise PlanFailed(f"git {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def read_version(component: str, source: str) -> str | None:
    """The version at [source], or None when that commit does not have the file."""
    path = version_of(component)
    if source == "head":
        try:
            with open(path, encoding="utf-8") as file:
                text = file.read()
        except FileNotFoundError:
            return None
    else:
        exists = subprocess.run(
            ["git", "cat-file", "-e", f"{source}:{path}"], capture_output=True
        )
        if exists.returncode != 0:
            return None
        text = git("show", f"{source}:{path}")

    for line in text.splitlines():
        line = line.strip()
        if line.startswith("version="):
            return line.removeprefix("version=").strip()
    return None


def tag_commit(component: str, version: str) -> str | None:
    result = subprocess.run(
        ["git", "rev-list", "-n", "1", f"refs/tags/{component}-v{version}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def plan(base: str, force_both: bool = False) -> list[Decision]:
    head = git("rev-parse", "HEAD")
    decisions = []
    for component in COMPONENTS:
        version = read_version(component, "head")
        if version is None:
            raise PlanFailed(f"{version_of(component)} is missing or has no version")
        if force_both:
            decisions.append(Decision(component, version, True, "manual run"))
            continue
        base_version = read_version(component, base)
        decisions.append(
            decide(
                component,
                version,
                base_version,
                tag_commit(component, version),
                head,
            )
        )
    return decisions


def write_github_output(decisions: list[Decision], path: str) -> None:
    lines = []
    for decision in decisions:
        lines.append(f"{decision.component}_version={decision.version}")
        lines.append(f"{decision.component}_release={'true' if decision.release else 'false'}")
    with open(path, "a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def self_test() -> None:
    """The decision table, so the rule cannot drift from its test."""

    def check(expected: bool, reason: str | None, **kwargs) -> None:
        decision = decide("app", "0.4.1", **kwargs)
        assert decision.release is expected, decision
        if reason is not None:
            assert decision.reason == reason, decision

    # No release yet: publish it, and heal a version that never got published.
    check(True, "no release yet", base_version="0.4.0", tag_commit=None, head_commit="bbb")
    check(True, "no release yet", base_version="0.4.1", tag_commit=None, head_commit="bbb")
    # A rerun of the commit that already published this version: skip.
    check(False, "already published for this commit", base_version="0.4.0", tag_commit="bbb", head_commit="bbb")
    # An unchanged component whose version was published earlier: skip. This is the
    # case that broke app-only and backend-only merges.
    check(False, "unchanged since the base", base_version="0.4.1", tag_commit="aaa", head_commit="bbb")
    # A changed version whose tag already exists on another commit: refuse.
    try:
        decide("app", "0.4.1", "0.4.0", "aaa", "bbb")
    except PlanFailed as failure:
        assert "never reuse a version number" in str(failure), failure
    else:
        raise AssertionError("reusing a released version must fail")
    # A new component (no base file) with a free version: publish.
    check(True, "no release yet", base_version=None, tag_commit=None, head_commit="bbb")

    print("release plan self-test passed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=BASE_REF, help=f"commit to compare against (default {BASE_REF})")
    parser.add_argument("--force-both", action="store_true", help="release both components (manual runs)")
    parser.add_argument("--github-output", metavar="PATH", help="also write GitHub Actions outputs here")
    parser.add_argument("--self-test", action="store_true", help="run the decision table and exit")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    decisions = plan(args.base, args.force_both)
    print(f"Compared against {args.base}:")
    for decision in decisions:
        print(f"  {decision.row()}")
    if args.github_output:
        write_github_output(decisions, args.github_output)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PlanFailed as failure:
        print(f"error: {failure}", file=sys.stderr)
        sys.exit(1)
