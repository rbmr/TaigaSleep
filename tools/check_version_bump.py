#!/usr/bin/env python3
"""Check that a pull request bumps the version of every component it changes.

Run by .github/workflows/version.yml on pull requests into main, and locally as
`python3 tools/check_version_bump.py`. It exits non-zero with an explanation when
a changed component keeps the version it already has on main.

The rules it enforces, in one place:

- app/version.properties holds the app release version and requires_api.
- backend/version.properties holds the backend release version and api.
- Every part of a release version is 0..999, because the app derives its
  versionCode as major * 1_000_000 + minor * 1_000 + patch.
- A changed component's version must be strictly greater than main's, compared
  as semver.
- The app and the backend in main speak the same contract: requires_api and api
  must be equal.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

# Overridable for a local dry run (`BASE_REF=HEAD~3 python3 tools/check_version_bump.py`).
BASE_REF = os.environ.get("BASE_REF", "origin/main")
APP_FILE = "app/version.properties"
BACKEND_FILE = "backend/version.properties"

# Changes here build nothing, so they need no version bump and publish no
# release. Everything else is app code or an app build input.
IGNORED_PREFIXES = ("docs/", ".github/", "tools/")
IGNORED_FILES = {".gitignore", ".editorconfig", "LICENSE"}

VERSION_PATTERN = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
MAX_PART = 999


class CheckFailed(Exception):
    """A rule was broken; the message is shown to the contributor."""


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise CheckFailed(f"git {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout


def read_properties(path: str, source: str) -> dict[str, str] | None:
    """Read a properties file from the worktree ("head") or from a git ref ("base")."""
    if source == "head":
        try:
            with open(path, encoding="utf-8") as file:
                text = file.read()
        except FileNotFoundError:
            return None
    else:
        # A component can be new in this pull request, so a missing base file is
        # not an error.
        exists = subprocess.run(
            ["git", "cat-file", "-e", f"{BASE_REF}:{path}"], capture_output=True
        )
        if exists.returncode != 0:
            return None
        text = git("show", f"{BASE_REF}:{path}")

    values: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise CheckFailed(f"{path}: cannot parse {line!r}")
        values[key.strip()] = value.strip()
    return values


def parse_version(raw: str | None, path: str) -> tuple[int, int, int]:
    match = VERSION_PATTERN.match(raw or "")
    if not match:
        raise CheckFailed(f"{path}: version must be MAJOR.MINOR.PATCH, got {raw!r}")
    version = tuple(int(part) for part in match.groups())
    if any(part > MAX_PART for part in version):
        raise CheckFailed(
            f"{path}: every part of {raw} must be 0..{MAX_PART}, because the app "
            "derives its versionCode from it"
        )
    return version


def parse_api(raw: str | None, path: str, key: str) -> int:
    try:
        return int(raw or "")
    except ValueError:
        raise CheckFailed(f"{path}: {key} must be an integer, got {raw!r}") from None


def component_of(path: str) -> str | None:
    """Which component a changed path belongs to, or None when it builds nothing."""
    if path.lower().endswith(".md") or path in IGNORED_FILES:
        return None
    if path.startswith(IGNORED_PREFIXES):
        return None
    if path.startswith("backend/"):
        return "backend"
    return "app"


def changed_paths() -> list[str]:
    """The pull request's changes against main, plus uncommitted work for a local run."""
    sets = (
        git("diff", "--name-only", f"{BASE_REF}...HEAD").splitlines(),
        git("diff", "--name-only", "HEAD").splitlines(),
        git("ls-files", "--others", "--exclude-standard").splitlines(),
    )
    return sorted({path for paths in sets for path in paths if path})


def changed_components() -> set[str]:
    components = {component_of(path) for path in changed_paths()}
    return {component for component in components if component is not None}


def main() -> int:
    base_app = read_properties(APP_FILE, "base")
    base_backend = read_properties(BACKEND_FILE, "base")
    head_app = read_properties(APP_FILE, "head")
    head_backend = read_properties(BACKEND_FILE, "head")

    if head_app is None or head_backend is None:
        raise CheckFailed(f"both {APP_FILE} and {BACKEND_FILE} must exist")

    app_version = parse_version(head_app.get("version"), APP_FILE)
    backend_version = parse_version(head_backend.get("version"), BACKEND_FILE)
    requires_api = parse_api(head_app.get("requires_api"), APP_FILE, "requires_api")
    api = parse_api(head_backend.get("api"), BACKEND_FILE, "api")

    if requires_api != api:
        raise CheckFailed(
            f"the app requires API {requires_api} but the backend speaks API {api}: "
            "main must ship an app and a backend that agree"
        )

    changed = changed_components()
    problems: list[str] = []

    def check(name: str, path: str, version, base, was_changed: bool) -> str:
        if not was_changed:
            return f"{path}: not changed"
        if base is not None:
            base_version = parse_version(base.get("version"), f"{BASE_REF}:{path}")
            if version <= base_version:
                previous = ".".join(str(part) for part in base_version)
                current = ".".join(str(part) for part in version)
                problems.append(
                    f"{name}: {path} is still {current}, but the change needs a version "
                    f"greater than {previous} on main"
                )
                return f"{path}: {previous} -> {current} (not bumped)"
        current = ".".join(str(part) for part in version)
        return f"{path}: {current}"

    rows = [
        check(
            "app",
            APP_FILE,
            app_version,
            base_app,
            "app" in changed,
        ),
        check(
            "backend",
            BACKEND_FILE,
            backend_version,
            base_backend,
            "backend" in changed,
        ),
    ]

    print(f"Compared against {BASE_REF}:")
    for row in rows:
        print(f"  {row}")

    if problems:
        print("\nVersion bump required:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nBump the version of every component the pull request changes. "
            "See docs/versioning.md.",
            file=sys.stderr,
        )
        return 1

    print("\nEvery changed component has a new version.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except CheckFailed as failure:
        print(f"error: {failure}", file=sys.stderr)
        sys.exit(1)
