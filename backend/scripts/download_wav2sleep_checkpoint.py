#!/usr/bin/env python3
"""Download the pinned wav2sleep checkpoint for offline worker inference.

The worker never downloads a model while a job runs. Install the optional
extra, run this script once on a machine with network access, then point
``TAIGA_WAV2SLEEP_CHECKPOINT`` at the resulting directory.

    cd backend
    uv run --project worker --frozen --extra wav2sleep \
        python scripts/download_wav2sleep_checkpoint.py --dest /srv/taiga/models/wav2sleep

The default destination is ``$TAIGA_WAV2SLEEP_CHECKPOINT`` or
``${XDG_CACHE_HOME:-~/.cache}/taiga-sleep/models/wav2sleep``. The checkpoint is
MIT-licensed; see ``CHECKPOINT.json`` in the destination for provenance.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = "joncarter/wav2sleep"
REVISION = "8b6e46ab3ef6e3945282b81f631933c59db0b205"
SHA256 = "ea6fb4410315cf6cce406fe1ffd44cba83e8dc69be6a69101aff62cc2cbee0bc"
BYTES = 9_666_562
BASE_URL = f"https://huggingface.co/{REPO}/resolve/{REVISION}"


def default_destination() -> Path:
    configured = os.environ.get("TAIGA_WAV2SLEEP_CHECKPOINT", "").strip()
    if configured:
        return Path(configured).expanduser()
    cache = os.environ.get("XDG_CACHE_HOME", "").strip()
    root = Path(cache).expanduser() if cache else Path.home() / ".cache"
    return root / "taiga-sleep" / "models" / "wav2sleep"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dest",
        type=Path,
        default=None,
        help="Checkpoint directory (default: $TAIGA_WAV2SLEEP_CHECKPOINT or the XDG cache)",
    )
    parser.add_argument("--force", action="store_true", help="Overwrite an existing checkpoint")
    return parser


def download(url: str, destination: Path) -> None:
    temporary = destination.with_name(destination.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "taiga-sleep-worker"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as handle:
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                handle.write(block)
    except (OSError, urllib.error.URLError) as exc:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f"cannot download {url}: {exc}") from exc
    temporary.replace(destination)


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    destination = (args.dest or default_destination()).expanduser().resolve()
    weights = destination / "state_dict.pth"

    if weights.is_file() and not args.force:
        digest = hashlib.sha256(weights.read_bytes()).hexdigest()
        if digest == SHA256 and weights.stat().st_size == BYTES:
            print(f"checkpoint already present: {destination}")
            return 0
        print(f"checkpoint at {destination} does not match the pin; use --force", file=sys.stderr)
        return 2

    destination.mkdir(parents=True, exist_ok=True)
    try:
        print(f"downloading {REPO}@{REVISION[:12]} into {destination}")
        for name in ("config.yaml", "README.md", "state_dict.pth"):
            print(f"  {name}")
            download(f"{BASE_URL}/{name}", destination / name)
    except RuntimeError as exc:
        print(exc, file=sys.stderr)
        return 1

    digest = hashlib.sha256(weights.read_bytes()).hexdigest()
    size = weights.stat().st_size
    if digest != SHA256 or size != BYTES:
        print(
            f"downloaded checkpoint does not match the pin "
            f"(expected sha256 {SHA256}, got {digest})",
            file=sys.stderr,
        )
        return 1

    provenance = {
        "repo": REPO,
        "revision": REVISION,
        "url": f"https://huggingface.co/{REPO}",
        "license": "MIT",
        "state_dict_sha256": SHA256,
        "state_dict_bytes": BYTES,
        "citation": (
            "Carter JF, Tarassenko L. wav2sleep: A Unified Multi-Modal Approach to "
            "Sleep Stage Classification from Physiological Signals. ML4H 2024. "
            "arXiv:2411.04644"
        ),
    }
    (destination / "CHECKPOINT.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8"
    )
    print(f"verified {destination}")
    print(f"export TAIGA_WAV2SLEEP_CHECKPOINT={destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
