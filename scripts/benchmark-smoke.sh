#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/core" "$work/database" "$work/orm" "$work/processor" "$work/bench" "$work/gen"
javac --release 21 -d "$work/core" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -d "$work/database" $(find framework-database/src/main/java -name '*.java')
javac --release 21 -cp "$work/database" -d "$work/orm" $(find framework-orm/src/main/java -name '*.java')
javac --release 21 -cp "$work/core:$work/orm:$work/database" -proc:none -d "$work/processor" $(find framework-processor/src/main/java -name '*.java')
javac --release 21 -cp "$work/core:$work/processor:$work/orm:$work/database" \
  -processorpath "$work/core:$work/processor:$work/orm:$work/database" -processor dev.fastframe.processor.FastFrameProcessor \
  -s "$work/gen" -d "$work/bench" \
  $(find benchmarks/fastframe-app/src/main/java -name '*.java' ! -name 'BenchmarkApplication.java')
javac --release 21 -cp "$work/core:$work/bench" -d "$work/bench" scripts/FFV06Smoke.java
java -cp "$work/core:$work/bench" FFV06Smoke
echo "v0.7 BENCHMARK ROUTES PASS"
python3 -m unittest discover -s benchmarks/tests -v
