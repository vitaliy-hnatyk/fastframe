#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${FF_DB_URL:?FF_DB_URL must be set}"
mvn -B -ntp -pl demo-app -am package
java -cp demo-app/target/demo-app-0.22.0-SNAPSHOT.jar dev.fastframe.migrations.MigrationCli "${1:-demo-app/migrations}"
