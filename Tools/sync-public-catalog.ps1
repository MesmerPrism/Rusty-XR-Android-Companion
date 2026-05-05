param(
    [string]$DeviceSerial = "",
    [string]$CatalogPath = "",
    [string]$ApkRoot = "",
    [string]$UnityEditorVersion = "6000.3.8f1",
    [switch]$ClearExisting,
    [switch]$SkipApks
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

function Assert-LeafApkFile([string]$appId, [string]$apkFile) {
    if ([string]::IsNullOrWhiteSpace($apkFile)) {
        return
    }
    if ($apkFile -match "^[a-zA-Z][a-zA-Z0-9+.-]*://") {
        throw "App '$appId' uses remote apkFile '$apkFile'. Download/cache support is not implemented for phone staging yet."
    }

    $leaf = Split-Path $apkFile -Leaf
    if ($leaf -ne $apkFile) {
        throw "App '$appId' uses apkFile '$apkFile'. Use a leaf APK filename for Android phone staging."
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($CatalogPath)) {
    $CatalogPath = Join-Path $repoRoot "samples\catalogs\rusty-xr-android-companion.example.catalog.json"
}

$catalogFile = Resolve-Path $CatalogPath -ErrorAction Stop
$catalogDir = Split-Path $catalogFile -Parent
$apkSourceRoot = if ([string]::IsNullOrWhiteSpace($ApkRoot)) {
    $catalogDir
} else {
    (Resolve-Path $ApkRoot -ErrorAction Stop).Path
}
$catalogJson = Get-Content $catalogFile -Raw | ConvertFrom-Json

if ($catalogJson.schemaVersion -ne "rusty.xr.quest-app-catalog.v1") {
    throw "Catalog schemaVersion must be 'rusty.xr.quest-app-catalog.v1'."
}
if (-not $catalogJson.apps -or $catalogJson.apps.Count -eq 0) {
    throw "The catalog must contain a non-empty 'apps' array."
}

$seenAppIds = New-Object System.Collections.Generic.HashSet[string]
$resolvedApks = @()
foreach ($app in $catalogJson.apps) {
    foreach ($required in @("id", "label", "packageName")) {
        if ([string]::IsNullOrWhiteSpace($app.$required)) {
            throw "App entry is missing required field '$required'."
        }
    }

    if (-not $seenAppIds.Add([string]$app.id)) {
        throw "Duplicate app id in catalog: '$($app.id)'."
    }

    $apkFile = [string]$app.apkFile
    Assert-LeafApkFile -appId $app.id -apkFile $apkFile
    if (-not [string]::IsNullOrWhiteSpace($apkFile)) {
        $apkSource = Join-Path $apkSourceRoot $apkFile
        if (Test-Path $apkSource) {
            $resolvedApks += [pscustomobject]@{
                AppId = $app.id
                ApkFile = $apkFile
                SourcePath = (Resolve-Path $apkSource).Path
            }
        } elseif (-not $SkipApks) {
            Write-Host "APK for app '$($app.id)' was not found at '$apkSource'; catalog will still be staged." -ForegroundColor Yellow
        }
    }
}

if ($catalogJson.bundles) {
    foreach ($bundle in $catalogJson.bundles) {
        if ([string]::IsNullOrWhiteSpace($bundle.id) -or [string]::IsNullOrWhiteSpace($bundle.label)) {
            throw "Each bundle must define 'id' and 'label'."
        }
        foreach ($appId in @($bundle.appIds)) {
            if (-not $seenAppIds.Contains([string]$appId)) {
                throw "Bundle '$($bundle.id)' references unknown app id '$appId'."
            }
        }
    }
}

$adbPath = Resolve-AndroidAdb $UnityEditorVersion
$phonePackageId = "io.github.mesmerprism.rustyxr.companion.android"
$remoteCatalogDir = "/sdcard/Android/data/$phonePackageId/files/catalogs"
$remoteApkDir = "/sdcard/Android/data/$phonePackageId/files/apks"
$remoteCatalog = "$remoteCatalogDir/rusty-xr-android-companion.catalog.json"
$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArgs += @("-s", $DeviceSerial)
}

Write-Host "Syncing Rusty XR Android catalog to phone..." -ForegroundColor Cyan
Write-Host "  adb:     $adbPath"
Write-Host "  catalog: $catalogFile"
Write-Host "  APK root: $apkSourceRoot"
Write-Host "  remote:  $remoteCatalog"
if ($adbArgs.Count -gt 0) {
    Write-Host "  serial:  $DeviceSerial"
}

if ($ClearExisting) {
    & $adbPath @adbArgs shell "rm -rf '$remoteCatalogDir' '$remoteApkDir'"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed clearing remote catalog/APK directories."
    }
}

& $adbPath @adbArgs shell "mkdir -p '$remoteCatalogDir' '$remoteApkDir'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed creating remote catalog/APK directories."
}

& $adbPath @adbArgs push $catalogFile $remoteCatalog
if ($LASTEXITCODE -ne 0) {
    throw "Failed pushing catalog to phone."
}

if (-not $SkipApks) {
    $uniqueApks = $resolvedApks | Sort-Object ApkFile -Unique
    foreach ($apk in $uniqueApks) {
        Write-Host "Pushing $($apk.ApkFile)..." -ForegroundColor Cyan
        & $adbPath @adbArgs push $apk.SourcePath "$remoteApkDir/$($apk.ApkFile)"
        if ($LASTEXITCODE -ne 0) {
            throw "Failed pushing '$($apk.ApkFile)' to phone."
        }
    }
}

Write-Host "Verifying staged catalog..." -ForegroundColor Cyan
& $adbPath @adbArgs shell "ls -lh '$remoteCatalogDir' '$remoteApkDir'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed listing remote catalog/APK directories."
}

Write-Host "Public catalog sync complete." -ForegroundColor Green
