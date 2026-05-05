# Rusty XR Android Companion

Rusty XR Android Companion is a public Android phone app for Quest operator
workflows in the Rusty XR ecosystem.

This staging branch was seeded from a working phone-side proof app so the hard
parts stay proven while the public repo is reshaped around Rusty XR naming,
catalogs, diagnostics, and release boundaries.

## Current Scope

- Jetpack Compose Android phone UI.
- Foreground service host for long-running transport actions.
- Direct ADB protocol over Android USB host and TCP sockets.
- USB-host Quest bootstrap and authorization flow.
- Enable Wi-Fi ADB from a phone-to-headset USB connection.
- Direct Wi-Fi ADB reconnect to `host:5555`.
- ADB file transfer, streaming `pm install`, package launch, force-stop, URL
  open, foreground query, package listing, and utility key events.
- Staged APK install from app-private phone storage.
- Public `rusty.xr.quest-app-catalog.v1` metadata loader with bundled and
  staged catalog support.
- Runtime profile Activity extras on launch, aligned with the Windows
  companion catalog workflow.
- Phone-local runtime profile authoring and persistence, merged with imported
  catalog profiles.
- Device property profile application.
- OSC UDP send/listen diagnostics aligned with the Windows companion argument
  vocabulary.
- Polar H10 BLE availability and battery check using Android framework BLE.
- Agent command workflow for PC-driven Polar ECG/ACC smoke checks and
  phone-to-Quest install/launch/stop commands.
- Diagnostics ZIP export plus a Python analyzer.
- Optional LSL monitor surface that reports unavailable native runtime when no
  audited Android `liblsl` payload is present.

## Windows Companion And MCP Alignment

The Windows Companion owns the first API/CLI/MCP operation surface for the
ecosystem. This Android app exposes the phone-local side of that surface through
the gated `AgentCommandActivity` workflow documented in
`docs/agent-command-workflow.md`.

Current Windows/MCP wrappers should treat phone commands as explicit
state-changing operations:

- command mode is disabled by default in the phone app
- commands require a time-limited Agent Commands window, except for debug-only
  validation sessions
- reports are written to app-private external storage on the phone
- install, launch, stop, and utility commands should never be treated as
  passive read-only calls

## Public Boundary

This repo must stay public-safe:

- no private package IDs or launch activities
- no private APK payloads
- no signing keys or generated release secrets
- no raw captures, screenshots, or diagnostics bundles
- no local machine paths in committed docs or source
- no bundled native libraries until their license, source, version, ABI, hash,
  and redistribution posture are documented

Public catalogs should use generic target apps and Rusty XR public examples.
User-supplied APKs belong in local app storage, ignored caches, or release
assets with explicit manifests and hashes.

## Build

```powershell
.\Tools\invoke-android-gradle.ps1 -Task testDebugUnitTest
.\Tools\invoke-android-gradle.ps1 -Task assembleDebug
```

The default public build does not compile or bundle the optional native LSL
bridge. The Kotlin monitor reports that the runtime is unavailable until a
future audited native-runtime path is added.

## Install On A Phone

```powershell
.\Tools\install-quest-companion.ps1 -DeviceSerial <phone-serial>
```

The staging app id is:

```text
io.github.mesmerprism.rustyxr.companion.android
```

## Stage A Quest APK

The install action expects APK files staged in the app-private phone storage:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/apks/
```

Use the helper:

```powershell
.\Tools\push-staged-apks.ps1 -DeviceSerial <phone-serial> -ApkPath C:\path\to\your-app.apk
```

Catalog import and Storage Access Framework support are planned so this manual
staging path is not the long-term public UX.

## Catalogs

The bundled public example catalog is:

```text
app/src/main/assets/Catalogs/rusty-xr-android-companion.example.catalog.json
```

The same source example is available at:

```text
samples/catalogs/rusty-xr-android-companion.example.catalog.json
```

For local phone testing, stage a replacement catalog in app-private storage:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/catalogs/rusty-xr-android-companion.catalog.json
```

Use the helper to push the public catalog shape:

```powershell
.\Tools\sync-public-catalog.ps1 -DeviceSerial <phone-serial> -CatalogPath .\samples\catalogs\rusty-xr-android-companion.example.catalog.json -SkipApks
```

Catalog APK entries are metadata only. APK bytes stay in ignored local storage
or release assets with hashes and provenance.

## Local Runtime Profiles

The Library screen can create, copy, save, and delete phone-local runtime
profiles. Local profiles are stored in app-private JSON at:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/catalogs/user-runtime-profiles.json
```

Each profile stores a label, target package IDs, optional description, and
`key=value` launch extras. The app merges these profiles with bundled or staged
catalog profiles, so saved phone profiles appear in the same runtime preset
picker and are sent as launch extras on the next app launch.

## Diagnostics Export

After a transport action completes or fails, tap **Export Diagnostics Bundle**
in the app. The bundle is written under the app-private diagnostics folder.

Analyze a pulled bundle with:

```powershell
python .\Tools\analyze-phone-diagnostics.py <path-to-diag_YYYYMMDD_HHMMSS.zip>
```

The analyzer writes:

- `<stem>_report.md`
- `<stem>_analysis.json`

## Streaming And Sensors

The Streams screen provides dependency-free OSC send/listen tools and a
phone-side Polar H10 availability check. The Polar path uses Android BLE for
device presence, service visibility, standard Battery Level reads, and PMD
ECG/ACC smoke validation; it does not forward Polar data to the headset.

Android LSL examples such as RECORDA and SENDA are treated as architecture
references only because their app code is GPL-3.0. The public app can use
MIT-licensed LSL bindings later only after native library provenance,
third-party notices, and release manifests are complete.

## Next Implementation Slices

- Align phone diagnostics with the Windows companion diagnostics vocabulary.
- Resolve ADB protocol library provenance before publication.
- Add WebSocket profile sync and pairing diagnostics.
- Add optional phone-side Polar recording/export once the public file contract
  is settled.
- Add a Storage Access Framework import flow for APKs and catalogs.
- Add import/export for local runtime profile packs.

## Documentation

- [Architecture](docs/architecture.md)
- [Quest connection](docs/quest-connection.md)
- [APK install and launch](docs/apk-install-launch.md)
- [Agent command workflow](docs/agent-command-workflow.md)
- [Diagnostics](docs/diagnostics.md)
- [Streaming and sensor protocols](docs/streaming-protocols.md)
- [Source workspace](docs/source-workspace.md)
- [Release workflow](docs/release-workflow.md)
- [Public boundary](docs/public-boundary.md)
