param(
    [string]$DeviceSerial = "",
    [string]$ManifestPath = "",
    [string]$UnityEditorVersion = "6000.3.8f1",
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

function Resolve-AndroidAdb([string]$unityEditorVersion) {
    $unityAndroidRoot = Resolve-UnityAndroidRoot $unityEditorVersion
    if ($env:ANDROID_SDK_ROOT -and (Test-Path (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"))) {
        return Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
    }
    if ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"))) {
        return Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    }
    if ($unityAndroidRoot) {
        $candidate = Join-Path $unityAndroidRoot "SDK\platform-tools\adb.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "adb not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or install the Unity Android SDK."
}

if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    throw "Provide -ManifestPath pointing at a library.json manifest."
}

$manifestFile = Resolve-Path $ManifestPath -ErrorAction Stop
$manifestDir = Split-Path $manifestFile -Parent
$manifestJson = Get-Content $manifestFile -Raw | ConvertFrom-Json

if (-not $manifestJson.apps -or $manifestJson.apps.Count -eq 0) {
    throw "The manifest must contain a non-empty 'apps' array."
}

$seenAppIds = New-Object System.Collections.Generic.HashSet[string]
$resolvedApks = @()
foreach ($app in $manifestJson.apps) {
    foreach ($required in @("id", "label", "packageId", "apkFile")) {
        if ([string]::IsNullOrWhiteSpace($app.$required)) {
            throw "App entry is missing required field '$required'."
        }
    }

    if (-not $seenAppIds.Add($app.id)) {
        throw "Duplicate app id in manifest: '$($app.id)'."
    }

    $apkLeaf = Split-Path $app.apkFile -Leaf
    if ($apkLeaf -ne $app.apkFile) {
        throw "App '$($app.id)' uses apkFile '$($app.apkFile)'. Use a leaf filename only."
    }

    $apkSource = Join-Path $manifestDir $app.apkFile
    if (-not (Test-Path $apkSource)) {
        throw "APK file for app '$($app.id)' not found: '$apkSource'."
    }

    $resolvedApks += [pscustomobject]@{
        AppId = $app.id
        ApkFile = $app.apkFile
        SourcePath = (Resolve-Path $apkSource).Path
    }
}

if ($manifestJson.bundles) {
    $knownAppIds = $seenAppIds
    foreach ($bundle in $manifestJson.bundles) {
        if ([string]::IsNullOrWhiteSpace($bundle.id) -or [string]::IsNullOrWhiteSpace($bundle.label)) {
            throw "Each bundle must define 'id' and 'label'."
        }
        if (-not $bundle.appIds -or $bundle.appIds.Count -eq 0) {
            throw "Bundle '$($bundle.id)' must contain at least one app id."
        }
        foreach ($appId in $bundle.appIds) {
            if (-not $knownAppIds.Contains([string]$appId)) {
                throw "Bundle '$($bundle.id)' references unknown app id '$appId'."
            }
        }
    }
}

$adbPath = Resolve-AndroidAdb $UnityEditorVersion
$phonePackageId = "io.github.mesmerprism.rustyxr.companion.android"
$remoteDir = "/sdcard/Android/data/$phonePackageId/files/apks"
$remoteManifest = "$remoteDir/library.json"
$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArgs += @("-s", $DeviceSerial)
}

Write-Host "Syncing APK library to phone..." -ForegroundColor Cyan
Write-Host "  adb:      $adbPath"
Write-Host "  manifest: $manifestFile"
Write-Host "  remote:   $remoteDir"
if ($adbArgs.Count -gt 0) {
    Write-Host "  serial:   $DeviceSerial"
}

if ($ClearExisting) {
    & $adbPath @adbArgs shell "rm -rf '$remoteDir'"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed clearing the remote APK library directory."
    }
}

& $adbPath @adbArgs shell "mkdir -p '$remoteDir'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed creating the remote APK library directory."
}

$uniqueApks = $resolvedApks | Sort-Object ApkFile -Unique
foreach ($apk in $uniqueApks) {
    Write-Host "Pushing $($apk.ApkFile)..." -ForegroundColor Cyan
    & $adbPath @adbArgs push $apk.SourcePath "$remoteDir/$($apk.ApkFile)"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed pushing '$($apk.ApkFile)' to phone."
    }
}

Write-Host "Pushing library manifest..." -ForegroundColor Cyan
& $adbPath @adbArgs push $manifestFile $remoteManifest
if ($LASTEXITCODE -ne 0) {
    throw "Failed pushing library.json to phone."
}

Write-Host "Verifying staged APK library..." -ForegroundColor Cyan
& $adbPath @adbArgs shell "ls -lh '$remoteDir'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed listing the remote APK library directory."
}

Write-Host "APK library sync complete." -ForegroundColor Green
