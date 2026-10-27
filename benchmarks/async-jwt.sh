#!/usr/bin/env bash
# Real upstream HTTP wait and signed JWT comparison; shell wrapper for macOS/Linux.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 benchmarks/async_jwt.py "$@"
