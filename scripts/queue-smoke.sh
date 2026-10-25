#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mapfile -t sources < <(find framework-queue/src/main/java -name '*.java' -type f)
javac --release 21 -d "$TMP" "${sources[@]}" scripts/QueueSmoke.java
java -cp "$TMP" QueueSmoke
