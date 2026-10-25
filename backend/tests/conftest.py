"""pytest bootstrap for the backend integration suite.

The suite lives in ``backend/tests`` and imports its fixtures as
``tests.recording_fixture``, the same way the worker suite imports its own
``tests`` package. Adding ``backend`` to ``sys.path`` keeps that import working
without packaging the test helpers.
"""

from __future__ import annotations

import sys
from pathlib import Path

BACKEND_DIR = str(Path(__file__).resolve().parents[1])
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)
