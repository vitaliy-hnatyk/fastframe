#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
mkdir -p "$build/core" "$build/processor" "$build/demo" "$build/generated"
javac --release 21 -d "$build/core" $(find framework-core/src/main/java -name '*.java')
javac --release 21 -cp "$build/core" -d "$build/processor" $(find framework-processor/src/main/java -name '*.java')
# Explicit processor invocation mirrors Maven's annotationProcessorPaths configuration.
javac --release 21 -cp "$build/core:$build/processor" \
  -processorpath "$build/core:$build/processor" \
  -processor dev.fastframe.processor.FastFrameProcessor \
  -s "$build/generated" -d "$build/demo" \
  $(find demo-app/src/main/java -name '*.java' ! -name 'Application.java')
javac --release 21 -cp "$build/core:$build/demo" -d "$build/demo" scripts/FFSmoke.java
java -cp "$build/core:$build/demo" FFSmoke
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
if javac --release 21 -cp "$build/core:$build/processor" \
    -processorpath "$build/core:$build/processor" \
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
if javac --release 21 -cp "$build/core:$build/processor" \
    -processorpath "$build/core:$build/processor" \
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
if javac --release 21 -cp "$build/core:$build/processor" \
    -processorpath "$build/core:$build/processor" \
    -processor dev.fastframe.processor.FastFrameProcessor \
    -d "$build/negative/body" "$build/negative/body/BadBody.java" "$build/negative/body/BadBodyController.java" \
    >"$build/negative-body.log" 2>&1; then
  echo "Expected invalid @Body record to fail compilation" >&2; exit 1
fi
grep -q '@Size requires String' "$build/negative-body.log"
echo "PROCESSOR NEGATIVE TESTS PASS"
