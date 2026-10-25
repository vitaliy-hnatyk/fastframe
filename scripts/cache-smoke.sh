#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" $(find framework-cache/src/main/java -name '*.java') scripts/CacheSmoke.java
javac --release 21 -d "$TMP" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -cp "$TMP" -d "$TMP" demo-app/src/main/java/dev/fastframe/demo/routes/CacheRoutes.java scripts/CacheHttpSmoke.java
java -cp "$TMP" CacheSmoke
java -cp "$TMP" CacheHttpSmoke
