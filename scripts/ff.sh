#!/usr/bin/env bash
# Linux/macOS Artisan-style CLI. Requires JDK 21 + Maven 3.9+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ "${1:-}" != "--skip-build" ]]; then
  mvn -B -ntp -pl framework-cli -am -DskipTests package
  if [[ "${1:-}" == 'route:list' ]]; then
    mvn -B -ntp -pl demo-app -am -DskipTests clean compile
  fi
else
  shift
fi
java -jar framework-cli/target/framework-cli-0.21.0-SNAPSHOT.jar "$@" --root "$ROOT"
