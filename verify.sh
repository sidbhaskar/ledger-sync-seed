#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Uses Gradle to handle the full classpath (including DynamoDB SDK).
# After the first run, Gradle caches dependencies locally — no repeated network needed.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> running selfCheck via Gradle"
./gradlew selfCheck "$@"
