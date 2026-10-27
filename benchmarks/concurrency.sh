#!/usr/bin/env bash
# From project root: bash benchmarks/concurrency.sh [runner options]
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 benchmarks/concurrency.py "$@"
