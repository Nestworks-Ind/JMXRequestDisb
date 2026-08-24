@echo off
REM Auto-generated launcher for ThinkTimePacingCalculator.java
setlocal
cd /d "%~dp0"

echo Compiling ThinkTimePacingCalculator.java ...
javac -d . "ThinkTimePacingCalculator.java"
if errorlevel 1 (
    echo.
    echo Compilation failed.
    pause
    exit /b 1
)

java ThinkTimePacingCalculator
pause
endlocal
