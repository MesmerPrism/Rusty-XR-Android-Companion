[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,

    [string] $Adb = $(if ($env:RUSTY_XR_ADB) { $env:RUSTY_XR_ADB } elseif ($env:DOPE_ADB_EXE) { $env:DOPE_ADB_EXE } else { "adb" }),

    [string] $PackageId = "io.github.mesmerprism.rustyxr.companion.android",

    [ValidateSet("Prepare", "Restore")]
    [string] $Mode = "Prepare",

    [string] $StatePath,

    [ValidateRange(0, 7)]
    [int] $StayOnWhilePluggedIn = 2,

    [switch] $NoDeviceIdleWhitelist
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = "Stop"

if (-not $StatePath) {
    $safeSerial = ($DeviceSerial -replace "[^A-Za-z0-9_.-]", "_")
    $StatePath = Join-Path ([System.IO.Path]::GetTempPath()) "rusty-xr-agent-command-phone-readiness-$safeSerial.json"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Arguments)
    & $Adb -s $DeviceSerial @Arguments
}

function Get-AdbText {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Arguments)
    $output = Invoke-Adb @Arguments
    ($output -join "`n").Trim()
}

function Get-PhoneReadinessSnapshot {
    $stayOn = Get-AdbText shell settings get global stay_on_while_plugged_in
    $whitelist = Get-AdbText shell dumpsys deviceidle whitelist
    $power = Get-AdbText shell dumpsys power
    [ordered]@{
        stayOnWhilePluggedIn = $stayOn
        deviceIdleWhitelisted = ($whitelist -match [regex]::Escape($PackageId))
        wakefulness = (($power -split "`n") | Where-Object { $_ -match "mWakefulness=|mStayOn=" } | ForEach-Object { $_.Trim() })
    }
}

if ($Mode -eq "Prepare") {
    $before = Get-PhoneReadinessSnapshot
    $state = [ordered]@{
        schema = "rusty.xr.android_companion.agent_command_phone_readiness.v1"
        deviceSerial = $DeviceSerial
        packageId = $PackageId
        previousStayOnWhilePluggedIn = $before.stayOnWhilePluggedIn
        wasDeviceIdleWhitelisted = [bool] $before.deviceIdleWhitelisted
        preparedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    }
    $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $StatePath -Encoding utf8

    if (-not $NoDeviceIdleWhitelist) {
        Invoke-Adb shell cmd deviceidle whitelist "+$PackageId" | Out-Host
    }
    Invoke-Adb shell settings put global stay_on_while_plugged_in "$StayOnWhilePluggedIn" | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Invoke-Adb shell wm dismiss-keyguard | Out-Null

    $after = Get-PhoneReadinessSnapshot
    [ordered]@{
        mode = "Prepare"
        statePath = $StatePath
        before = $before
        after = $after
    } | ConvertTo-Json -Depth 6
    exit 0
}

if (-not (Test-Path -LiteralPath $StatePath)) {
    throw "State file not found: $StatePath"
}

$saved = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
Invoke-Adb shell settings put global stay_on_while_plugged_in "$($saved.previousStayOnWhilePluggedIn)" | Out-Null
if (-not $NoDeviceIdleWhitelist -and -not [bool] $saved.wasDeviceIdleWhitelisted) {
    Invoke-Adb shell cmd deviceidle whitelist "-$PackageId" | Out-Host
}

$afterRestore = Get-PhoneReadinessSnapshot
[ordered]@{
    mode = "Restore"
    statePath = $StatePath
    restoredStayOnWhilePluggedIn = $saved.previousStayOnWhilePluggedIn
    removedDeviceIdleWhitelist = (-not [bool] $saved.wasDeviceIdleWhitelisted -and -not $NoDeviceIdleWhitelist)
    after = $afterRestore
} | ConvertTo-Json -Depth 6
