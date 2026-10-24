#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/core" "$work/processor" "$work/bench" "$work/gen"
javac --release 21 -d "$work/core" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -cp "$work/core" -d "$work/processor" $(find framework-processor/src/main/java -name '*.java')
javac --release 21 -cp "$work/core:$work/processor" \
  -processorpath "$work/core:$work/processor" -processor dev.fastframe.processor.FastFrameProcessor \
  -s "$work/gen" -d "$work/bench" \
  $(find benchmarks/fastframe-app/src/main/java -name '*.java' ! -name 'BenchmarkApplication.java')
javac --release 21 -cp "$work/core:$work/bench" -d "$work/bench" scripts/FFV06Smoke.java
java -cp "$work/core:$work/bench" FFV06Smoke
echo "v0.7 BENCHMARK ROUTES PASS"
python3 -m unittest discover -s benchmarks/tests -v
