#!/usr/bin/env bash
# Real PostgreSQL/Redis benchmark. Requires Docker Desktop (Mac), Docker Engine (Linux).
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 benchmarks/db_redis.py "$@"
