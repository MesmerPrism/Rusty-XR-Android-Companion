# Public Boundary

This repo is public Android companion source. Keep committed files portable and
safe to publish.

Do not commit:

- private/local repository names or paths
- private package IDs, launch activities, stream names, study names, or session
  IDs
- private visual behavior, research logic, or tuning constants
- Quest APK/AAB payloads without explicit release approval
- signing keys, keystores, passwords, or generated release secrets
- raw captures, screenshots, diagnostics ZIPs, or local artifacts
- native libraries without a documented redistribution audit

Use generic wording:

- target app
- user-supplied APK
- selected Quest
- device profile
- runtime profile
- public Rusty XR example

Run the boundary scan before publishing:

```powershell
powershell -ExecutionPolicy Bypass -File .\Tools\boundary-scan\Invoke-PublicBoundaryScan.ps1
```
