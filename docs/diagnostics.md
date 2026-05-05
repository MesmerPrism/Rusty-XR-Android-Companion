# Diagnostics

The app exports phone-side diagnostics bundles for USB, Wi-Fi ADB, install,
launch, and shell probe failures.

Analyze a pulled bundle with:

```powershell
python .\Tools\analyze-phone-diagnostics.py <path-to-diagnostics.zip>
```

Diagnostics should stay useful without a source checkout and should avoid
private file paths, private app identifiers, screenshots, and raw captures.

Future diagnostics should align with the Windows companion vocabulary:

- app version and build type
- phone model and Android version
- USB permission and ADB authorization state
- endpoint parse and subnet comparison
- command timeline and failure class
- selected catalog app, device profile, and runtime profile IDs
- sanitized remediation notes
