@echo off
REM Auto-generated launcher for JavaToBatConverter.java
setlocal
cd /d "%~dp0"

echo Compiling JavaToBatConverter.java ...
javac -d . "JavaToBatConverter.java"
if errorlevel 1 (
    echo.
    echo Compilation failed.
    pause
    exit /b 1
)

start "" javaw JavaToBatConverter
endlocal
