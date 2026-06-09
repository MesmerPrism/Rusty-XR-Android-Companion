# APK Install And Launch

The Android companion installs user-supplied Quest APKs from app-private phone
storage. Install uses Android package-manager streaming from the phone to the
Quest, so it does not need to leave a temporary APK on headset storage.

Current staging flow:

```powershell
.\Tools\push-staged-apks.ps1 -DeviceSerial <phone-serial> -ApkPath C:\path\to\app.apk
```

Then use the app UI to select, install, launch, or stop the target app.

PC-based agents can run the same install, launch, and stop paths through the
time-limited command activity documented in
[Agent command workflow](agent-command-workflow.md).

## Catalog Direction

The app loads public Rusty XR catalog metadata first and keeps the original
proof catalog paths as fallback:

```text
rusty.xr.quest-app-catalog.v1
```

Bundled example:

```text
app/src/main/assets/Catalogs/rusty-xr-android-companion.example.catalog.json
```

Staged phone override:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/catalogs/rusty-xr-android-companion.catalog.json
```

Helper:

```powershell
.\Tools\sync-public-catalog.ps1 -DeviceSerial <phone-serial> -CatalogPath .\samples\catalogs\rusty-xr-android-companion.example.catalog.json -SkipApks
```

Runtime profile `values` are passed as typed `am start` Activity extras on
launch. Boolean, integer, long, and float-looking strings use `--ez`, `--ei`,
`--el`, and `--ef`; other values use `--es`. Profiles with a legacy CSV file
can still be uploaded as `runtime_overrides.csv`.

## Morphospace Makepad Profiles

Active Morphospace Makepad settings use the canonical
`rusty.gui.makepad.effective_settings.v1` resolver path from `rusty-makepad`.
The Android companion does not resolve that surface and should not become a
second settings authority. Use `rusty-quest-makepad` to build the Quest Makepad
runtime bundle, then stage the generated property values as a catalog device
profile when the phone is the launcher.

A catalog device profile can carry generated Quest Makepad property values:

```json
{
  "id": "quest-makepad-mesh-replay",
  "label": "Quest Makepad Mesh Replay",
  "properties": [
    {
      "key": "debug.rustyquest.makepad.mesh.replay.enabled",
      "value": "true"
    },
    {
      "key": "debug.rustyquest.makepad.render.scale",
      "value": "0.9"
    }
  ]
}
```

Apply the device profile before launch. Keep Activity extras for target app
launch flags only; do not duplicate a `debug.rustyquest.makepad.*` value as an
extra unless the target app explicitly documents that exact extra. Public
`rustyxr.*` runtime profile examples remain compatibility examples for Rusty XR
catalog apps, not the active Makepad settings source of truth.

## Phone-Local Runtime Profiles

The phone app can store local runtime profiles alongside imported catalog
profiles. Use the Library screen's runtime profile editor to create a new
profile or copy the selected catalog profile into a local draft, then save it.

Local profiles are written to app-private storage:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/catalogs/user-runtime-profiles.json
```

The file uses this public-safe shape:

```json
{
  "schemaVersion": "rusty.xr.android-companion.user-runtime-profiles.v1",
  "profiles": [
    {
      "id": "user.dev-smoke",
      "label": "Development Smoke",
      "description": "Local launch extras",
      "packageIds": ["com.example.target"],
      "values": {
        "rustyxr.example": "quest-composite-layer-apk",
        "rustyxr.camera": "false"
      }
    }
  ]
}
```

Catalog profiles remain read-only in the app. Local profiles are marked
`local`, can be deleted from the phone UI, and are merged into the same runtime
preset picker used by Launch Selected App.

Device profiles from the public catalog write Android properties with readback
verification where practical.

Quest APK bytes should remain local files, ignored caches, or release assets
with explicit hashes and provenance.
