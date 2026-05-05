# Contributing

Contributions are welcome when they keep the project useful as a general
Android phone companion for Rusty XR and Quest development workflows.

Good first contributions:

- clearer setup docs
- ADB error explanations
- no-hardware unit tests
- public sample catalog improvements
- diagnostics bundle improvements
- Android storage import flow improvements
- accessibility and screen-reader fixes in the Compose UI

Before opening a PR:

```powershell
.\Tools\invoke-android-gradle.ps1 -Task testDebugUnitTest
.\Tools\invoke-android-gradle.ps1 -Task lintDebug
.\Tools\invoke-android-gradle.ps1 -Task assembleDebug
powershell -ExecutionPolicy Bypass -File .\tools\boundary-scan\Invoke-PublicBoundaryScan.ps1
```

## Public Asset Policy

The source repo does not commit Quest APK payloads. Use user-selected APKs,
local ignored caches, or public release assets with explicit manifests and
hashes.

Do not attach private builds, private certificates, generated capture data,
diagnostics ZIPs, screenshots, or machine-specific logs to public PRs.
