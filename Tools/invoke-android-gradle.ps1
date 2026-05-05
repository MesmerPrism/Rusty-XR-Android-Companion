param(
    [Parameter(Mandatory = $true)]
    [string]$Task,
    [string]$UnityEditorVersion = "6000.3.8f1",
    [switch]$NoDaemon
)

$ErrorActionPreference = "Stop"

function Resolve-UnityAndroidRoot([string]$version) {
    $candidate = Join-Path $env:ProgramFiles "Unity\Hub\Editor\$version\Editor\Data\PlaybackEngines\AndroidPlayer"
    if (Test-Path -LiteralPath $candidate) {
        return $candidate
    }

    return $null
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$gradleLauncher = Join-Path $repoRoot "gradlew.bat"
$unityAndroidRoot = Resolve-UnityAndroidRoot $UnityEditorVersion

$javaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME
} elseif ($unityAndroidRoot) {
    Join-Path $unityAndroidRoot "OpenJDK"
} else {
    $null
}

$androidSdkRoot = if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    $env:ANDROID_HOME
} elseif ($unityAndroidRoot) {
    Join-Path $unityAndroidRoot "SDK"
} else {
    $null
}

if (-not (Test-Path -LiteralPath $gradleLauncher)) {
    throw "Gradle launcher not found at '$gradleLauncher'."
}

if (-not $javaHome -or -not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "No Android JDK found. Set JAVA_HOME or install an Android-compatible JDK."
}

if (-not $androidSdkRoot -or -not (Test-Path -LiteralPath $androidSdkRoot)) {
    throw "No Android SDK found. Set ANDROID_SDK_ROOT/ANDROID_HOME or install Android SDK command-line tools."
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdkRoot
$env:ANDROID_SDK_ROOT = $androidSdkRoot
$env:PATH = (Join-Path $javaHome "bin") + [IO.Path]::PathSeparator + $env:PATH

Write-Host "Running Android Gradle task..." -ForegroundColor Cyan
Write-Host "  Project: $repoRoot"
Write-Host "  Task:    $Task"
Write-Host "  JDK:     $javaHome"
Write-Host "  SDK:     $androidSdkRoot"

$args = @("--console=plain", $Task)
if ($NoDaemon) {
    $args = @("--no-daemon") + $args
}

Push-Location $repoRoot
try {
    & $gradleLauncher @args
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task '$Task' failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
