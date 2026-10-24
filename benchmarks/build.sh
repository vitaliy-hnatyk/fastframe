#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null || { echo 'Maven 3 is required' >&2; exit 1; }
mvn -B -ntp -pl benchmarks/fastframe-app -am clean package
mvn -B -ntp -f benchmarks/micronaut-app/pom.xml clean package
