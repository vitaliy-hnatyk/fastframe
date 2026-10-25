# FastFrame v0.14 — File Storage and Async HTTP Client

This is an **experimental API**, not a production security guarantee. Requires Java 21. The repository also continues to include the v0.13 queue/scheduler, Redis cache, ORM, and auth features.

## Modules

| Maven module | Dependencies | Responsibilities |
|---|---|---|
| `framework-storage` | JDK only | `Storage` API, `LocalDiskStorage`, key validation, whole-file CRUD |
| `framework-storage-s3` | `framework-storage`, AWS SDK v2 S3 | Optional S3 backend using a caller-managed `S3Client` |
| `framework-http-client` | JDK 21 only | `AsyncHttpClient` with `sendAsync`, `get`, `postJson`, connection/request timeouts, bounded response bodies |

These dependencies are **opt-in**. `framework-storage-s3` is not added to the default demo classpath.

## Local file storage

```java
Storage storage = new LocalDiskStorage(Path.of("C:/fastframe/private-uploads"), 10 * 1024 * 1024);
storage.put("avatars/user-42.png", pngBytes);
byte[] bytes = storage.get("avatars/user-42.png").orElseThrow();
boolean present = storage.exists("avatars/user-42.png");
storage.delete("avatars/user-42.png");
```

Only simple portable key segments (`[A-Za-z0-9][A-Za-z0-9._-]*`, separated by `/`) are allowed. Traversal (`..`), absolute paths, backslashes and reserved characters are rejected. Symlinks at existing path components are rejected. Writes use a same-directory temporary file followed by a replace move. File size is checked before writing and while reading. **This is not a race-proof filesystem sandbox against another process that can modify the root**. Keep the storage directory outside any publicly served web root, and grant it restricted OS permissions.

All `Storage` operations are **blocking**. In Netty handlers, execute them in a bounded worker pool or another asynchronous bridge, never inside an event loop.

## Optional S3 backend

```java
import software.amazon.awssdk.services.s3.S3Client;
import dev.fastframe.storage.s3.S3Storage;

S3Client client = S3Client.create(); // default credential and region providers
try {
    Storage storage = new S3Storage(client, "my-private-bucket", "fastframe", 10 * 1024 * 1024);
    storage.put("documents/report.pdf", pdfBytes);
} finally {
    client.close();
}
```

Requires AWS credentials and region configured **outside source code** (environment or standard AWS provider chain). Use a private bucket with least-privilege permissions. The adapter does not create a bucket, generate public URLs or implement multipart upload. It uses blocking AWS SDK operations, so isolate it from Netty event loops. The adapter has not been exercised against AWS or an S3-compatible server in the build environment.

## Outbound asynchronous HTTP

```java
import dev.fastframe.httpclient.AsyncHttpClient;
import java.net.URI;
import java.time.Duration;

try (AsyncHttpClient http = new AsyncHttpClient(
        Duration.ofSeconds(3), Duration.ofSeconds(10), 1024 * 1024)) {
    var response = http.get(URI.create("https://api.example.org/status"));
    response.thenAccept(r -> System.out.println(r.status() + " " + r.text()));

    var post = http.postJson(URI.create("https://api.example.org/users"), "{\"name\":\"Ada\"}");
}
```

Requests do not follow redirects by default. The client rejects non-HTTP(S) URLs, has connection and request timeouts, and rejects bodies larger than the configured maximum while streaming (no unbounded buffering). **Application code must use an allowlist of trusted destinations**: HTTP(S) validation does not prevent SSRF against localhost, private IPs, or cloud metadata endpoints. Do not expose arbitrary user-supplied URLs through an API.

## Demo uploads (disabled by default)

Without `FF_STORAGE_ROOT`, private files default to a folder in the user home directory (`~/.fastframe-private-files`), rather than inside XAMPP `www`.

The demo adds binary body support for `Content-Type: application/octet-stream` while retaining strict UTF-8 validation for normal requests. There is **no `multipart/form-data` parser yet**, and HTTP upload size is capped by the existing Netty `MAX_REQUEST_BYTES` (default 64 KiB).

In PowerShell **only for trusted local testing**:

```powershell
cd C:\xampp\www\fastframe
$env:FF_ENABLE_STORAGE_DEMO = 'true'
$env:FF_STORAGE_ROOT = 'C:\fastframe-private-files'
$env:FF_STORAGE_DEMO_TOKEN = 'dev-local-example-token-change-me-123456'
$env:FF_HOST = '127.0.0.1'  # localhost only
.\scripts\run-demo.ps1
```

In another PowerShell terminal (same token):

```powershell
'hello' | Set-Content -Path .\sample.txt -NoNewline
curl.exe -X PUT -H 'Content-Type: application/octet-stream' `
  -H 'X-Storage-Demo-Token: dev-local-example-token-change-me-123456' `
  --data-binary '@sample.txt' http://127.0.0.1:8080/api/storage/files/sample.txt
curl.exe -H 'X-Storage-Demo-Token: dev-local-example-token-change-me-123456' `
  http://127.0.0.1:8080/api/storage/files/sample.txt
```

DELETE uses the same URL and token. The token is a **demo-only shared secret**, not a replacement for authentication, authorization, TLS, CSRF protections, malware scanning, MIME sniffing, quotas, or per-user isolation. The demo server binds to `127.0.0.1` by default. Storage routes are registered only with `FF_ENABLE_STORAGE_DEMO=true` and a token of at least 24 characters. Do not expose these endpoints to the Internet.

## Build and smoke-test on Windows

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force   # only when you trust these scripts
.\scripts\storage-smoke.ps1
.\scripts\run-demo.ps1
```

`scripts/storage-smoke.ps1` selects the configured Temurin JDK 21 in this session without uninstalling or replacing Java 8. Requires Maven 3.9+. JUnit tests cover local files, invalid keys, size limit, symlinks when supported, a local HTTP test server, JSON POST, non-followed redirects, and HTTP response limits. A Netty `EmbeddedChannel` test checks byte-perfect non-UTF8 binary uploads.

Linux/macOS Java-only checks:

```bash
bash scripts/storage-smoke.sh
bash scripts/core-smoke.sh
```

These Java-only smoke tests **do not replace** a full Maven build or actual Windows + Netty + S3 integration tests.

## Limitations and subsequent work

No streaming large-file uploads, multipart/form-data, file type validation, signed URLs, S3 multipart transfers, virus scanning, resilient retries, redirect policy customization, or SSRF-safe allowlisting in the base HTTP client. Those features need dedicated integration and security tests before production use.
