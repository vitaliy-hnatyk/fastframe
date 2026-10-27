#!/usr/bin/env bash
# Run fixed-arrival load (requires k6; see TARGET_RPS.md).
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 benchmarks/target_rps.py "$@"
