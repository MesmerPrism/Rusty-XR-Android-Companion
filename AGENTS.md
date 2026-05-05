# Agent Notes

This is intended to become a public open-source Android companion repo. Keep
every committed file public-safe.

## Public Boundary

Do not commit:

- private/local repository names or paths
- private package IDs, launch activities, stream names, study names, or session
  IDs
- private visual-effect behavior, unpublished research logic, or private tuning
  constants
- APK/AAB payloads unless a future release explicitly approves them and release
  storage is configured
- signing keys, keystores, passwords, PFX/P12 files, or generated release
  secrets
- raw captures, screenshots, diagnostics ZIPs, or other local artifacts
- native libraries unless their source, version, ABI, hash, license, and
  redistribution posture are documented

Use generic language such as "target app", "user-supplied APK", "selected
Quest", "device profile", and "runtime profile".

## Architecture Rules

- Keep phone-specific USB-host ADB behind transport services.
- Keep no-hardware diagnostics and unit tests useful.
- Prefer public Rusty XR schemas when they are stable.
- Keep generated Quest APK bytes out of source.
- Treat LSL native runtime support as optional until the binary release path is
  audited.

## Validation

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```
