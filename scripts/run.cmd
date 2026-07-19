@echo off
REM run.cmd -- Launch the HFT trading system (Windows)
REM
REM Usage:
REM   scripts\run.cmd                                       (direct mode, default config)
REM   scripts\run.cmd --config config\aeron.properties     (full Aeron pipeline)
REM   scripts\run.cmd --config config\default.properties   (explicit direct mode)
REM
REM Requires: gradlew.bat build   (produces the fat JAR first)

SETLOCAL

SET "SCRIPT_DIR=%~dp0"
SET "PROJECT_DIR=%SCRIPT_DIR%.."
SET "JAR=%PROJECT_DIR%\build\libs\hftrading-1.0-SNAPSHOT.jar"
SET "FLAGS=%SCRIPT_DIR%jvm.flags"
SET "DEFAULT_CONFIG=%PROJECT_DIR%\config\default.properties"

IF NOT EXIST "%JAR%" (
    echo [run] Fat JAR not found: %JAR%
    echo [run] Build first:  gradlew.bat build
    EXIT /B 1
)

IF NOT EXIST "%FLAGS%" (
    echo [run] JVM flags file not found: %FLAGS%
    EXIT /B 1
)

SET "DATA_CSV=%PROJECT_DIR%\data\bench_1000000_peak_1000000.csv"
SET "OUT_CSV=%PROJECT_DIR%\out\bench_1m.csv"
IF NOT EXIST "%DATA_CSV%" (
    IF NOT EXIST "%OUT_CSV%" (
        echo [run] WARNING: Benchmark data file not found at %DATA_CSV% or %OUT_CSV%.
        echo [run] Generate it:  gradlew.bat generateTestData
        echo.
    )
)

echo [run] Starting HFT system...
echo [run] JAR: %JAR%

IF "%~1"=="" (
    echo [run] Config: %DEFAULT_CONFIG%
    echo.
    java @"%FLAGS%" -jar "%JAR%" --config "%DEFAULT_CONFIG%"
) ELSE (
    echo [run] Args: %*
    echo.
    java @"%FLAGS%" -jar "%JAR%" %*
)
