#!/usr/bin/env bash
# Kotlin static analysis for the app, with detekt and the project's config.
#
# The findings that predate the check live in tools/detekt/baseline.xml; a new
# one fails this script. CI runs it after setting up Java, and it runs the same
# way locally. The jar is pinned by version and checksum and cached outside the
# repository.
set -euo pipefail

detekt_version=1.23.8
detekt_sha256=2ce2ff952e150baf28a29cda70a363b0340b3e81a55f43e51ec5edffc3d066c1

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cache_dir=${XDG_CACHE_HOME:-$HOME/.cache}/taiga-sleep
jar="$cache_dir/detekt-cli-$detekt_version-all.jar"

if [ ! -f "$jar" ]; then
  mkdir -p "$cache_dir"
  url="https://github.com/detekt/detekt/releases/download/v$detekt_version"
  curl --fail --silent --show-error --location \
    --output "$jar.part" \
    "$url/detekt-cli-$detekt_version-all.jar"
  echo "$detekt_sha256  $jar.part" | sha256sum --check --status || {
    echo "detekt: the downloaded jar does not match its checksum" >&2
    rm -f "$jar.part"
    exit 1
  }
  mv "$jar.part" "$jar"
fi

exec java -jar "$jar" \
  --input "$root/app/src" \
  --config "$root/tools/detekt/detekt.yml" \
  --baseline "$root/tools/detekt/baseline.xml" \
  --build-upon-default-config \
  --parallel
