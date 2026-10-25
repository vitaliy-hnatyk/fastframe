#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" framework-security/src/main/java/dev/fastframe/security/*.java scripts/SecuritySmoke.java
java -cp "$out" SecuritySmoke
