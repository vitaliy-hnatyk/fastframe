# FastFrame v0.10 — typed ORM records and SQL migrations

This release adds optional modules `framework-orm` and `framework-migrations`. Keep using Java 21 and the existing Netty HTTP engine. ORM/JDBC APIs are **blocking** and must run on a dedicated bounded worker pool, not an event loop.

## Models (compile-time generated mappers)

```java
import dev.fastframe.orm.annotation.Table;
import dev.fastframe.orm.annotation.Id;
import dev.fastframe.orm.annotation.Column;

@Table("users")
public record User(@Id Long id, @Column("full_name") String name) {}
```

With the `framework-processor` annotation processor in `annotationProcessorPaths`, javac generates `UserFastFrameMapper` alongside the `User` record. No reflective field access is performed by the repository at runtime.

```java
Database db = new Database(myDataSource);
Repository<User, Long> users = new Repository<>(db, new UserFastFrameMapper());
users.insert(new User(7L, "Ada"));
User user = users.findOrFail(7L);
users.update(new User(7L, "Grace"));
users.delete(7L);

// Or run repository operations within a single transaction:
db.transaction(tx -> {
    new Repository<>(tx, new UserFastFrameMapper()).insert(new User(8L, "Bob"));
    return null;
});
```

Supported model component types: String, `int/Integer`, `long/Long`, `boolean/Boolean`, `double/Double`, BigDecimal. IDs: String, int/Integer, long/Long. Models must be public top-level Java records. Mapping validation is compile-time; SQL identifiers are validated. Generated mappers are available only when the annotation processor is enabled. Map keys come from quoted SQL column names.

**Current limitations:** caller-assigned primary keys only (no auto-generated IDs), no relationships, lazy loading, joins, soft deletes, dirty tracking, automatic eager loading or schema inference. `save()` uses a non-atomic find-then-insert/update strategy; prefer `insert`/`update` for concurrent workloads. Repository queries are blocking and `.get(limit)` / `.where(column,value,limit)` require bounded limits. `EntityMapper` may also be implemented manually.

## Migrations

Store SQL files under `demo-app/migrations` or your own folder, e.g.:

```text
demo-app/migrations/
  V001__create_users.sql
  V002__add_index.sql
```

Each file should contain **one SQL statement**. Versions must increase monotonically. The runner creates `fastframe_schema_migrations`, stores SHA-256 checksums, skips already-applied versions, rejects modifications to applied migrations, and rejects new migrations inserted below the latest applied version. There is no automatic DOWN/rollback migration or SQL statement splitter in v0.10. Always back up production data before schema changes.

```java
var migrations = SqlMigrations.load(Path.of("demo-app/migrations"));
var result = new MigrationRunner(db).migrate(migrations);
System.out.println(result.applied());
```

Migrations run sequentially on the calling thread. Run from **one maintenance process only**, never concurrently across app instances. PostgreSQL supports transactional DDL, but H2 / other databases can auto-commit DDL; the runner does not guarantee atomic rollback of DDL on all databases. Keep migration SQL trusted; do not interpolate end-user strings.

### Windows PowerShell

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
# Local demo; run from repository root:
$env:FF_DB_URL = 'jdbc:h2:mem:fastframe;DB_CLOSE_DELAY=-1'
.\scripts\run-demo.ps1
# In another PowerShell:
curl.exe http://localhost:8080/api/db/users
```

For PostgreSQL, add PostgreSQL JDBC driver to the consuming module's pom.xml, use a pooled DataSource, and specify credentials through environment variables, not committed files. Run migrations explicitly:

```powershell
$env:FF_DB_URL = 'jdbc:postgresql://localhost:5432/fastframe'
$env:FF_DB_USER = 'my_user'
$env:FF_DB_PASSWORD = '<read securely from your secret manager>'
.\scripts\migrate.ps1 -Directory 'demo-app/migrations'
```

The demo automatically migrates embedded `jdbc:h2:mem:` databases. For all other JDBC URLs, migration-at-startup is disabled unless `FF_RUN_MIGRATIONS=true`. Standalone `migrate.ps1` always runs migrations explicitly. `FF_MIGRATIONS_DIR` selects the directory for app startup migrations. The migration CLI depends on the shaded demo JAR; it uses the environment variables above. H2 *in-memory* state is process-local, so for persistent CLI testing prefer file-backed H2 or PostgreSQL.

### Verify on Windows

```powershell
.\scripts\orm-smoke.ps1
mvn -B -ntp -pl framework-orm,framework-migrations -am test
mvn -B -ntp -pl demo-app -am clean verify
```

Maven needs JDK 21. Java 8 can remain the global Windows default; PowerShell launch scripts choose your Temurin JDK 21 for that session only.
