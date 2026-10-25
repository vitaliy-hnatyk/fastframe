# FastFrame v0.17 — Service Providers, Bootstrap and Events

## Overview

- `framework-application`: `FastFrameApp.boot(config, providers...)`, `ServiceProvider.register`, `ServiceProvider.boot`, `AppContext.bind/require`, resource shutdown hooks.
- `framework-events`: exact-class typed listeners using `EventBus.on`, `publish` and `publishAsync`.
- No reflection or classpath scanning. Providers are added explicitly in a stable order.
- All providers **register** before any provider **boots**; services may be resolved during `boot()`.
- Services are singleton bindings by `Class<T>`; duplicate registration fails immediately.
- Event handlers execute on the publishing thread unless `publishAsync` is used with a supplied executor. Avoid blocking Netty's event loop.

## Example

```java
record UserCreated(long id) {}

final class BillingProvider implements ServiceProvider {
    @Override public void register(AppContext ctx) {
        ctx.bind(BillingService.class, new BillingService());
    }
    @Override public void boot(AppContext ctx) {
        AutoCloseable listener = ctx.events().on(UserCreated.class,
                event -> ctx.require(BillingService.class).onUserCreated(event.id()));
        ctx.onShutdown(listener);
    }
}

try (AppContext app = FastFrameApp.boot(Config.load(), new BillingProvider())) {
    app.events().publish(new UserCreated(42));
    Router router = app.router();
}
```

### Laravel mapping

| Laravel | FastFrame |
| --- | --- |
| `ServiceProvider::register()` | `ServiceProvider.register(AppContext)` |
| `ServiceProvider::boot()` | `ServiceProvider.boot(AppContext)` |
| `app()->singleton()` | `AppContext.bind(Class<T>, value)` |
| `app()->make()` | `AppContext.require(Class<T>)` |
| `Event::listen()` | `EventBus.on(Class<T>, listener)` |
| `Event::dispatch()` | `EventBus.publish(event)` |

## Windows (Java 21, Java 8 remains installed)

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\bootstrap-smoke.ps1
.\scripts\ff.ps1 make:provider Billing
.\scripts\ff.ps1 make:listener UserCreated
.\scripts\run-demo.ps1
```

The demo uses `DemoProviders` for HTTP, database, cache, jobs and storage. Add generated providers explicitly to `FastFrameApp.boot(...)`, and subscribe listeners during `boot()`.

## Guarantees and limitations

- `AppContext` bootstrap and mutable registry are single-threaded. Resolve services at startup; don't mutate the registry from HTTP handlers.
- An event is delivered only to listeners registered for **its exact runtime class**; superclass/interface polymorphism is not implemented.
- Synchronous event listener exceptions propagate and stop further synchronous listeners. Async exceptions complete the future exceptionally.
- `publishAsync` depends on an executor supplied and managed by the application; there is no hidden thread pool.
- Shutdown hooks are called in reverse registration order; bootstrap failure attempts cleanup. If cleanup fails during initialization, the cleanup error is attached as suppressed.
- This is **not** a general-purpose compile-time DI replacement. Existing `@Inject` generated controllers remain independent.
- Do not register untrusted plugins or treat the service registry as a security boundary.

## Tests

Linux/macOS: `bash scripts/bootstrap-smoke.sh` and `bash scripts/cli-smoke.sh`.
Windows: `.\\scripts\\bootstrap-smoke.ps1`.
Maven: `mvn -B -ntp -pl demo-app -am clean verify`.
