#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -d "$TMP" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -cp "$TMP" -d "$TMP" $(find framework-observability/src/main/java -name '*.java') scripts/ObservabilitySmoke.java
java -cp "$TMP" ObservabilitySmoke
