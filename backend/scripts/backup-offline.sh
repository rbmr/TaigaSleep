#!/bin/sh
set -eu

# Offline backup for a stopped instance (or a filesystem snapshot that makes
# the data directory consistent). This includes data.db, auxiliary.db and the
# storage/ directory with uploaded files.
DATA_DIR="${1:-/var/lib/taiga-sleep}"
DEST="${2:-.}"

if [ ! -d "$DATA_DIR" ]; then
    echo "no such data directory: $DATA_DIR" >&2
    exit 1
fi

mkdir -p "$DEST"
STAMP=$(date -u +%Y%m%d-%H%M%S)
ARCHIVE="$DEST/taiga-sleep-data-$STAMP.tar.gz"
tar -C "$(dirname "$DATA_DIR")" -czf "$ARCHIVE" "$(basename "$DATA_DIR")"
echo "wrote $ARCHIVE"
