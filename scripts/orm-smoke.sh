#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
find framework-core/src/main/java framework-database/src/main/java framework-orm/src/main/java framework-migrations/src/main/java -name '*.java' > "$TMP/source.txt"
javac --release 21 -d "$TMP" "@$TMP/source.txt"
javac --release 21 -cp "$TMP" -proc:none -d "$TMP" framework-processor/src/main/java/dev/fastframe/processor/FastFrameModelProcessor.java
javac --release 21 -cp "$TMP" -processor dev.fastframe.processor.FastFrameModelProcessor -d "$TMP" scripts/OrmSmokeUser.java
javac --release 21 -cp "$TMP" -d "$TMP" scripts/OrmSmokeMain.java scripts/MigrationSmoke.java
java -cp "$TMP" OrmSmokeMain
java -cp "$TMP" MigrationSmoke
# Compile-time annotation validation: reject missing @Id and unsafe identifiers.
cat > "$TMP/BadOrmModel.java" <<'JAVA'
import dev.fastframe.orm.annotation.*;
@Table("unsafe;drop")
public record BadOrmModel(String name) {}
JAVA
if javac --release 21 -cp "$TMP" -processor dev.fastframe.processor.FastFrameModelProcessor -d "$TMP" "$TMP/BadOrmModel.java" > "$TMP/bad-orm.log" 2>&1; then
    echo "Expected unsafe model to fail compilation" >&2
    exit 1
fi
grep -q "Invalid @Table SQL identifier" "$TMP/bad-orm.log"
echo "ORM NEGATIVE COMPILE TEST PASS"
