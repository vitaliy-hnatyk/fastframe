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
# This is a zero-external-dependency *source smoke*, not a complete benchmark app build.
# Compile the plain/JSON/routing/JWT/async controllers with javac and the local
# annotation processor. DbRedisController and DbRedisWorkload intentionally stay
# out of this smoke: they require HikariCP, PostgreSQL and the Redis adapter,
# and are compiled separately by the Maven benchmark-fastframe package step in CI.
benchmark_src="benchmarks/fastframe-app/src/main/java/dev/fastframe/benchmark"
smoke_sources=(
  "$benchmark_src/BenchJwt.java"
  "$benchmark_src/EchoRequest.java"
  "$benchmark_src/JsonSizeData.java"
  "$benchmark_src/BenchController.java"
  "$benchmark_src/AsyncJwtController.java"
)
javac --release 21 -cp "$work/core:$work/processor:$work/orm:$work/database" \
  -processorpath "$work/core:$work/processor:$work/orm:$work/database" -processor dev.fastframe.processor.FastFrameProcessor \
  -s "$work/gen" -d "$work/bench" "${smoke_sources[@]}"
javac --release 21 -cp "$work/core:$work/bench" -d "$work/bench" scripts/FFV06Smoke.java
java -cp "$work/core:$work/bench" FFV06Smoke
echo "FastFrame v0.22 JDK-only benchmark routes PASS (external DB/Redis classes: Maven CI)"
python3 -m unittest discover -s benchmarks/tests -v
