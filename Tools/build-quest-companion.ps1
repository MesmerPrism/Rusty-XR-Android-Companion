param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",
    [string]$UnityEditorVersion = "6000.3.8f1",
    [switch]$Clean
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
$projectRoot = $repoRoot
$gradleLauncher = Join-Path $projectRoot "gradlew.bat"
$unityAndroidRoot = Resolve-UnityAndroidRoot $UnityEditorVersion
$javaHome = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME
} elseif ($unityAndroidRoot) {
    Join-Path $unityAndroidRoot "OpenJDK"
} else {
    $null
}
$androidSdkRoot = if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
    $env:ANDROID_HOME
} elseif ($unityAndroidRoot) {
    Join-Path $unityAndroidRoot "SDK"
} else {
    $null
}
$task = if ($Configuration -eq "Release") { "assembleRelease" } else { "assembleDebug" }

if (-not (Test-Path $gradleLauncher)) {
    throw "Gradle launcher not found at '$gradleLauncher'."
}

if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "No Android JDK found. Set JAVA_HOME or install the Unity Android OpenJDK."
}

if (-not $androidSdkRoot -or -not (Test-Path $androidSdkRoot)) {
    throw "No Android SDK found. Set ANDROID_SDK_ROOT/ANDROID_HOME or install the Unity Android SDK."
}

if ($Clean) {
    foreach ($path in @(
        (Join-Path $projectRoot "build"),
        (Join-Path $projectRoot "app\build")
    )) {
        if (Test-Path $path) {
            [System.IO.Directory]::Delete($path, $true)
        }
    }
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdkRoot
$env:ANDROID_SDK_ROOT = $androidSdkRoot

Write-Host "Building Rusty XR Companion Android app..." -ForegroundColor Cyan
Write-Host "  Project: $projectRoot"
Write-Host "  Task:    $task"
Write-Host "  JDK:     $javaHome"
Write-Host "  SDK:     $androidSdkRoot"

Push-Location $projectRoot
try {
    & $gradleLauncher --console=plain $task
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$artifactPath = if ($Configuration -eq "Release") {
    Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
} else {
    Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
}

if (Test-Path $artifactPath) {
    Write-Host "APK: $artifactPath" -ForegroundColor Green
}
