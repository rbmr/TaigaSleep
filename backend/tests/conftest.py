"""pytest bootstrap for the backend integration suite.

The suite lives in ``backend/tests`` and imports its helpers as ``tests.harness`` and
``tests.recording_fixture``, the same way the worker suite imports its own ``tests``
package. Adding ``backend`` to ``sys.path`` keeps that import working without packaging
the test helpers.

The suite needs the built Go API: point POCKETBASE_BIN at it (see backend/README.md).
"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path
from typing import Iterator

import pytest

BACKEND_DIR = str(Path(__file__).resolve().parents[1])
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from tests.harness import Backend  # noqa: E402 - needs the path above


@pytest.fixture(scope="session")
def api() -> Iterator[Backend]:
    binary = os.environ.get("POCKETBASE_BIN") or shutil.which("pocketbase")
    if not binary:
        pytest.skip("API binary not found; set POCKETBASE_BIN to the built Go binary")

    backend = Backend(binary)
    try:
        yield backend
    finally:
        backend.close()
