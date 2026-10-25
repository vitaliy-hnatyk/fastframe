# FastFrame CLI v0.16 — Artisan-inspired commands

FastFrame v0.16 extends **`framework-cli`**, a dependency-free Java 21 CLI JAR. It generates code compatible with the existing compile-time HTTP router, ORM mapper, and SQL migrations. The project stays experimental and is not production-ready.

## Windows 10/11 PowerShell

FastFrame uses JDK 21 for this session (by default `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`) while leaving system Java 8 untouched.

Run from `C:\xampp\www\fastframe` after backing up local changes:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force # only if you trust these scripts
.\scripts\cli-smoke.ps1
.\scripts\ff.ps1 make:controller PostController
.\scripts\ff.ps1 make:model Post
.\scripts\ff.ps1 make:service Invoice
.\scripts\ff.ps1 make:request CreateInvoice
.\scripts\ff.ps1 make:middleware Audit
.\scripts\ff.ps1 make:job SendEmail
.\scripts\ff.ps1 make:migration create_posts
.\scripts\ff.ps1 route:list
```

`route:list` recompiles `demo-app` before showing the generated routes. Controller and model templates are added under `demo-app/src/main/java/dev/fastframe/demo/`; migration files go to `demo-app/migrations/`. The example `make:model Post` and `make:migration create_posts` are independent: the SQL schema is a minimal scaffold; edit it as required. The CLI **refuses to overwrite files**. To speed up repeated commands, add `-SkipBuild` to `ff.ps1` once the CLI JAR has been built. Do **not** use `-SkipBuild` for `route:list` after editing controllers if you want current metadata.

An existing migration such as `V001__create_users.sql` causes the next migration version to be `V002__create_posts.sql`. Only `create_<table>` has a ready-to-edit `CREATE TABLE`; other migration names contain a `TODO` comment and must be filled in before running migrations.

To generate in a different package:

```powershell
.\scripts\ff.ps1 make:controller Invoice -Package dev.fastframe.demo.controllers
```

## Linux and macOS

```bash
bash scripts/ff.sh make:controller Post
bash scripts/ff.sh make:model Post
bash scripts/ff.sh make:service Invoice
bash scripts/ff.sh make:request CreateInvoice
bash scripts/ff.sh make:middleware Audit
bash scripts/ff.sh make:job SendEmail
bash scripts/ff.sh make:migration create_posts
bash scripts/ff.sh route:list
bash scripts/cli-smoke.sh
```

## Direct Java usage

```bash
mvn -pl framework-cli -am -DskipTests package
java -jar framework-cli/target/framework-cli-0.19.0-SNAPSHOT.jar help
java -jar framework-cli/target/framework-cli-0.19.0-SNAPSHOT.jar make:controller Order --root .
java -jar framework-cli/target/framework-cli-0.19.0-SNAPSHOT.jar route:list --root .
```

On Windows, `--root` accepts the full absolute project path, and the standalone JAR also works from any working directory.

## Route listing: important limitation

`route:list` reads `demo-app/target/classes/META-INF/fastframe/routes.tsv`, which is written by **FastFrameProcessor at compile time**. The manifest enumerates `@Controller`, `@Get`, `@Post`, `@Put`, and `@Delete` mappings and **does not include manually registered `Router` routes** (such as the existing demo `ApiRoutes` and Storage/Queue/Database routes). It is not a comprehensive live HTTP route inventory. Later versions can expose manual registrations through a combined build-time manifest or a separate router introspection API.

## Safety & scope

- Templates accept simple PascalCase Java names and validated Java packages; no source-code evaluation or script execution from CLI arguments.
- Files are created with `CREATE_NEW`, so an existing file is never overwritten; output symlinks in the project tree are rejected.
- Schema generation does not create a DB connection or apply migrations. Review generated SQL before running the migration CLI.
- `route:list` is compile-time metadata, not runtime classpath scanning or JVM reflection.
- CLI v0.16 implements `make:service`, `make:request`, and `make:middleware` and `make:job`; these are starter templates, **not** full Laravel FormRequest, middleware auto-registration or queue auto-discovery. Route naming and full Artisan compatibility remain unimplemented.

## Verification

Run `mvn -B -ntp -pl framework-cli,demo-app -am clean verify` on a machine with Maven 3.9+, Java 21 and dependencies. `scripts/cli-smoke.sh` additionally validates generated-controller compilation and its route manifest using `javac`. Native Windows CI also runs `scripts/cli-smoke.ps1`. All other modules remain unchanged except the processor's build-time route manifest generation and version bumps.

## Additional v0.16 generators

| CLI command | Generated Java type | Integration step |
|---|---|---|
| `make:service Invoice` | `@Singleton` class `InvoiceService` | Inject into a controller using `@Inject` constructor |
| `make:request CreateInvoice` | validated `record CreateInvoiceRequest` | Accept with `@Body` in a controller |
| `make:middleware Audit` | `Middleware` implementation | Explicitly register with `router.use(...)` |
| `make:job SendEmail` | `JobHandler` implementation | Register with `new JobWorker(store).handle("mail.send", new SendEmailJob())` |

All four use the existing safe generator: validated class/package names and no overwrites. `make:job` uses the optional `framework-queue` module. Generated code contains illustrative methods; application logic must be implemented separately.


## v0.17 generators

`make:provider Billing` and `make:listener UserCreated` generate templates. Register providers manually via `FastFrameApp.boot(...)`.
