param(
    [string]$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
)

$ErrorActionPreference = "Stop"

$resolvedRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$ignoredDirectoryNames = @(
    ".git",
    ".gradle",
    ".idea",
    "build",
    ".cxx",
    "jniLibs",
    "artifacts",
    "diagnostics"
)

$blockedTextPatterns = @(
    ("visc" + "ereality"),
    ("quest" + "companion"),
    ("Quest" + "Session" + "Kit"),
    ("Quest " + "Session " + "Kit"),
    ("Astral" + "Karate" + "Dojo"),
    ("Rusty" + "-DOPE"),
    ("Dope" + "Companion"),
    ("Color" + "ama"),
    ("Frak" + "till"),
    ("brain" + "-candy"),
    ("C:" + "\Users\")
)

$blockedFileExtensions = @(
    ".apk",
    ".aab",
    ".apks",
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".zip"
)

$textFileExtensions = @(
    ".gradle",
    ".java",
    ".json",
    ".kt",
    ".kts",
    ".md",
    ".properties",
    ".ps1",
    ".toml",
    ".txt",
    ".xml"
)

function Test-IsIgnoredPath([string]$path) {
    $relative = Get-RelativePath $path
    $segments = $relative -split '[\\/]'
    foreach ($segment in $segments) {
        if ($ignoredDirectoryNames -contains $segment) {
            return $true
        }
    }

    return $false
}

function Get-RelativePath([string]$path) {
    $fullPath = (Resolve-Path -LiteralPath $path).Path
    if ($fullPath.Equals($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        return "."
    }

    $rootPrefix = $resolvedRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        return $fullPath.Substring($rootPrefix.Length)
    }

    return $fullPath
}

$findings = New-Object System.Collections.Generic.List[string]

Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File -Force | ForEach-Object {
    if (Test-IsIgnoredPath $_.FullName) {
        return
    }

    $relative = Get-RelativePath $_.FullName
    $extension = $_.Extension

    if ($blockedFileExtensions -contains $extension) {
        $findings.Add("Blocked artifact file: $relative")
        return
    }

    if (($textFileExtensions -notcontains $extension) -and $_.Name -ne ".gitignore") {
        return
    }

    $content = Get-Content -LiteralPath $_.FullName -Raw
    foreach ($pattern in $blockedTextPatterns) {
        if ($content.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $findings.Add("Blocked text pattern '$pattern' in $relative")
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host "Public boundary scan failed:" -ForegroundColor Red
    $findings | Sort-Object | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}

Write-Host "Public boundary scan passed." -ForegroundColor Green
