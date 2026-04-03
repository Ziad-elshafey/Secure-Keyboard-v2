param(
    [string]$UsernamePrefix,
    [string]$Password,
    [string]$TestClass,
    [string]$TestMethod,
    [string]$AvdName = "Medium_Phone_API_35",
    [switch]$StartEmulator,
    [switch]$CleanNative,
    [switch]$KeepInstalledApp,
    [switch]$NoBuild,
    [switch]$ListDevices
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-LocalProperty {
    param(
        [string]$Path,
        [string]$Key
    )

    if (-not (Test-Path $Path)) {
        return $null
    }

    foreach ($line in Get-Content $Path) {
        if ($line -match '^\s*#') { continue }
        $parts = $line -split '=', 2
        if ($parts.Length -ne 2) { continue }
        if ($parts[0].Trim() -eq $Key) {
            return $parts[1].Trim()
        }
    }

    return $null
}

function Resolve-ConfigValue {
    param(
        [string]$ExplicitValue,
        [string]$EnvName,
        [string]$LocalPropertyKey
    )

    if ($ExplicitValue) { return $ExplicitValue }

    $envValue = [Environment]::GetEnvironmentVariable($EnvName)
    if (-not [string]::IsNullOrWhiteSpace($envValue)) {
        return $envValue
    }

    $localValue = Get-LocalProperty -Path $script:LocalPropertiesPath -Key $LocalPropertyKey
    if (-not [string]::IsNullOrWhiteSpace($localValue)) {
        return $localValue
    }

    return $null
}

function Get-AdbDevices {
    $adbOutput = & $script:AdbPath devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed. Resolved adb path: $script:AdbPath"
    }

    $devices = @()
    foreach ($line in ($adbOutput | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $columns = $line -split '\s+'
        if ($columns.Length -lt 2) { continue }
        $devices += [PSCustomObject]@{
            Serial = $columns[0]
            State = $columns[1]
        }
    }
    return $devices
}

function Resolve-AndroidSdkRoot {
    $sdkFromLocalProperties = Get-LocalProperty -Path $script:LocalPropertiesPath -Key "sdk.dir"
    if (-not [string]::IsNullOrWhiteSpace($sdkFromLocalProperties)) {
        $normalized = $sdkFromLocalProperties -replace '\\\\', '\'
        if (Test-Path $normalized) {
            return $normalized
        }
    }

    $envCandidates = @(
        [Environment]::GetEnvironmentVariable("ANDROID_SDK_ROOT"),
        [Environment]::GetEnvironmentVariable("ANDROID_HOME")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $envCandidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $localAppDataSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $localAppDataSdk) {
        return $localAppDataSdk
    }

    return $null
}

function Wait-ForEmulatorBoot {
    param([string]$Serial)

    & $script:AdbPath -s $Serial wait-for-device | Out-Null
    while ($true) {
        $boot = (& $script:AdbPath -s $Serial shell getprop sys.boot_completed 2>$null).Trim()
        if ($boot -eq "1") {
            break
        }
        Start-Sleep -Seconds 2
    }
}

function Reset-TestPackages {
    param(
        [string[]]$DeviceSerials,
        [string[]]$Packages
    )

    foreach ($serial in $DeviceSerials) {
        foreach ($package in $Packages) {
            $installed = & $script:AdbPath -s $serial shell pm path $package 2>$null
            if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($installed | Out-String).Trim())) {
                continue
            }

            Write-Host "Removing $package from $serial" -ForegroundColor DarkYellow
            & $script:AdbPath -s $serial uninstall $package | Out-Null
        }
    }
}

function Install-Apk {
    param(
        [string]$Serial,
        [string]$ApkPath,
        [switch]$AllowTestPackage
    )

    if (-not (Test-Path $ApkPath)) {
        throw "APK not found: $ApkPath"
    }

    $args = @("-s", $Serial, "install", "-r", "-d")
    if ($AllowTestPackage) {
        $args += "-t"
    }
    $args += $ApkPath

    $output = & $script:AdbPath @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install APK '$ApkPath':`n$output"
    }
}

function Resolve-UserHome {
    $profile = [Environment]::GetFolderPath("UserProfile")
    if (-not [string]::IsNullOrWhiteSpace($profile) -and (Test-Path $profile)) {
        return $profile
    }

    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE) -and (Test-Path $env:USERPROFILE)) {
        return $env:USERPROFILE
    }

    if (-not [string]::IsNullOrWhiteSpace($env:HOME) -and (Test-Path $env:HOME)) {
        return $env:HOME
    }

    throw "Could not resolve the current user home directory for Rust."
}

function Resolve-ExecutablePath {
    param(
        [string]$Name,
        [string]$FallbackPath
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command -and -not [string]::IsNullOrWhiteSpace($command.Source)) {
        return $command.Source
    }

    if (-not [string]::IsNullOrWhiteSpace($FallbackPath) -and (Test-Path $FallbackPath)) {
        return $FallbackPath
    }

    return $null
}

function Resolve-HomeFromExecutable {
    param([string]$ExecutablePath)

    if ([string]::IsNullOrWhiteSpace($ExecutablePath)) {
        return $null
    }

    $binDir = Split-Path -Parent $ExecutablePath
    if ([string]::IsNullOrWhiteSpace($binDir)) {
        return $null
    }

    return Split-Path -Parent $binDir
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$LocalPropertiesPath = Join-Path $ProjectRoot "local.properties"

Write-Step "Resolving configuration"

$resolvedPrefix = Resolve-ConfigValue -ExplicitValue $UsernamePrefix -EnvName "SECURE_SERVER_IT_USERNAME_PREFIX" -LocalPropertyKey "secure.server.it.username.prefix"
$resolvedPassword = Resolve-ConfigValue -ExplicitValue $Password -EnvName "SECURE_SERVER_IT_PASSWORD" -LocalPropertyKey "secure.server.it.password"

if ([string]::IsNullOrWhiteSpace($resolvedPrefix)) {
    throw "Missing username prefix. Pass -UsernamePrefix, set SECURE_SERVER_IT_USERNAME_PREFIX, or add secure.server.it.username.prefix to local.properties."
}

if ([string]::IsNullOrWhiteSpace($resolvedPassword)) {
    throw "Missing password. Pass -Password, set SECURE_SERVER_IT_PASSWORD, or add secure.server.it.password to local.properties."
}

$sdkRoot = Resolve-AndroidSdkRoot
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    throw "Could not resolve Android SDK path. Set sdk.dir in local.properties, ANDROID_SDK_ROOT, or install the SDK under %LOCALAPPDATA%\Android\Sdk."
}

$AdbPath = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $AdbPath)) {
    throw "adb.exe not found at $AdbPath"
}

$EmulatorPath = Join-Path $sdkRoot "emulator\emulator.exe"

$env:SECURE_SERVER_IT = "true"
$env:SECURE_SERVER_IT_USERNAME_PREFIX = $resolvedPrefix
$env:SECURE_SERVER_IT_PASSWORD = $resolvedPassword

$userHome = Resolve-UserHome
$defaultCargoHome = Join-Path $userHome ".cargo"
$rustupPath = Resolve-ExecutablePath -Name "rustup" -FallbackPath (Join-Path $defaultCargoHome "bin\rustup.exe")
$cargoPath = Resolve-ExecutablePath -Name "cargo" -FallbackPath (Join-Path $defaultCargoHome "bin\cargo.exe")
$cargoHome = [Environment]::GetEnvironmentVariable("CARGO_HOME")
if ([string]::IsNullOrWhiteSpace($cargoHome)) {
    $cargoHome = Resolve-HomeFromExecutable -ExecutablePath $cargoPath
}
if ([string]::IsNullOrWhiteSpace($cargoHome)) {
    $cargoHome = $defaultCargoHome
}
$rustupHome = [Environment]::GetEnvironmentVariable("RUSTUP_HOME")
if ([string]::IsNullOrWhiteSpace($rustupHome)) {
    $cargoParent = Split-Path -Parent $cargoHome
    if (-not [string]::IsNullOrWhiteSpace($cargoParent)) {
        $rustupHome = Join-Path $cargoParent ".rustup"
    }
}
if ([string]::IsNullOrWhiteSpace($rustupHome)) {
    $rustupHome = Join-Path $userHome ".rustup"
}
$toolchainUserHome = Split-Path -Parent $cargoHome
if ([string]::IsNullOrWhiteSpace($toolchainUserHome) -or -not (Test-Path $toolchainUserHome)) {
    $toolchainUserHome = $userHome
}
New-Item -ItemType Directory -Force (Join-Path $ProjectRoot ".android-local") | Out-Null

if ([string]::IsNullOrWhiteSpace($rustupPath)) {
    throw "rustup.exe not found. Install Rust or ensure rustup is on PATH."
}

if ([string]::IsNullOrWhiteSpace($cargoPath)) {
    throw "cargo.exe not found. Install Rust or ensure cargo is on PATH."
}

$gradleHome = Join-Path $ProjectRoot ".gradle-user-home"
New-Item -ItemType Directory -Force $gradleHome | Out-Null

# Clear deprecated/conflicting Android location variables inherited from the parent shell.
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
Remove-Item Env:ANDROID_HOME -ErrorAction SilentlyContinue
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue

$currentAndroidUserHome = [Environment]::GetEnvironmentVariable("ANDROID_USER_HOME")
if ([string]::IsNullOrWhiteSpace($currentAndroidUserHome)) {
    $currentAndroidUserHome = Join-Path $ProjectRoot ".android-local"
    $env:ANDROID_USER_HOME = $currentAndroidUserHome
    Write-Host "Using fallback ANDROID_USER_HOME: $currentAndroidUserHome" -ForegroundColor DarkGray
} else {
    Write-Host "Using existing ANDROID_USER_HOME: $currentAndroidUserHome" -ForegroundColor DarkGray
}
$env:GRADLE_USER_HOME = $gradleHome
$env:USERPROFILE = $toolchainUserHome
$env:HOME = $toolchainUserHome
$env:CARGO_HOME = $cargoHome
$env:RUSTUP_HOME = $rustupHome
$env:RUSTUP_TOOLCHAIN = "stable-x86_64-pc-windows-msvc"

Write-Step "Checking Rust toolchain"
& $rustupPath show active-toolchain | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "rustup has no active toolchain. Run 'rustup default stable' once and rerun."
}
& $rustupPath target add aarch64-linux-android | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Failed to prepare Rust Android target aarch64-linux-android."
}

Write-Step "Checking connected devices"

$devices = Get-AdbDevices
if ($ListDevices) {
    if ($devices.Count -eq 0) {
        Write-Host "No adb devices found."
    } else {
        $devices | Format-Table -AutoSize
    }
}

$onlineDevices = @($devices | Where-Object { $_.State -eq "device" })
if ($onlineDevices.Count -eq 0 -and $StartEmulator) {
    if (-not (Test-Path $EmulatorPath)) {
        throw "emulator.exe not found at $EmulatorPath"
    }

    Write-Step "Starting emulator $AvdName"
    Start-Process -FilePath $EmulatorPath -ArgumentList @("-avd", $AvdName, "-no-snapshot-load", "-dns-server", "8.8.8.8")
    Start-Sleep -Seconds 5

    $emulatorDevices = @()
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $emulatorDevices = @(Get-AdbDevices | Where-Object { $_.State -eq "device" -and $_.Serial -like "emulator-*" })
        if ($emulatorDevices.Count -gt 0) {
            break
        }
        Start-Sleep -Seconds 2
    }

    if ($emulatorDevices.Count -eq 0) {
        throw "Emulator $AvdName did not appear in adb devices."
    }

    Wait-ForEmulatorBoot -Serial $emulatorDevices[0].Serial
    $onlineDevices = @($emulatorDevices)
}

if ($onlineDevices.Count -eq 0) {
    throw "No online emulator/device found. Start an emulator or connect a device, then rerun."
}

$selectedSerial = $onlineDevices[0].Serial
& $AdbPath -s $selectedSerial wait-for-device | Out-Null
$env:ANDROID_SERIAL = $selectedSerial

if ([string]::IsNullOrWhiteSpace($TestClass)) {
    $TestClass = @(
        "dev.patrickgold.florisboard.secure.integration.SecureServerIntegrationTest",
        "dev.patrickgold.florisboard.secure.integration.SecureServerInfrastructureIntegrationTest"
    ) -join ","
}

$classArgument = $TestClass
if (-not [string]::IsNullOrWhiteSpace($TestMethod)) {
    if ($TestClass.Contains(",")) {
        throw "When -TestMethod is used, pass a single -TestClass value."
    }
    $classArgument = "$TestClass#$TestMethod"
}

$gradleArgs = @(
    ":app:assembleDebug",
    ":app:assembleDebugAndroidTest"
)

if ($NoBuild) {
    $gradleArgs += "-x"
    $gradleArgs += "assembleDebug"
    $gradleArgs += "-x"
    $gradleArgs += "assembleDebugAndroidTest"
}

if ($CleanNative) {
    Write-Step "Cleaning native intermediates"
    Remove-Item -Recurse -Force "lib\native\.cxx" -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force "lib\native\src\main\rust\target" -ErrorAction SilentlyContinue
}

if (-not $KeepInstalledApp) {
    Write-Step "Removing conflicting installed debug packages"
    $deviceSerials = @($onlineDevices | ForEach-Object { $_.Serial })
    Reset-TestPackages -DeviceSerials $deviceSerials -Packages @(
        "dev.patrickgold.florisboard.debug.test",
        "dev.patrickgold.florisboard.debug"
    )
    & $AdbPath -s $selectedSerial wait-for-device | Out-Null
}

Write-Step "Running secure server integration tests"
Write-Host "Prefix: $resolvedPrefix"
Write-Host "Target: $classArgument"
Write-Host "SDK: $sdkRoot"
Write-Host "ADB: $AdbPath"
Write-Host "ANDROID_USER_HOME: $currentAndroidUserHome"
Write-Host "ANDROID_SERIAL: $env:ANDROID_SERIAL"
Write-Host "CARGO_HOME: $env:CARGO_HOME"
Write-Host "RUSTUP_HOME: $env:RUSTUP_HOME"
Write-Host "RUSTUP_TOOLCHAIN: $env:RUSTUP_TOOLCHAIN"
Write-Host "Devices:"
$onlineDevices | Format-Table -AutoSize

Push-Location $ProjectRoot
try {
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle exited with code $LASTEXITCODE"
    }

    $appApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    $instrumentationTarget = "dev.patrickgold.florisboard.debug.test/androidx.test.runner.AndroidJUnitRunner"

    Write-Step "Installing debug APKs"
    Install-Apk -Serial $selectedSerial -ApkPath $appApkPath
    Install-Apk -Serial $selectedSerial -ApkPath $testApkPath -AllowTestPackage

    Write-Step "Launching instrumentation"
    $instrumentArgs = @(
        "-s", $selectedSerial,
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $classArgument,
        "-e", "secureServerIt", "true",
        "-e", "secureServerItUsernamePrefix", $resolvedPrefix,
        "-e", "secureServerItPassword", $resolvedPassword,
        $instrumentationTarget
    )
    $instrumentationOutput = & $AdbPath @instrumentArgs 2>&1
    $instrumentationText = ($instrumentationOutput | Out-String).Trim()
    Write-Host $instrumentationText

    if ($LASTEXITCODE -ne 0) {
        throw "Instrumentation command failed with code $LASTEXITCODE"
    }

    if (
        $instrumentationText -match "FAILURES!!!" -or
        $instrumentationText -match "INSTRUMENTATION_FAILED" -or
        $instrumentationText -match "Process crashed"
    ) {
        throw "Instrumentation reported test failure."
    }
} finally {
    Pop-Location
}

Write-Step "Done"
Write-Host "Built APKs:"
Write-Host (Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk")
Write-Host (Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk")
