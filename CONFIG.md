# FastFrame v0.16 — .env and configuration

`framework-config` is a standalone, dependency-free Java 21 module. It does not modify process environment variables or system settings. The HTTP server loads configuration once at startup (not per request).

## Local Windows setup (Java 8 + Java 21)

For a checkout under `C:\xampp\www\fastframe`, **do not store any live `.env` secrets in the web document root**. Use a path outside XAMPP:

```powershell
cd C:\xampp\www\fastframe
New-Item -ItemType Directory -Force C:\fastframe-private | Out-Null
Copy-Item .\.env.example C:\fastframe-private\.env
notepad C:\fastframe-private\.env
$env:FF_ENV_FILE = 'C:\fastframe-private\.env'
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\config-smoke.ps1
.\scripts\run-demo.ps1
```

This uses your selected Temurin JDK 21 for FastFrame only; existing Java 8 applications are unaffected. For local non-web development outside the Apache document root, copy `.env.example` to `.env` in the project root. `.env` is ignored by Git, but **do not rely on `.gitignore` or `.htaccess` as the sole security control**.

## Precedence

1. Existing **process environment variables**, such as `$env:PORT=8081` or `$env:FF_DB_URL=...`.
2. Variables in the file selected by `FF_ENV_FILE`, or `.env` in the current directory if no override exists.
3. Explicit defaults in Java code.

`FF_ENV_FILE` itself is an OS environment setting (not discovered from `.env`). Unset keys do not require the file to exist. Example:

```java
import dev.fastframe.config.Config;

Config config = Config.load();
String jdbcUrl = config.get("FF_DB_URL");
int port = config.intValue("PORT", 8080, 1, 65535);
boolean storageDemo = config.booleanValue("FF_ENABLE_STORAGE_DEMO", false);
var timeout = config.millis("REQUEST_TIMEOUT_MS", 10_000, 1, 3_600_000);
String secret = config.require("FF_JWT_SECRET_B64"); // optional only if JWT enabled
```

Alternatively `Config.load(path, overrideMap)` is deterministic in tests. The API is immutable; it does not print secrets and does not interpolate values, execute commands, or parse YAML/PHP.

## Supported syntax

```dotenv
PORT=8080
APP_ENV=local
FF_HOST=127.0.0.1
FF_DB_URL='jdbc:h2:mem:fastframe;DB_CLOSE_DELAY=-1'
FF_ENABLE_STORAGE_DEMO=false
# Comment
```

Supports comments, blank lines, `export KEY=VALUE`, single/double quoted values, and common double-quoted escape sequences (`\n`, `\r`, `\t`, `\\`, `\"`). `#` begins an inline comment only after whitespace on an unquoted value. Unquoted Windows paths are supported (`FF_STORAGE_ROOT=C:\fastframe-private\files`). Duplicate keys, malformed lines and invalid numeric/boolean values fail immediately without printing credential values. Maximum file size: 1 MiB.

## Security and limitations

- Never commit `.env` or put secrets in HTTP response bodies, source code or benchmark logs.
- `FF_JWT_SECRET_B64` must be set to a securely generated strong secret to enable protected JWT endpoints; it is not supplied with the template.
- The generated root `.htaccess` denies access to `.env` files only if Apache processes `.htaccess`. It does not secure all Apache/Nginx configurations; **place private files outside any publicly served directory**.
- Environment variables override file values, even when the environment value is an empty string.
- Config is a Java API, not a Laravel PHP-compatible `config()` function; PHP config arrays, nested key paths and hot reload are not implemented.
- CLI output does not display `.env` values. Validate configuration with the smoke scripts.

## Verification

Linux/macOS: `bash scripts/config-smoke.sh`, `bash scripts/cli-smoke.sh`.

Windows: `.\scripts\config-smoke.ps1`, `.\scripts\cli-smoke.ps1`.

Full Maven reactor build: `mvn -B -ntp -pl demo-app -am clean verify` with JDK 21.
