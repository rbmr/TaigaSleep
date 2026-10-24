#!/bin/sh
set -eu

# Consistent database + stored-files backup through PocketBase's backup API.
# See backup.py for environment variables and options.
exec python3 "$(dirname "$0")/backup.py" "$@"
