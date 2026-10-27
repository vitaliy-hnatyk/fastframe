#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
include_laravel=false
laravel_server=octane
while (($#)); do
  case "$1" in
    --with-laravel) include_laravel=true ;;
    --laravel-server) shift; laravel_server="${1:?--laravel-server requires a value}" ;;
    *) echo "Unknown build.sh option: $1" >&2; exit 2 ;;
  esac
  shift
done
command -v mvn >/dev/null || { echo 'Maven 3 is required' >&2; exit 1; }
mvn -B -ntp -pl benchmarks/fastframe-app -am clean package
mvn -B -ntp -f benchmarks/micronaut-app/pom.xml clean package

if "$include_laravel"; then
  if [[ "$laravel_server" == octane ]]; then
    python3 benchmarks/setup_laravel.py --with-octane
  elif [[ "$laravel_server" == artisan ]]; then
    python3 benchmarks/setup_laravel.py
  else
    echo 'Laravel mode must be artisan or octane' >&2; exit 2
  fi
fi
