#!/usr/bin/env python3
"""Print the pytest node ids of one shard of a test file.

The integration suite shares one API server between the tests of a process, so
its tests are not safe to run beside each other inside one process. Splitting
the suite across jobs instead gives every job its own server, its own data
directory and its own worker processes. This script lists the tests once, keeps
one contiguous slice of that collection for the given `index`, and prints the
selected ids, one per line, for pytest to run. Keeping the collection order
means a shard runs its tests in the order the whole suite runs them, so the
tests that make a statement about a fresh server still see one. The split is
deterministic, so the shards are disjoint and together are the whole suite.

Run it in an environment that can import the suite, from the directory holding
the test paths:

    uv run --project worker --frozen python ../../tools/pytest_shard.py \
        --index 0 --shards 4 tests/test_integration.py
"""

from __future__ import annotations

import argparse
import subprocess
import sys

NODE_ID = "::"


class ShardError(Exception):
    """The suite could not be collected, so a shard would be incomplete."""


def node_ids(paths: list[str]) -> list[str]:
    """The collected test ids of the paths, sorted, as pytest would run them."""
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "--collect-only", "-q", "-p", "no:cacheprovider", *paths],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise ShardError(f"pytest --collect-only failed:\n{result.stderr.strip()}")
    ids = [
        line.strip()
        for line in result.stdout.splitlines()
        if NODE_ID in line and line.strip().startswith(tuple(paths))
    ]
    if not ids:
        raise ShardError(f"pytest collected no tests from {' '.join(paths)}")
    return ids


def shard(ids: list[str], index: int, shards: int) -> list[str]:
    """One shard's share: a contiguous slice, in collection order."""
    size = -(-len(ids) // shards)  # ceil, so the first shards take the remainder
    return ids[index * size : (index + 1) * size]


def self_test(ids: list[str], shards: int) -> int:
    """Check that the shards are disjoint and together cover every test."""
    seen: dict[str, int] = {}
    failures: list[str] = []
    for index in range(shards):
        part = shard(ids, index, shards)
        if not part:
            failures.append(f"shard {index} is empty, so its job would run no tests")
        for node in part:
            if node in seen:
                failures.append(f"{node} is in shards {seen[node]} and {index}")
            seen[node] = index
    for node in ids:
        if node not in seen:
            failures.append(f"{node} is in no shard")
    if failures:
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(f"{len(failures)} shard check(s) failed", file=sys.stderr)
        return 1
    print(f"{len(ids)} tests split over {shards} shards with no gaps or overlaps")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", help="the test paths pytest should collect")
    parser.add_argument("--shards", type=int, default=4, help="how many jobs share the tests")
    parser.add_argument("--index", type=int, help="which shard to print, from 0")
    parser.add_argument("--self-test", action="store_true", help="check the split and exit")
    arguments = parser.parse_args()

    if arguments.shards < 1:
        parser.error("--shards must be at least 1")
    try:
        ids = node_ids(arguments.paths)
        if arguments.self_test:
            return self_test(ids, arguments.shards)
        if arguments.index is None:
            parser.error("--index is required unless --self-test is given")
        if not 0 <= arguments.index < arguments.shards:
            parser.error("--index must be between 0 and --shards minus 1")
        for node in shard(ids, arguments.index, arguments.shards):
            print(node)
    except ShardError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
