#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/application" "$TMP/events" "$TMP/config" "$TMP/queue" "$TMP/cli" "$TMP/core" "$TMP/database" "$TMP/orm" "$TMP/processor" "$TMP/output"
javac --release 21 -d "$TMP/config" $(find "$ROOT/framework-config/src/main/java" -name '*.java')
javac --release 21 -d "$TMP/events" $(find "$ROOT/framework-events/src/main/java" -name '*.java')
javac --release 21 -d "$TMP/queue" "$ROOT/framework-queue/src/main/java/dev/fastframe/queue/JobHandler.java"
javac --release 21 -d "$TMP/cli" "$ROOT/framework-cli/src/main/java/dev/fastframe/cli/FastFrameCli.java"
javac --release 21 -d "$TMP/core" $(find "$ROOT/framework-core/src/main/java" -name '*.java')
javac --release 21 -cp "$TMP/core:$TMP/config:$TMP/events" -d "$TMP/application" $(find "$ROOT/framework-application/src/main/java" -name '*.java')
javac --release 21 -d "$TMP/database" $(find "$ROOT/framework-database/src/main/java" -name '*.java')
javac --release 21 -cp "$TMP/database" -d "$TMP/orm" $(find "$ROOT/framework-orm/src/main/java" -name '*.java')
javac --release 21 -cp "$TMP/core:$TMP/orm:$TMP/database" -d "$TMP/processor" \
  $(find "$ROOT/framework-processor/src/main/java" -name '*.java')
CLI=(java -cp "$TMP/cli" dev.fastframe.cli.FastFrameCli)
"${CLI[@]}" make:controller Post --root "$TMP"
"${CLI[@]}" make:model Category --root "$TMP"
"${CLI[@]}" make:service Payment --root "$TMP"
"${CLI[@]}" make:request CreatePayment --root "$TMP"
"${CLI[@]}" make:middleware Audit --root "$TMP"
"${CLI[@]}" make:job SendEmail --root "$TMP"
"${CLI[@]}" make:provider Cache --root "$TMP"
"${CLI[@]}" make:listener UserCreated --root "$TMP"
"${CLI[@]}" make:migration create_posts --root "$TMP"
test -f "$TMP/demo-app/migrations/V001__create_posts.sql"
if "${CLI[@]}" make:controller Post --root "$TMP"; then
  echo 'Error: CLI unexpectedly overwrote controller' >&2
  exit 1
fi
javac --release 21 -cp "$TMP/core:$TMP/processor:$TMP/orm:$TMP/database" \
  -processorpath "$TMP/core:$TMP/processor:$TMP/orm:$TMP/database" \
  -processor dev.fastframe.processor.FastFrameProcessor,dev.fastframe.processor.FastFrameModelProcessor \
  -d "$TMP/output" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/controllers/PostController.java" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/models/Category.java" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/services/PaymentService.java" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/dto/CreatePaymentRequest.java" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/middleware/AuditMiddleware.java"
test -f "$TMP/output/dev/fastframe/demo/models/CategoryFastFrameMapper.class"
javac --release 21 -cp "$TMP/application:$TMP/events:$TMP/config:$TMP/core" -d "$TMP/output" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/providers/CacheProvider.java" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/listeners/UserCreatedListener.java"
javac --release 21 -cp "$TMP/queue" -d "$TMP/output" \
  "$TMP/demo-app/src/main/java/dev/fastframe/demo/jobs/SendEmailJob.java"
"${CLI[@]}" route:list --root "$TMP" --classes "$TMP/output" | tee "$TMP/routes.txt"
grep -q 'GET.*\/api\/posts/{id}' "$TMP/routes.txt"
# Invalid user input must not write outside the project.
if "${CLI[@]}" make:model '../Escape' --root "$TMP"; then
  echo 'Error: accepted invalid class name' >&2
  exit 1
fi
echo 'FastFrame CLI + generated route manifest smoke: PASS'
