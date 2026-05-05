param(
    [string]$DeviceSerial = "",
    [string]$UnityEditorVersion = "6000.3.8f1",
    [string[]]$ApkPath = @(),
    [switch]$ClearExisting
)

$ErrorActionPreference = "Stop"

function Resolve-UnityAndroidRoot([string]$version) {
    $candidate = Join-Path $env:ProgramFiles "Unity\Hub\Editor\$version\Editor\Data\PlaybackEngines\AndroidPlayer"
    if (Test-Path $candidate) {
        return $candidate
    }

    return $null
}

if ($ApkPath.Count -eq 0) {
    throw "Provide at least one local APK path with -ApkPath."
}

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
$phonePackageId = "io.github.mesmerprism.rustyxr.companion.android"
$remoteDir = "/sdcard/Android/data/$phonePackageId/files/apks"

if (-not $adbPath -or -not (Test-Path $adbPath)) {
    throw "adb not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or install the Unity Android SDK."
}

$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArgs += @("-s", $DeviceSerial)
}

Write-Host "Preparing staged APK directory on phone..." -ForegroundColor Cyan
Write-Host "  adb: $adbPath"
Write-Host "  remote: $remoteDir"
if ($adbArgs.Count -gt 0) {
    Write-Host "  serial: $DeviceSerial"
}

if ($ClearExisting) {
    & $adbPath @adbArgs shell "rm -rf '$remoteDir'"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed clearing existing staged APK directory."
    }
}

& $adbPath @adbArgs shell "mkdir -p '$remoteDir'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed creating staged APK directory."
}

foreach ($apk in $ApkPath) {
    if (-not (Test-Path $apk)) {
        throw "APK file not found: '$apk'."
    }

    $leaf = Split-Path $apk -Leaf
    Write-Host "Pushing $leaf..." -ForegroundColor Cyan
    & $adbPath @adbArgs push $apk "$remoteDir/$leaf"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed pushing '$leaf' to phone."
    }
}

Write-Host "Verifying staged APKs..." -ForegroundColor Cyan
& $adbPath @adbArgs shell "ls -lh '$remoteDir'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed listing staged APKs on phone."
}

Write-Host "APK staging complete." -ForegroundColor Green
Write-Host "For staged library metadata and bundle support, sync library.json with .\\Tools\\sync-apk-library.ps1." -ForegroundColor Yellow
