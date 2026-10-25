#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp -pl framework-testing,demo-app -am clean verify
printf 'FastFrame v0.19 feature testing smoke: PASS\n'
