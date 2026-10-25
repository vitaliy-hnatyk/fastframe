# FastFrame v0.20 — HTTP Security

FastFrame v0.20 is **experimental**. Security hardening reduces risk; it does not constitute a security certification.

## New modules and APIs

- `framework-security`: `CorsPolicy`, `IpRateLimiter`, `HttpSecurity`, no third-party runtime dependencies.
- `framework-netty`: enforces CORS and a shared optional rate limiter before dispatching a controller; adds security headers to *handler-generated* responses (including 4xx/5xx).
- `demo-app`: reads optional security settings from `Config` / `.env` / process environment.

### Config (demo)

| Variable | Default | Meaning |
| --- | --- | --- |
| `FF_CORS_ALLOWED_ORIGINS` | empty | comma-separated **exact** HTTP(S) origins, e.g. `https://app.example.com,https://admin.example.com`; empty disables CORS |
| `FF_CORS_ALLOW_CREDENTIALS` | `false` | allow browser credentials for the *exact* allowlist only |
| `FF_RATE_LIMIT_PER_MINUTE` | `0` | fixed-window per-IP limit; 0 disables local throttling |
| `FF_RATE_LIMIT_MAX_IPS` | `4096` | maximum unique active IP buckets; new clients are rejected when full |
| `FF_HSTS` | `false` | only enable when **public-facing HTTPS** is enforced end-to-end by trusted deployment |

Allowed CORS request headers: `Authorization`, `Content-Type`, `X-Request-ID`. Allowed methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS. CORS OPTIONS preflight returns 204, invalid/mismatched origin or request returns 403. Browser credential requests will never see `Access-Control-Allow-Origin: *`.

**CORS is not authentication or CSRF prevention.** `Origin` may be forged by scripts outside a browser; JWT/policies still protect privileged routes. If using cookie-based auth, implement CSRF tokens and cookie SameSite rules separately.

Security headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`. `Strict-Transport-Security: max-age=31536000` is opt-in. A site rendering HTML needs a reviewed CSP; this API-oriented release does not enforce a global CSP.

The Netty handler overrides application-specified `Access-Control-*` headers to keep the allowlist authoritative. Decoder-generated errors occurring *before* `NettyRequestHandler` (for example a too-large request handled by `HttpObjectAggregator`) may not carry these headers; apply global headers at the reverse proxy as well.

### Rate limit implementation and limits

`IpRateLimiter` is process-local, fixed-window, shared across Netty channels. It returns HTTP 429 and integer `Retry-After` in seconds. It uses the **socket remote address**; it deliberately never trusts user-provided `X-Forwarded-For`. With a reverse proxy, all traffic may share the proxy's IP bucket. Configure per-real-client enforcement **at the trusted reverse proxy** or implement an authenticated identity limiter. No distributed sharing: multiple FastFrame instances need an external store/gateway to enforce a cluster-wide policy. A full active-key map fails closed for new clients.

## Manual Windows testing

In PowerShell (JDK 21 installed):

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\security-smoke.ps1

$env:FF_CORS_ALLOWED_ORIGINS = 'http://localhost:3000'
$env:FF_RATE_LIMIT_PER_MINUTE = '2'
.\scripts\run-demo.ps1 -SkipBuild
```

In another terminal:

```powershell
curl.exe -i http://127.0.0.1:8080/_health/live
curl.exe -i -H 'Origin: http://localhost:3000' http://127.0.0.1:8080/_health/live
curl.exe -i -H 'Origin: https://untrusted.example' http://127.0.0.1:8080/_health/live
curl.exe -i -X OPTIONS -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: GET' http://127.0.0.1:8080/_health/live
```

With `FF_RATE_LIMIT_PER_MINUTE=2`, repeated requests from the same source IP will return 429. Preflight OPTIONS is answered before the limiter; set gateway limits if OPTIONS flooding matters. Note that loopback requests to any route share the same rate bucket, including health checks.

## Vulnerability review

`.github/workflows/dependency-review.yml` checks new dependencies in pull requests; `.github/dependabot.yml` proposes Maven/Actions upgrades. These checks run on GitHub only where repository permissions/features allow. They do not replace a complete dependency audit, penetration test, or runtime security evaluation. Recheck Netty, AWS SDK, Lettuce and Micronaut versions before a production deployment.

See `PRODUCTION.md` for Windows/XAMPP reverse proxy TLS deployment. Benchmarks should explicitly record enabled security features; CORS and local rate limiting can change RPS and latency.
