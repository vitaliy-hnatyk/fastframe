# Laravel 13 benchmark fixture

This directory contains only the **benchmark route and bootstrap overlays**. The actual
Laravel application and dependencies are installed by `python3 benchmarks/setup_laravel.py`
into `benchmarks/laravel-app/runtime/` (Git-ignored). Do not commit `runtime/` or vendor.

`routes/bench.php` uses Laravel's real routing, JSON parsing and response helpers.
`bootstrap/app.php` opts out of the default `web` middleware stack solely for these four
stateless benchmark routes. The fixture has no database access or external services.

See [../README.md](../README.md) for `artisan serve` and Octane/FrankenPHP setup.

**Additional database/cache benchmark:** `../DATABASE_REDIS.md` documents PostgreSQL 16, Redis 7, and Predis installation.
