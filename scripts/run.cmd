@echo off
REM run.cmd — Launch the HFT trading system with tuned JVM flags (Windows)
REM Usage: scripts\run.cmd [--config <path>] [--input <csv>] [args...]

SET "SCRIPT_DIR=%~dp0"
SET "PROJECT_DIR=%SCRIPT_DIR%.."
SET "JAR=%PROJECT_DIR%\build\libs\hftrading-1.0-SNAPSHOT.jar"

IF NOT EXIST "%JAR%" (
    echo Fat JAR not found: %JAR%
    echo Run: gradlew.bat jar
    EXIT /B 1
)

java ^
    "@%SCRIPT_DIR%jvm.flags" ^
    -jar "%JAR%" ^
    %*
