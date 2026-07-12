@echo off
setlocal
call scripts\build.cmd
if errorlevel 1 exit /b 1
if not exist out mkdir out
set MAX_ORDERS=%~1
set PEAK_ORDERS=%~2
set OUT_FILE=%~3
set PRODUCTS=%~4
if "%MAX_ORDERS%"=="" set MAX_ORDERS=1000000
if "%PEAK_ORDERS%"=="" set PEAK_ORDERS=%MAX_ORDERS%
if "%OUT_FILE%"=="" set OUT_FILE=out\bench_%MAX_ORDERS%_peak_%PEAK_ORDERS%.csv
if "%PRODUCTS%"=="" set PRODUCTS=10
echo "creating test data with %MAX_ORDERS% orders, %PEAK_ORDERS% peak orders, %PRODUCTS% products, filename: %OUT_FILE%"
java -cp out com.hftrading.util.TestDataTool --output "%OUT_FILE%" --orders %MAX_ORDERS% --peak %PEAK_ORDERS% --products %PRODUCTS% --seed 1
endlocal
