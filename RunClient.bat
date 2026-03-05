@echo off
title Camera Client - Remote Camera IP
echo ========================================
echo   Camera Client - Remote Camera IP
echo ========================================
echo.

:: Check if JAR exists, if not build it first
if not exist "%~dp0build\libs\camera-ip-1.0.0-client.jar" (
    echo [..] Building app for the first time...
    echo.
    call "%~dp0gradlew.bat" fatJarClient -p "%~dp0" --console=plain
    if %ERRORLEVEL% neq 0 (
        echo.
        echo [X] Build failed! Make sure Java JDK 17+ is installed.
        pause
        exit /b 1
    )
    echo.
    echo [OK] Build complete!
    echo.
)

java -jar "%~dp0build\libs\camera-ip-1.0.0-client.jar"
pause
