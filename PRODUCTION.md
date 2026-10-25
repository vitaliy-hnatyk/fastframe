# FastFrame — Production deployment checklist (experimental v0.20)

## Windows + XAMPP

**Do not deploy source code inside an Apache document root.** If your development checkout is `C:\xampp\www\fastframe`, move the application to `C:\apps\fastframe` before accepting public traffic; never allow Apache to serve `.env`, `pom.xml`, source code or migrations. Store secrets outside the web root, e.g. `C:\fastframe-private\.env` and point `FF_ENV_FILE` to it. Store uploads outside any public web root.

Run Netty on `127.0.0.1:8080` and enforce HTTPS at a maintained reverse proxy (Apache `mod_proxy` + `mod_ssl`, nginx or Caddy). Configure a trusted TLS certificate and a port-80-to-443 redirect. Example *illustrative* Apache 443 virtual host (replace cert paths and domain):

```apache
<VirtualHost *:443>
    ServerName api.example.com
    SSLEngine on
    SSLCertificateFile "C:/certs/api.example.com/fullchain.pem"
    SSLCertificateKeyFile "C:/certs/api.example.com/privkey.pem"
    ProxyPreserveHost On
    ProxyPass / http://127.0.0.1:8080/
    ProxyPassReverse / http://127.0.0.1:8080/
    # Enforce request size and per-real-client rate limits at the edge too.
    <Location "/_metrics">
        Require local
    </Location>
</VirtualHost>
```

Set `FF_HSTS=true` only after verifying that `https://api.example.com` is fully operational for all clients. The built-in limiter sees Apache's loopback IP, not the browser IP. Do not trust arbitrary client-supplied forwarding headers to fix this.

## Application prerequisites

- Windows firewall denies public access to port 8080; only reverse proxy accepts public HTTPS traffic.
- Use a dedicated Windows service account with the least filesystem permissions.
- Configure strong JWT secrets in environment/private `.env`; rotate keys with a planned token migration.
- Enforce database connection pooling, migration coordination and backups. Avoid JDBC calls on Netty event loop.
- Apply rate limits separately to login/password-reset APIs, and add CSRF protection if browser sessions/cookies are used.
- Disable debug and unauthenticated metrics. Do not log authorization headers, cookies, bodies or raw URL query strings.
- Use health/readiness probes and tested graceful shutdown; restart/service management is an operations responsibility.
- Update Netty and all dependencies after reviewing release notes/CVEs.
- Run all Maven/Netty integration tests **and** security tests on Windows before deployment.
- Validate with a reverse proxy and realistic load; compare latency/RPS with v0.19 using equivalent options.

**Not implemented by v0.20**: native Netty TLS listener, automatic trusted-proxy resolution, cluster-wide rate limiting, CSRF tokens, WAF, external secrets manager, security certification.
