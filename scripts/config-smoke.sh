#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac --release 21 -d "$TMP" "$ROOT/framework-config/src/main/java/dev/fastframe/config/Config.java" "$ROOT/scripts/ConfigSmoke.java"
java -cp "$TMP" ConfigSmoke
