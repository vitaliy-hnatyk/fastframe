#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
mkdir -p "$build/events" "$build/application" "$build/config" "$build/cache" "$build/queue" "$build/storage" "$build/core" "$build/database" "$build/orm" "$build/migrations" "$build/processor" "$build/auth" "$build/demo" "$build/generated"
javac --release 21 -d "$build/config" $(find framework-config/src/main/java -name '*.java')
javac --release 21 -d "$build/events" $(find framework-events/src/main/java -name '*.java')
javac --release 21 -d "$build/queue" $(find framework-queue/src/main/java -name '*.java')
javac --release 21 -d "$build/storage" $(find framework-storage/src/main/java -name '*.java')
javac --release 21 -d "$build/cache" $(find framework-cache/src/main/java -name '*.java')
javac --release 21 -d "$build/database" $(find framework-database/src/main/java -name '*.java')
javac --release 21 -d "$build/core" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -cp "$build/core:$build/config:$build/events" -d "$build/application" $(find framework-application/src/main/java -name '*.java')
javac --release 21 -cp "$build/core" -d "$build/auth" $(find framework-auth/src/main/java -name '*.java')
javac --release 21 -cp "$build/core:$build/auth" -d "$build/auth" scripts/AuthSmoke.java
java -cp "$build/core:$build/auth" AuthSmoke
javac --release 21 -cp "$build/database" -d "$build/orm" $(find framework-orm/src/main/java -name '*.java')
javac --release 21 -cp "$build/database" -d "$build/migrations" $(find framework-migrations/src/main/java -name '*.java')
javac --release 21 -cp "$build/core:$build/orm:$build/database" -proc:none -d "$build/processor" $(find framework-processor/src/main/java -name '*.java')
# Explicit processor invocation mirrors Maven's annotationProcessorPaths configuration.
javac --release 21 -cp "$build/queue:$build/storage:$build/cache:$build/core:$build/auth:$build/processor:$build/database:$build/orm:$build/migrations:$build/config:$build/events:$build/application" \
  -processorpath "$build/core:$build/processor:$build/orm:$build/database" \
  -processor dev.fastframe.processor.FastFrameProcessor,dev.fastframe.processor.FastFrameModelProcessor \
  -s "$build/generated" -d "$build/demo" \
  $(find demo-app/src/main/java -name '*.java' ! -name 'Application.java')
javac --release 21 -cp "$build/queue:$build/storage:$build/cache:$build/core:$build/auth:$build/database:$build/demo:$build/orm:$build/migrations" -d "$build/demo" scripts/FFSmoke.java
java -cp "$build/queue:$build/storage:$build/cache:$build/core:$build/auth:$build/database:$build/demo:$build/orm:$build/migrations" FFSmoke
javac --release 21 -cp "$build/queue:$build/storage:$build/cache:$build/core:$build/auth:$build/database:$build/demo:$build/orm:$build/migrations" -d "$build/demo" scripts/GuardSmoke.java
java -cp "$build/queue:$build/storage:$build/cache:$build/core:$build/auth:$build/database:$build/demo:$build/orm:$build/migrations" GuardSmoke
javac --release 21 -cp "$build/core" -d "$build/demo" scripts/FFV04Smoke.java
java -cp "$build/core:$build/demo" FFV04Smoke
javac --release 21 -cp "$build/core" -d "$build/demo" scripts/FFV07Smoke.java
java -cp "$build/core:$build/demo" FFV07Smoke
javac --release 21 -cp "$build/core" -d "$build/demo" \
  framework-netty/src/main/java/dev/fastframe/netty/ErrorResponses.java scripts/FFV05Smoke.java
java -cp "$build/core:$build/demo" dev.fastframe.netty.FFV05Smoke
# These should produce a compile-time error, not generate broken runtime code.
mkdir -p "$build/negative/invalid" "$build/negative/cycle"
cat > "$build/negative/invalid/BadController.java" <<'JAVA'
package failures;
import dev.fastframe.core.annotation.*;
@Controller("/bad") public class BadController {
    @Get public String wrongReturnType() { return "bad"; }
}
JAVA
if javac --release 21 -cp "$build/core:$build/processor:$build/orm:$build/database" \
    -processorpath "$build/core:$build/processor:$build/orm:$build/database" \
    -processor dev.fastframe.processor.FastFrameProcessor \
    -d "$build/negative/invalid" "$build/negative/invalid/BadController.java" \
    >"$build/negative-invalid.log" 2>&1; then
  echo "Expected invalid controller to fail compilation" >&2; exit 1
fi
grep -q 'Route must return Response' "$build/negative-invalid.log"
cat > "$build/negative/cycle/One.java" <<'JAVA'
package failures;
import dev.fastframe.core.annotation.*;
@Singleton public class One { @Inject public One(Two two) {} }
JAVA
cat > "$build/negative/cycle/Two.java" <<'JAVA'
package failures;
import dev.fastframe.core.annotation.*;
@Singleton public class Two { @Inject public Two(One one) {} }
JAVA
if javac --release 21 -cp "$build/core:$build/processor:$build/orm:$build/database" \
    -processorpath "$build/core:$build/processor:$build/orm:$build/database" \
    -processor dev.fastframe.processor.FastFrameProcessor \
    -d "$build/negative/cycle" "$build/negative/cycle/One.java" "$build/negative/cycle/Two.java" \
    >"$build/negative-cycle.log" 2>&1; then
  echo "Expected circular singleton dependencies to fail compilation" >&2; exit 1
fi
grep -q 'Circular singleton dependency' "$build/negative-cycle.log"
mkdir -p "$build/negative/body"
cat > "$build/negative/body/BadBody.java" <<'JAVA'
package failures;
import dev.fastframe.core.annotation.*;
import dev.fastframe.core.http.Response;
public record BadBody(@Size(min=3) int count) { }
JAVA
cat > "$build/negative/body/BadBodyController.java" <<'JAVA'
package failures;
import dev.fastframe.core.annotation.*;
import dev.fastframe.core.http.Response;
@Controller("/body") public class BadBodyController {
    @Post public Response handle(@Body BadBody dto) { return Response.text("bad"); }
}
JAVA
if javac --release 21 -cp "$build/core:$build/processor:$build/orm:$build/database" \
    -processorpath "$build/core:$build/processor:$build/orm:$build/database" \
    -processor dev.fastframe.processor.FastFrameProcessor \
    -d "$build/negative/body" "$build/negative/body/BadBody.java" "$build/negative/body/BadBodyController.java" \
    >"$build/negative-body.log" 2>&1; then
  echo "Expected invalid @Body record to fail compilation" >&2; exit 1
fi
grep -q '@Size requires String' "$build/negative-body.log"
echo "PROCESSOR NEGATIVE TESTS PASS"
