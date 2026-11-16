#!/usr/bin/env python3
"""Split the tests of one Go package over a fixed number of CI shards.

`go test ./...` runs one process per package, so a package with most of the
suite's wall clock decides how long the whole Go job takes. The API's tests in
`internal/api` are such a package. They cannot safely call `t.Parallel()`: many
of them set package-level variables or environment variables, which would race
with a test running beside them. They can still run in separate processes, so
the `Go tests` matrix in `.github/workflows/ci.yml` splits them by name instead
of by package.

Every job asks this script for its share. The split is deterministic (sorted
test names, round robin by index), so the jobs together run every test exactly
once. `--self-test` checks what the workflow depends on: no test is in two
shards and no test is in none.

Run it from `backend/api`, where the Go module lives:

    python3 ../../tools/go_test_shard.py --index 0 --shards 4
    python3 ../../tools/go_test_shard.py --self-test
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

DEFAULT_PACKAGE = "./internal/api"
DEFAULT_DIRECTORY = "."
TEST_NAME = re.compile(r"^Test[A-Za-z0-9_]*$")


class ShardError(Exception):
    """The package's tests could not be split as the workflow needs."""


def test_names(directory: str, package: str) -> list[str]:
    """The top-level tests of one package, sorted, as `go test -list` sees them."""
    result = subprocess.run(
        ["go", "test", "-list", "^Test", package],
        cwd=directory,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise ShardError(f"go test -list {package} failed:\n{result.stderr.strip()}")
    names = sorted(
        line.strip() for line in result.stdout.splitlines() if TEST_NAME.match(line.strip())
    )
    if not names:
        raise ShardError(f"no tests found in {package}; the sharding would cover nothing")
    return names


def shard(names: list[str], index: int, shards: int) -> list[str]:
    """One shard's share: every `shards`-th name, starting at `index`."""
    return names[index::shards]


def pattern(names: list[str]) -> str:
    """A `go test -run` regex that matches exactly the named tests."""
    return "^(?:" + "|".join(re.escape(name) for name in names) + ")$"


def self_test(names: list[str], shards: int) -> int:
    """Check that the shards are disjoint and together cover every test."""
    failures: list[str] = []
    seen: dict[str, int] = {}
    for index in range(shards):
        part = shard(names, index, shards)
        if not part:
            failures.append(f"shard {index} is empty, so its job would run no tests")
        regex = re.compile(pattern(part))
        for name in names:
            if regex.match(name):
                if name in seen:
                    failures.append(f"{name} is in shards {seen[name]} and {index}")
                seen[name] = index
        for name in part:
            if name not in names:
                failures.append(f"shard {index} names {name}, which the package does not have")

    for name in names:
        if name not in seen:
            failures.append(f"{name} is in no shard")
    if failures:
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(f"{len(failures)} shard check(s) failed", file=sys.stderr)
        return 1
    print(f"{len(names)} tests split over {shards} shards with no gaps or overlaps")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", default=DEFAULT_DIRECTORY, help="the Go module directory")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="the package to split")
    parser.add_argument("--shards", type=int, default=4, help="how many jobs share the tests")
    parser.add_argument("--index", type=int, help="which shard to print, from 0")
    parser.add_argument("--self-test", action="store_true", help="check the split and exit")
    arguments = parser.parse_args()

    if arguments.shards < 1:
        parser.error("--shards must be at least 1")
    try:
        names = test_names(arguments.directory, arguments.package)
        if arguments.self_test:
            return self_test(names, arguments.shards)
        if arguments.index is None:
            parser.error("--index is required unless --self-test is given")
        if not 0 <= arguments.index < arguments.shards:
            parser.error("--index must be between 0 and --shards minus 1")
        print(pattern(shard(names, arguments.index, arguments.shards)))
    except ShardError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
