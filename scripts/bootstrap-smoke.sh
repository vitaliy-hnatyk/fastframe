#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
mkdir -p "$build/core" "$build/config" "$build/events" "$build/application" "$build/test"
javac --release 21 -d "$build/core" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -d "$build/config" $(find framework-config/src/main/java -name '*.java')
javac --release 21 -d "$build/events" $(find framework-events/src/main/java -name '*.java')
cp="$build/core:$build/config:$build/events"
javac --release 21 -cp "$cp" -d "$build/application" $(find framework-application/src/main/java -name '*.java')
javac --release 21 -cp "$cp:$build/application" -d "$build/test" scripts/BootstrapSmoke.java
java -cp "$cp:$build/application:$build/test" BootstrapSmoke
