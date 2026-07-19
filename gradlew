#!/bin/sh
# Self-bootstrapping Gradle wrapper for Linux / macOS.
# Downloads Gradle 8.8 on first run; cached in ~/.gradle/wrapper/dists.
# Usage: ./gradlew <task>   e.g.  ./gradlew build

GRADLE_VERSION=8.8
GRADLE_DIST="gradle-${GRADLE_VERSION}-bin"
GRADLE_CACHE="${HOME}/.gradle/wrapper/dists/${GRADLE_DIST}"
GRADLE_ZIP="${GRADLE_CACHE}/${GRADLE_DIST}.zip"
GRADLE_BIN="${GRADLE_CACHE}/gradle-${GRADLE_VERSION}/bin/gradle"
GRADLE_URL="https://services.gradle.org/distributions/${GRADLE_DIST}.zip"

if [ ! -f "$GRADLE_BIN" ]; then
    echo "[gradlew] Gradle ${GRADLE_VERSION} not found in cache."
    echo "[gradlew] Downloading from ${GRADLE_URL} ..."
    mkdir -p "$GRADLE_CACHE"

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$GRADLE_URL" -o "$GRADLE_ZIP" || { echo "[gradlew] ERROR: Download failed."; exit 1; }
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$GRADLE_URL" -O "$GRADLE_ZIP" || { echo "[gradlew] ERROR: Download failed."; exit 1; }
    else
        echo "[gradlew] ERROR: Neither curl nor wget found. Install one and retry."
        exit 1
    fi

    echo "[gradlew] Extracting ..."
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_CACHE" || { echo "[gradlew] ERROR: Extraction failed."; exit 1; }
    chmod +x "$GRADLE_BIN"
    echo "[gradlew] Gradle ${GRADLE_VERSION} ready."
fi

exec "$GRADLE_BIN" "$@"
