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

The Android bundled example catalog uses staged APK filenames because a phone
cannot directly read sibling PC source output folders. Build public Quest
examples from `Rusty-XR`, push the APKs into the phone app's `files/apks/`
folder, then use the catalog entry to install and launch them.

Run validation with:

```powershell
.\Tools\invoke-android-gradle.ps1 -Task testDebugUnitTest
.\Tools\invoke-android-gradle.ps1 -Task assembleDebug
powershell -ExecutionPolicy Bypass -File .\Tools\boundary-scan\Invoke-PublicBoundaryScan.ps1
```

The helper uses `JAVA_HOME` and `ANDROID_SDK_ROOT` when present. On machines
that already have a Unity AndroidPlayer toolchain installed, it can use that
JDK/SDK as a local fallback without making the app depend on Unity.
