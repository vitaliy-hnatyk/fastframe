# FastFrame v0.11 – Authentication & Authorization

## Scope
- `framework-auth`: per-request bearer identity, HS256 signed JWTs (issuer, audience, exp, iat, nbf, 32-byte minimum HMAC key, 1h default max TTL); role/permission checks and resource-level `Policy<T>`.
- `PasswordHasher`: salted PBKDF2-HMAC-SHA256, default **600,000 iterations**. Use unique salts and store the entire versioned hash. Slow hashing **must run in a bounded worker executor** rather than a Netty event-loop thread.
- `@Authenticated` and `@Authorize("users.read")` / `@Authorize("role:admin")` supported on controllers or route methods. Guards are emitted during compilation and return 401/403; generated code does not reflectively inspect annotations at runtime.
- Protected sample paths: `/api/secure/me`, `/api/secure/users`, `/api/secure/admin`.
- Unprotected paths (e.g. `/api/health`) remain public.

## Windows setup (PowerShell)
Keep Java 8 globally; scripts select JDK 21 only for the session.

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
# Generate a random 256-bit key for local testing. Preserve it in your current shell only.
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:FF_JWT_SECRET_B64 = [Convert]::ToBase64String($bytes)
.\scripts\auth-smoke.ps1
.\scripts\run-demo.ps1 -SkipBuild
```

In a **second terminal**, set `FF_JWT_SECRET_B64` to the same secret (e.g. pass from your local secrets manager; do not commit it) and use JDK 21:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$token = java -cp .\demo-app\target\demo-app-0.12.0-SNAPSHOT.jar dev.fastframe.demo.DevToken dev-user users.read
curl.exe -i http://127.0.0.1:8080/api/secure/me
curl.exe -i -H "Authorization: Bearer $token" http://127.0.0.1:8080/api/secure/me
curl.exe -i -H "Authorization: Bearer $token" http://127.0.0.1:8080/api/secure/users
curl.exe -i -H "Authorization: Bearer $token" http://127.0.0.1:8080/api/secure/admin
```
Expected status codes: 401 without token; 200 for authenticated identity and `users.read`; 403 for `/admin` (no `admin` role).

## Important security limitations
This is an **experimental authentication foundation**, not a complete identity platform. No registration/login endpoint, token revocation, key rotation, refresh tokens, CSRF/session authentication, account lockouts, login rate limiting, MFA, or browser cookie security. JWT payloads are signed but **not encrypted**. Never put passwords or secrets in claims. Deploy only behind HTTPS; avoid writing Authorization headers to logs. For production, prefer a vetted JWT library and security review, load secrets from an external manager, use short-lived tokens and protect password verification from DoS. Permissions in a JWT remain valid until expiration; if immediate revocation is required, check permissions against an external store or use a token denylist.

The `BearerAuthMiddleware` is added only when `FF_JWT_SECRET_B64` is configured; otherwise secured routes **fail closed** (401). Register all global middleware **before routes**.
