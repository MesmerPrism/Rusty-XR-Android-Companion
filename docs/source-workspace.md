# Source Workspace

Recommended sibling layout:

```text
<workspace>\Rusty-XR
<workspace>\Rusty-XR-Android-Companion
<workspace>\Rusty-XR-Companion-Apps
```

Rusty XR owns public contracts, schemas, examples, and Quest APK source. The
Android companion owns phone-side USB-host ADB, phone storage, Android
permissions, install/launch UX, and phone diagnostics.

For active Morphospace Makepad maintenance, keep these optional sibling repos
beside the public compatibility repos when they are present:

```text
<workspace>\rusty-gui
<workspace>\rusty-makepad
<workspace>\rusty-quest
<workspace>\rusty-quest-makepad
<workspace>\makepad-morphospace
<workspace>\rusty-hostess
```

Those repos do not replace the Android companion's public Rusty XR catalog
surface. They are the active lanes for portable GUI descriptors, canonical
Makepad settings/profile resolution, Quest property write plans,
Quest-specific Makepad app adapters, the maintained Makepad fork checkout, and
the Hostess app shell that consumes generated effective-settings reports.

The Android bundled example catalog uses staged APK filenames because a phone
cannot directly read sibling PC source output folders. Build public Quest
examples from `Rusty-XR`, push the APKs into the phone app's `files/apks/`
folder, then use the catalog entry to install and launch them.

## Active Makepad Settings Flow

For Morphospace Makepad apps, do not hand-craft ADB extras or phone-local
runtime profile values as the settings authority. Resolve one canonical
effective-settings report, then use the phone companion only as the transport
for the target APK, launch extras, and generated Quest property profile:

- `rusty-makepad` owns `rusty.gui.makepad.app_settings_surface.v1`,
  `rusty.gui.makepad.settings_profile.v1`, the resolver, provenance, and
  hotload decisions.
- `rusty-quest-makepad` owns Quest-specific Makepad profile bundles and
  camera-shell adapters over that surface.
- `rusty-quest` owns `debug.rustyquest.makepad.*` property write/readback
  plans as a platform transport layer.
- `rusty-hostess` consumes `rusty.gui.makepad.effective_settings.v1` with
  `--makepad-effective-settings`, `RUSTY_MAKEPAD_EFFECTIVE_SETTINGS`, or its
  Android default settings file locations.
- `Rusty-XR-Android-Companion` can stage APKs, apply catalog device profiles,
  and pass Activity extras, but it does not resolve Makepad settings.

Build the current Quest Makepad camera-shell mesh replay bundle from the active
repos:

```powershell
cd <workspace>\rusty-quest-makepad
.\tools\Build-QuestMakepadRuntimeBundle.ps1 -OutDir .\local-artifacts\quest-makepad-runtime-bundle
```

The bundle output contains `effective-settings.json`,
`property-write-plan.json`, and `runtime-bundle-report.json`. Treat
`effective-settings.json` as the app-facing canonical settings report and
`property-write-plan.json` as generated Quest transport evidence. When the
phone companion is the launch path, stage a public-safe catalog whose device
profile mirrors the generated `debug.rustyquest.makepad.*` property values,
apply that device profile from the phone UI, then launch the selected app with
only the Activity extras the app explicitly documents.

Run validation with:

```powershell
.\Tools\invoke-android-gradle.ps1 -Task testDebugUnitTest
.\Tools\invoke-android-gradle.ps1 -Task assembleDebug
powershell -ExecutionPolicy Bypass -File .\Tools\boundary-scan\Invoke-PublicBoundaryScan.ps1
```

The helper uses `JAVA_HOME` and `ANDROID_SDK_ROOT` when present. On machines
that already have a Unity AndroidPlayer toolchain installed, it can use that
JDK/SDK as a local fallback without making the app depend on Unity.
