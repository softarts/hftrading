#!/usr/bin/env bash
# run.sh — Launch the HFT trading system with tuned JVM flags
# Usage: ./scripts/run.sh [--config <path>] [--input <csv>] [args...]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

JAR="$PROJECT_DIR/build/libs/hftrading-1.0-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
    echo "Fat JAR not found: $JAR"
    echo "Run: ./gradlew jar"
    exit 1
fi

exec java \
    "@$SCRIPT_DIR/jvm.flags" \
    -jar "$JAR" \
    "$@"
