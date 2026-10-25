#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mapfile -t sources < <(find framework-storage/src/main/java framework-http-client/src/main/java -name '*.java' -type f)
javac --release 21 -d "$TMP" "${sources[@]}" scripts/StorageSmoke.java scripts/HttpClientSmoke.java
java -cp "$TMP" StorageSmoke
java -cp "$TMP" HttpClientSmoke
