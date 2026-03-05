@echo off
title Camera Client - Remote Camera IP
echo ========================================
echo   Camera Client - Remote Camera IP
echo ========================================
echo.

:: Move to script directory
pushd "%~dp0"

:: Check if JAR exists, if not build it first
if not exist "build\libs\camera-ip-1.0.0-client.jar" (
    echo [..] Building app for the first time...
    echo.
    call gradlew.bat fatJarClient
    if %ERRORLEVEL% neq 0 (
        echo.
        echo [X] Build failed! Make sure Java JDK 17+ is installed.
        popd
        pause
        exit /b 1
    )
    echo.
    echo [OK] Build complete!
    echo.
)

java -jar "build\libs\camera-ip-1.0.0-client.jar"
popd
pause
