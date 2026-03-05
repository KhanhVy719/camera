@echo off
echo ========================================
echo   Remote Camera IP - Setup
echo ========================================
echo.

:: Check Java
java -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [X] Java not found! Please install JDK 17+
    echo     https://adoptium.net/
    pause
    exit /b 1
)
echo [OK] Java found

:: Check Gradle wrapper
if not exist "gradlew.bat" (
    echo [..] Generating Gradle wrapper...
    where gradle >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo [X] Gradle not found. Downloading wrapper manually...
        echo     Please install Gradle from https://gradle.org/install/
        echo     Or run: choco install gradle
        pause
        exit /b 1
    )
    call gradle wrapper --gradle-version 8.5
)
echo [OK] Gradle wrapper ready

:: Build project
echo.
echo [..] Building project (downloading dependencies)...
call gradlew.bat build -x test
if %ERRORLEVEL% neq 0 (
    echo [X] Build failed!
    pause
    exit /b 1
)
echo [OK] Build successful!

:: Check Python (optional, for virtual camera)
echo.
echo --- Virtual Camera Setup (optional) ---
python --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [NOTE] Python not found. Virtual Camera feature won't work.
    echo        Install Python 3 from https://python.org if needed.
) else (
    echo [OK] Python found
    echo [..] Installing pyvirtualcam...
    pip install pyvirtualcam opencv-python numpy >nul 2>&1
    echo [OK] Python dependencies installed
    echo.
    echo [NOTE] For Virtual Camera, you also need OBS Studio installed:
    echo        https://obsproject.com/download
)

echo.
echo ========================================
echo   Setup Complete!
echo ========================================
echo.
echo   Run Camera Host:   gradlew runHost
echo   Run Camera Client:  gradlew runClient
echo.
echo   For internet use: install ZeroTier
echo   https://www.zerotier.com/download/
echo.
pause
