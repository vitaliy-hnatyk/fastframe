#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" $(find framework-core/src/main/java -name '*.java') \
  $(find demo-app/src/main/java/dev/fastframe/demo -name '*.java' ! -name 'Application.java') \
  scripts/FFSmoke.java
java -cp "$classes" FFSmoke
