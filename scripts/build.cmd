@echo off
setlocal
if not exist out mkdir out
set SOURCES_FILE=%TEMP%\hftrading_sources_%RANDOM%%RANDOM%.txt
set TEST_SOURCES_FILE=%TEMP%\hftrading_test_sources_%RANDOM%%RANDOM%.txt

del "%SOURCES_FILE%" >nul 2>&1
del "%TEST_SOURCES_FILE%" >nul 2>&1

break > "%SOURCES_FILE%"
for /r src\main\java %%f in (*.java) do @echo %%f>> "%SOURCES_FILE%"
javac -d out @"%SOURCES_FILE%"
if errorlevel 1 goto :build_failed

del "%SOURCES_FILE%" >nul 2>&1

if exist src\test\java (
    break > "%TEST_SOURCES_FILE%"
    for /r src\test\java %%f in (*.java) do @echo %%f>> "%TEST_SOURCES_FILE%"
    for %%a in ("%TEST_SOURCES_FILE%") do set TEST_FILE_SIZE=%%~za
    if not "!TEST_FILE_SIZE!"=="0" (
        javac -d out -cp out @"%TEST_SOURCES_FILE%"
        if errorlevel 1 goto :build_failed
        powershell -NoProfile -Command "$tests = Get-ChildItem -Path 'src/test/java' -Recurse -Filter '*Test.java'; $root = (Resolve-Path 'src/test/java').Path; foreach ($test in $tests) { $class = $test.FullName.Substring($root.Length + 1) -replace '\\', '.' -replace '\.java$', ''; Write-Host ('Running ' + $class); java -cp 'out' $class; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }"
        if errorlevel 1 goto :build_failed
    )
    del "%TEST_SOURCES_FILE%" >nul 2>&1
)

echo Compilation OK
exit /b 0

:build_failed
set ERR=%ERRORLEVEL%
del "%SOURCES_FILE%" >nul 2>&1
del "%TEST_SOURCES_FILE%" >nul 2>&1
echo BUILD FAILED
exit /b %ERR%
