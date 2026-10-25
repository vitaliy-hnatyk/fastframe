#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
find framework-database/src/main/java -name '*.java' -print0 | xargs -0 javac --release 21 -d "$TMP"
javac --release 21 -cp "$TMP" -d "$TMP" scripts/DatabaseSmoke.java
java -cp "$TMP" DatabaseSmoke
