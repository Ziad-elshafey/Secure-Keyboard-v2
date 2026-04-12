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
set "APP_ID=dev.patrickgold.florisboard.debug"
set "APP_ACTIVITY=dev.patrickgold.florisboard.SettingsLauncherAlias"

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
call gradlew.bat :app:compileDebugKotlin --build-cache -q
if errorlevel 1 (
    echo [ERROR] Kotlin compilation failed
    exit /b 1
)
echo [OK] Compilation passed

echo [2/2] Assembling + installing latest debug APK...
call gradlew.bat :app:assembleDebug --build-cache -x lint -x test -q
if errorlevel 1 (
    echo [ERROR] APK assembly failed
    exit /b 1
)

set "EMU_SERIAL="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
    if "%%B"=="device" (
        set "CANDIDATE=%%A"
        if /I "!CANDIDATE:~0,9!"=="emulator-" (
            set "EMU_SERIAL=%%A"
            goto SERIAL_FOUND
        )
    )
)

:SERIAL_FOUND
if not defined EMU_SERIAL (
    echo [ERROR] No running emulator found for install
    exit /b 1
)

set "APK_PATH=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "!APK_PATH!" (
    echo [ERROR] APK not found: !APK_PATH!
    exit /b 1
)

echo [INFO] Installing on !EMU_SERIAL!
"%ADB%" -s !EMU_SERIAL! install -r -d "!APK_PATH!" >nul
if errorlevel 1 (
    echo [WARN] Normal install failed, retrying with test flag...
    "%ADB%" -s !EMU_SERIAL! install -r -d -t "!APK_PATH!" >nul
    if errorlevel 1 (
        echo [WARN] Install still failed ^(often INSTALL_FAILED_UPDATE_INCOMPATIBLE: different signing key^).
        echo [INFO] Uninstalling !APP_ID! and doing a clean install...
        "%ADB%" -s !EMU_SERIAL! uninstall !APP_ID! >nul 2>&1
        "%ADB%" -s !EMU_SERIAL! install -r -d -t "!APK_PATH!" >nul
        if errorlevel 1 (
            echo [ERROR] Install failed on !EMU_SERIAL! after uninstall retry
            exit /b 1
        )
    )
)

echo [INFO] Launching !APP_ID!...
"%ADB%" -s !EMU_SERIAL! shell am force-stop !APP_ID! >nul
"%ADB%" -s !EMU_SERIAL! shell am start -n !APP_ID!/!APP_ACTIVITY! >nul

echo.
echo [OK] Secure-Keyboard-v2 installed and launched successfully!
endlocal
