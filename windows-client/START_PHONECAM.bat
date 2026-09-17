@echo off
title PhoneCam Launcher
color 0B

REM ── Mode select ───────────────────────────────────
REM   No args            -> USB mode (needs ADB + cable)
REM   START_PHONECAM.bat 192.168.0.95 [extra args...]
REM                      -> WiFi mode, no ADB needed.
set "PHONE_IP="
echo(%1 | findstr /R "[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*" >nul
if not errorlevel 1 (
    set "PHONE_IP=%1"
)
REM NOTE: %* is immune to SHIFT, so extra args are forwarded as %2..%9 below.

echo.
echo  ===============================================
echo    PhoneCam - Phone Camera to OBS Virtual Cam
if defined PHONE_IP (
echo    Mode: WiFi [%PHONE_IP%]
) else (
echo    Mode: USB [ADB]
)
echo  ===============================================
echo.

REM ── Check Python ──────────────────────────────────
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Install from https://python.org
    echo         Make sure to check "Add Python to PATH" during install.
    pause
    exit /b 1
)

echo [OK] Python found.

REM ── Install dependencies ──────────────────────────
echo.
echo [Setup] Installing/checking Python packages...
pip install pyvirtualcam opencv-python numpy av --quiet --upgrade
if errorlevel 1 (
    echo [WARN] Some packages may not have installed correctly.
)
echo [OK] Packages ready.

REM ── Check ADB (USB mode only) ───────────────────
echo.
if defined PHONE_IP (
    echo [WiFi] Skipping ADB - connecting straight to %PHONE_IP%.
    goto :skip_adb
)
adb version >nul 2>&1
if errorlevel 1 (
    echo [WARN] ADB not found in PATH.
    echo        Download Platform Tools from:
    echo        https://developer.android.com/tools/releases/platform-tools
    echo        Then add the folder to your PATH environment variable.
    echo.
    echo [INFO] For cable-free streaming, use WiFi mode instead:
    echo        START_PHONECAM.bat ^<phone-wifi-ip^>
    echo        (The IP is shown in the PhoneCam app.)
    goto :skip_adb
)
echo [OK] ADB found.
echo.
echo [ADB] Connected devices:
adb devices
echo.
echo [ADB] Setting up port forwarding (phone port 8080 to localhost:8080)...
adb forward tcp:8080 tcp:8080
:skip_adb

REM ── Check OBS ─────────────────────────────────────
echo.
if exist "%ProgramFiles%\obs-studio\bin\64bit\obs64.exe" (
    echo [OK] OBS Studio found.
) else if exist "%ProgramFiles(x86)%\obs-studio\bin\32bit\obs32.exe" (
    echo [OK] OBS Studio found ^(32-bit^).
) else (
    echo [WARN] OBS Studio not detected at default location.
    echo        Make sure OBS Studio is installed: https://obsproject.com
    echo        The virtual camera driver is bundled with OBS.
)

echo.
echo  ──────────────────────────────────────────────
echo   NEXT STEPS:
echo   1. Open the PhoneCam app on your Android phone
echo   2. Tap "Start Streaming"
if defined PHONE_IP (
echo   3. This script connects to %PHONE_IP% over WiFi
) else (
echo   3. This script connects automatically over USB
)
echo   4. In OBS: Sources + -^> Video Capture Device
echo              -^> Select "OBS Virtual Camera"
echo  ──────────────────────────────────────────────
echo.

REM ── Launch client ─────────────────────────────────
echo [Launch] Starting PhoneCam client...
echo          (Press Ctrl+C to stop)
echo.

if defined PHONE_IP (
    python "%~dp0phonecam_client.py" --host %PHONE_IP% --no-adb %2 %3 %4 %5 %6 %7 %8 %9
) else (
    python "%~dp0phonecam_client.py" %*
)

echo.
if not defined PHONE_IP (
echo [ADB] Removing port forward...
adb forward --remove tcp:8080 2>nul
echo.
)
echo [Done] PhoneCam stopped. Press any key to exit.
pause >nul
