# FastFrame 0.13 — Queue, Redis Jobs, Worker & Scheduler

## Modules

- `framework-queue`: `Jobs`, `JobMessage`, `JobWorker`, `InMemoryQueueStore`, `CronSchedule`, `JobScheduler`, zero runtime dependencies.
- `framework-queue-redis`: optional Redis queue adapter using Lettuce and atomic Lua scripts.
- `queue-demo`: standalone CLI with `dispatch`, `worker`, `scheduler`, `failures` commands.
- `demo-app`: POST `/api/jobs/demo`, HTTP 202 and an in-process **in-memory** worker. No Redis dependency in the HTTP demo.

## Java API

```java
QueueStore store = new InMemoryQueueStore(2048);
Jobs jobs = new Jobs(store);
UUID id = jobs.dispatch("mail.welcome", "user:42")
    .delay(Duration.ofSeconds(10)).maxAttempts(3).enqueue();
JobWorker worker = new JobWorker(store)
    .handle("mail.welcome", payload -> sendEmail(payload));
worker.start(Duration.ofMillis(200)); // independent thread, never a Netty event loop
```

Always enqueue a **job type + data** rather than executable objects. Workers must register the job type themselves; unknown types go to failed jobs. `QueueStore` is an explicit abstraction; in-memory jobs are lost on restart.

## PowerShell — Windows with Java 21 (Java 8 remains installed)

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\queue-smoke.ps1
.\scripts\run-demo.ps1
# In another PowerShell:
curl.exe -X POST http://localhost:8080/api/jobs/demo
```

After `queue-smoke.ps1` has built `queue-demo`, use `-SkipBuild` on all long-running processes to avoid concurrent Maven builds. For a **separate Redis worker and scheduler**, run Redis locally or remotely (TLS with `rediss://` outside trusted networks). Configure the Redis URI before launching each separate terminal:

```powershell
$env:FF_REDIS_URI = 'redis://127.0.0.1:6379'
$env:FF_QUEUE_PREFIX = 'fastframe:local:queue'
.\scripts\run-queue.ps1 -Command worker -SkipBuild   # terminal 1, long-running
.\scripts\run-queue.ps1 -Command scheduler -SkipBuild # terminal 2, long-running
.\scripts\run-queue.ps1 -Command dispatch -Payload 'hello' -SkipBuild # terminal 3
.\scripts\run-queue.ps1 -Command failures -SkipBuild
```

The **Redis demo CLI** works independently from the in-memory `/api/jobs/demo` endpoint. To publish from a production HTTP application to Redis, wire the `Jobs` instance to `RedisQueueStore` on a dedicated I/O executor rather than making synchronous Redis calls on Netty event loops.

## Reliability semantics / limitations

- Delivery is **at least once**. Handlers MUST be idempotent, e.g. deduplicate by business-event ID in the DB.
- Redis atomic Lua scripts claim due jobs with a **visibility lease** (default 60s), and fence stale acknowledgement tokens. Expired claims are redelivered when a worker next polls.
- A job running beyond the lease can be processed a second time; lease renewal / heartbeat is **not** implemented. Keep handlers shorter than the lease or extend the configured worker lease via code.
- Failed jobs retain type, payload and class name of error (not raw exception messages). Treat payloads as sensitive; configure Redis access restrictions and retention. No built-in failed-job pruning or replay commands yet.
- Retry: exponential backoff from 500ms capped at 30s, maximum attempts configurable per job. Workers never run inside Netty event loops.
- `JobScheduler` supports standard **5-field cron** (minute hour DOM month DOW, with `*`, comma, ranges, steps) and timezone via `ZoneId`. `daily("02:00", ...)` is available. Per-minute dedup is **per process only**; deploy exactly one scheduler per namespace. Distributed leader election is not implemented.
- Delayed jobs use Redis sorted sets and Lua; Redis persistence (AOF/RDB) determines crash durability. This is not an exactly-once transaction with application DB writes.
- Redis adapter uses **synchronous** Lettuce; only call it on worker threads. In-memory store is bounded by pending items, but failed job history is unbounded; use only for local/small test workloads.
- Before production: auth for queue operations, failure retention, leasing heartbeat, distributed scheduling lock, backpressure quotas, telemetry, failure injection, security review and real Redis integration load tests are required.

## Build & test

```
mvn -B -ntp -pl framework-queue,framework-queue-redis,queue-demo,demo-app -am clean verify
bash scripts/queue-smoke.sh
```

Redis integration test is optional, controlled by `FF_TEST_REDIS_URI`, and uses isolated test prefixes.
