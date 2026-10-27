#!/usr/bin/env bash
# FastFrame v0.22: 60-minute RSS/heap/CPU/GC/errors soak test.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 benchmarks/soak.py "$@"
