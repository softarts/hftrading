@rem Self-bootstrapping Gradle wrapper for Windows.
@rem Downloads Gradle 8.8 on first run; cached in %USERPROFILE%\.gradle\wrapper\dists.
@rem Usage: gradlew.bat <task>   e.g.  gradlew.bat build

@echo off
setlocal enabledelayedexpansion

set GRADLE_VERSION=8.8
set GRADLE_DIST=gradle-%GRADLE_VERSION%-bin
set GRADLE_CACHE=%USERPROFILE%\.gradle\wrapper\dists\%GRADLE_DIST%
set GRADLE_ZIP=%GRADLE_CACHE%\%GRADLE_DIST%.zip
set GRADLE_BIN=%GRADLE_CACHE%\gradle-%GRADLE_VERSION%\bin\gradle.bat
set GRADLE_URL=https://services.gradle.org/distributions/%GRADLE_DIST%.zip

if exist "%GRADLE_BIN%" goto :run

echo [gradlew] Gradle %GRADLE_VERSION% not found in cache.
echo [gradlew] Downloading from %GRADLE_URL% ...
if not exist "%GRADLE_CACHE%" mkdir "%GRADLE_CACHE%"

powershell -NoProfile -Command ^
  "try { Invoke-WebRequest -Uri '%GRADLE_URL%' -OutFile '%GRADLE_ZIP%' -UseBasicParsing } catch { Write-Error $_; exit 1 }"
if errorlevel 1 (
    echo [gradlew] ERROR: Download failed. Check your internet connection.
    exit /b 1
)

echo [gradlew] Extracting ...
powershell -NoProfile -Command ^
  "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%GRADLE_CACHE%' -Force"
if errorlevel 1 (
    echo [gradlew] ERROR: Extraction failed.
    exit /b 1
)

echo [gradlew] Gradle %GRADLE_VERSION% ready.

:run
"%GRADLE_BIN%" %*
