# Database & Redis: failure diagnostics

The original CI run completed all six FastFrame cases and Micronaut SELECT, then
stopped at Micronaut INSERT (`errors=11567`, `persisted=False`). That output does
**not** identify a root cause: an HTTP 4xx/5xx response, a status `0` transport
failure, and a committed INSERT whose HTTP response failed require different
fixes. Do not change SQL semantics, hide errors, or compare those RPS figures.

## Diagnostics added in this hotfix

On *any failed timed phase*, `benchmarks/db_redis.py` now:

1. Prints a breakdown of real `hey` HTTP status codes (including `0`).
2. Saves `diagnostics-<framework>-<case>-r<N>.json` with the HTTP counts,
   benchmark statistics, actual PostgreSQL/Redis verification values and the
   last 120 lines of the corresponding server log.
3. Saves `raw-<framework>-<case>-r<N>.csv.gz` with every request's measured
   latency and status; use `--keep-raw` to keep an uncompressed CSV instead.
4. Keeps the completed run marked invalid and exits nonzero. It does not
   count HTTP failures as successful persisted writes.
5. Uploads the JSON, raw CSV, server logs, and existing reports as a GitHub
   Actions artifact named `db-redis-smoke-diagnostics`, even if the CI step
   failed. The artifact has a seven-day retention policy.

Diagnostics are also saved if `hey` itself raises an exception or the
PostgreSQL side-effect verification cannot be completed.

## Reproduce only the failing scenario

First build the Micronaut benchmark application and make sure `docker`, `hey`,
PostgreSQL and Redis can run on the host. Then execute:

```bash
python3 benchmarks/db_redis.py \
  --frameworks micronaut \
  --cases insert \
  --duration 5s --warmup 0s \
  --concurrency 8 --repeats 1 \
  --keep-raw --stop-services
```

Open the generated `benchmarks/results/db-redis-*/diagnostics-micronaut-insert-r1.json`
and `micronaut-repeat-1.log` files. The failed CSV contains the status for
**each** request; use `status_counts` in `db-redis-results.csv` as a summary.

| Evidence | Interpretation to investigate |
|---|---|
| Mostly `500` | Micronaut exception / JDBC pool / PostgreSQL error in the server log |
| `400`, `404`, `405` or `415` | HTTP request formatting, routing, validation, content negotiation |
| `429` or `503` | Concurrency/admission limits, queue or worker saturation |
| `0` | Transport failure, timeout, reset or connection closure |
| Only `200`, but database row count differs | Commit-vs-response accounting, or verification contract |

These are diagnostic leads, **not** claimed explanations for the original
11,567 errors. Fix the actual error based on the recorded statuses and stack
trace. The runner will continue to reject any row with errors or a failed
persistent-side-effect check.

The CI runner uses 2-second phases for smoke tests; that is not sufficient to
establish statistically significant throughput comparisons.
