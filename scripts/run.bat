@echo off
setlocal
if not exist out mkdir out
set INPUT_FILE=%~1
set MEASURE=%~2
if "%INPUT_FILE%"=="" set INPUT_FILE=out\bench_1000000_peak_1000000.csv
if "%MEASURE%"=="" set MEASURE=on
java -cp out com.hftrading.app.Main --input "%INPUT_FILE%" --measure %MEASURE%
endlocal
