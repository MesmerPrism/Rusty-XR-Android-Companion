param(
    [string]$DeviceSerial = "",
    [string]$UnityEditorVersion = "6000.3.8f1",
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"

function Resolve-UnityAndroidRoot([string]$version) {
    $candidate = Join-Path $env:ProgramFiles "Unity\Hub\Editor\$version\Editor\Data\PlaybackEngines\AndroidPlayer"
    if (Test-Path $candidate) {
        return $candidate
    }

    return $null
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$defaultApkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$resolvedApkPath = if ([string]::IsNullOrWhiteSpace($ApkPath)) { $defaultApkPath } else { $ApkPath }
$unityAndroidRoot = Resolve-UnityAndroidRoot $UnityEditorVersion
$adbPath = if ($env:ANDROID_SDK_ROOT -and (Test-Path (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"))) {
    Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
} elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"))) {
    Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
} elseif ($unityAndroidRoot) {
    Join-Path $unityAndroidRoot "SDK\platform-tools\adb.exe"
} else {
    $null
}
$packageId = "io.github.mesmerprism.rustyxr.companion.android"
$activityComponent = "io.github.mesmerprism.rustyxr.companion.android/.MainActivity"

if (-not $adbPath -or -not (Test-Path $adbPath)) {
    throw "adb not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or install the Unity Android SDK."
}

if (-not (Test-Path $resolvedApkPath)) {
    throw "APK not found at '$resolvedApkPath'. Build it first with .\Tools\build-quest-companion.ps1."
}

$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArgs += @("-s", $DeviceSerial)
}

Write-Host "Installing Rusty XR Companion..." -ForegroundColor Cyan
Write-Host "  adb: $adbPath"
Write-Host "  apk: $resolvedApkPath"
if ($adbArgs.Count -gt 0) {
    Write-Host "  serial: $DeviceSerial"
}

& $adbPath @adbArgs install -r $resolvedApkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE."
}

& $adbPath @adbArgs shell am start -n $activityComponent
if ($LASTEXITCODE -ne 0) {
    throw "adb launch failed with exit code $LASTEXITCODE."
}

Write-Host "Installed and launched $packageId." -ForegroundColor Green
