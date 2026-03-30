@echo off
REM Ultra-fast build - Only Kotlin compilation + install, no lint/test/full build
REM Usage: super_fast.bat
REM Use when you only changed Kotlin code (no resources/manifest)

setlocal enabledelayedexpansion
echo [SUPER FAST] Secure-Keyboard-v2 code-only build...
cd /d "%~dp0"

REM ── Ensure emulator is running ──
set "EMULATOR=%LOCALAPPDATA%\Android\sdk\emulator\emulator.exe"
set "ADB=%LOCALAPPDATA%\Android\sdk\platform-tools\adb.exe"
set "AVD_NAME=Medium_Phone_API_35"
set "DEBUG_PACKAGE=dev.patrickgold.florisboard.debug"
set "INSTALL_LOG=%TEMP%\secure-keyboard-v2-install.log"

"%ADB%" devices 2>nul | findstr /R "emulator-.*device" >nul 2>&1
if errorlevel 1 (
    echo [INFO] No emulator detected — launching %AVD_NAME%...
    start "" "%EMULATOR%" -avd %AVD_NAME% -no-snapshot-load -dns-server 8.8.8.8
    echo [INFO] Waiting for emulator to boot...
    "%ADB%" wait-for-device
    :WAIT_BOOT
    for /f "tokens=*" %%A in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%A
    if not "!BOOT!"=="1" (
        timeout /t 2 /nobreak >nul
        goto WAIT_BOOT
    )
    echo [OK] Emulator is ready
) else (
    echo [OK] Emulator already running
)

echo.
echo [1/2] Compiling Kotlin...
call gradlew.bat :app:compileDebugKotlin --daemon --build-cache -q
if errorlevel 1 (
    echo [ERROR] Kotlin compilation failed
    exit /b 1
)
echo [OK] Compilation passed

echo [2/2] Installing APK...
call gradlew.bat installDebug --daemon --build-cache -x lint -x test -q > "%INSTALL_LOG%" 2>&1
set "INSTALL_EXIT=!errorlevel!"
type "%INSTALL_LOG%"
if not "!INSTALL_EXIT!"=="0" (
    findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%INSTALL_LOG%" >nul 2>&1
    if not errorlevel 1 (
        echo [INFO] Conflicting debug signature detected for %DEBUG_PACKAGE% - uninstalling and retrying...
        "%ADB%" uninstall "%DEBUG_PACKAGE%" >nul 2>&1
        call gradlew.bat installDebug --daemon --build-cache -x lint -x test -q > "%INSTALL_LOG%" 2>&1
        set "INSTALL_EXIT=!errorlevel!"
        type "%INSTALL_LOG%"
    )
)
del "%INSTALL_LOG%" >nul 2>&1
if not "!INSTALL_EXIT!"=="0" (
    echo [ERROR] Install failed
    exit /b 1
)

echo.
echo [OK] Secure-Keyboard-v2 installed successfully!
endlocal
