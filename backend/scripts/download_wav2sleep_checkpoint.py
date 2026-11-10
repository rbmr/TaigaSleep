#!/usr/bin/env python3
"""Download the pinned wav2sleep checkpoint for offline worker inference.

The worker never downloads a model while a job runs. Install the optional
extra, run this script once on a machine with network access, then point
``TAIGA_WAV2SLEEP_CHECKPOINT`` at the resulting directory.

    cd backend
    uv run --project worker --frozen --extra wav2sleep \
        python scripts/download_wav2sleep_checkpoint.py --dest /srv/taiga/models/wav2sleep

The implementation lives in ``taiga_worker.checkpoint`` so the worker image can
run the same download and checksum verification at build time.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Allow running the script straight from a checkout, not only through the
# worker environment.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "worker"))

from taiga_worker.checkpoint import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
