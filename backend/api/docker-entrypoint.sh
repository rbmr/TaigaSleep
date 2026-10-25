#!/bin/sh
set -eu

data_dir="${TAIGA_DATA_DIR:-/pb/pb_data}"
http_addr="${TAIGA_HTTP_ADDR:-0.0.0.0:8090}"
hooks_pool="${TAIGA_HOOKS_POOL:-1}"

mkdir -p "$data_dir"

# Optional first-run bootstrap. Remove these variables after the first start;
# the superuser is stored in the database. No credentials are baked into the
# image.
if [ -n "${TAIGA_ADMIN_EMAIL:-}" ] && [ -n "${TAIGA_ADMIN_PASSWORD:-}" ]; then
    /app/taiga-sleep-api superuser upsert "$TAIGA_ADMIN_EMAIL" "$TAIGA_ADMIN_PASSWORD" --dir "$data_dir"
    unset TAIGA_ADMIN_PASSWORD
fi

set -- serve \
    --http="$http_addr" \
    --dir="$data_dir" \
    --migrationsDir=/app/pb_migrations \
    --hooksDir=/app/pb_hooks \
    --hooksPool="$hooks_pool" \
    --automigrate=false \
    --hooksWatch=false

# Optional at-rest encryption for PocketBase's own settings/params. The value
# must be exactly 32 characters; PocketBase reads it from this environment
# variable when the flag is present.
if [ -n "${TAIGA_ENCRYPTION_KEY:-}" ]; then
    set -- "$@" --encryptionEnv TAIGA_ENCRYPTION_KEY
fi

exec /app/taiga-sleep-api "$@"
