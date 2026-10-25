# FastFrame v0.9 — Database Query Builder (blocking JDBC)

New Maven module: `framework-database` (no runtime dependencies beyond Java 21). It is optional; the Netty benchmarks remain **unchanged**, with no DB requests added to benchmark workloads.

## API

```java
DataSource pool = /* HikariCP or your existing DataSource */;
Database db = new Database(pool);

List<Map<String,Object>> rows = db.table("users")
    .select("id", "name")
    .where("active", true)
    .whereIn("id", List.of(1L, 2L, 3L))
    .orderBy("id", Query.Direction.DESC)
    .limit(20).offset(0).get();

Optional<Map<String,Object>> first = db.table("users").where("id", 1L).first();
long count = db.table("users").where("active", true).count();
boolean exists = db.table("users").where("id", 1L).exists();
db.table("users").insert(Map.of("id", 7L, "name", "Ada"));
db.table("users").where("id", 7L).update(Map.of("name", "Grace"));
db.table("users").where("id", 7L).delete();

db.transaction(tx -> {
    tx.table("users").where("id", 1L).update(Map.of("name", "Updated"));
    tx.table("audit_logs").insert(Map.of("event", "rename"));
    return null;
});
```

`Query` uses safe JDBC placeholders for values. Table/column names may contain only unquoted ASCII identifier components (`schema.table` accepted); names are then double-quoted in SQL. Supported dialects are PostgreSQL and H2 in PostgreSQL mode; other databases/dialects are not tested. Sorting direction is an enum. `update` and `delete` require at least one `where` condition. The mutable builder is **not thread safe**.

The `Transaction` object is only valid inside the `Database.transaction()` callback, on the same thread. Nested transactions, migrations, joins, ORM models, pagination helpers, query logging and raw SQL fragments are **not implemented**. Do not create async futures inside a transaction callback.

### Windows: run with embedded H2 demo

The demo already depends on H2 *at runtime* for local development, but **does not load a database unless FF_DB_URL is set**. Open PowerShell in the project directory:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
$env:FF_DB_URL = 'jdbc:h2:mem:fastframe;DB_CLOSE_DELAY=-1'
.\scripts\run-demo.ps1
# another terminal
curl.exe http://localhost:8080/api/db/users
```

The demo creates a single `users` table and sample row **only** when `FF_DB_URL` starts with `jdbc:h2:mem:`. This endpoint runs JDBC operations via the demo's bounded worker thread pool, not on the Netty event loop. If using PostgreSQL, create the table yourself and add the PostgreSQL JDBC driver to `demo-app/pom.xml`; or use a pooled `DataSource` from your production DI setup. For PostgreSQL: `FF_DB_URL=jdbc:postgresql://localhost:5432/fastframe`, `FF_DB_USER`, `FF_DB_PASSWORD`.

### Tests

```powershell
mvn -B -ntp -pl framework-database -am test
mvn -B -ntp -pl demo-app -am clean verify
```

No Maven available? Java 21 SQL-generation-only smoke: `bash scripts/database-smoke.sh` (Linux/macOS/Git Bash) or `.\scripts\database-smoke.ps1` (Windows PowerShell).

### Security/performance constraints

- **Blocking JDBC must never run on a Netty event-loop thread**. Use the same bounded worker executor pattern used by the demo, or a separate pool. Overload must be handled; avoid `CallerRunsPolicy` on event loops.
- Supply a pooled `DataSource` in production; `DriverManagerDataSource` is deliberately unpooled and is for demos/tests only.
- No automatic record/POJO reflection mapping: results are `List<Map<String,Object>>`. This requires measuring serialization overhead independently.
- Prepared SQL values do not make raw `Database.execute(sql, ...)` safe if its SQL text is built from untrusted input. Reserve `execute` for trusted migrations.
- `get()` fully loads result rows; always use `limit(...)` for untrusted/unbounded tables. Streaming and cursor paging need their own design.
- No claim of faster performance than Micronaut or Laravel; current `plain/json/route/echo` benchmark does not cover database performance.
