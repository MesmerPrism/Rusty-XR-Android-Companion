# Catalog Samples

`rusty-xr-android-companion.example.catalog.json` is a public-safe
`rusty.xr.quest-app-catalog.v1` example for the Android phone companion.

It contains metadata only. Build or obtain APKs separately, stage them in the
phone app's `files/apks/` folder, and keep APK bytes out of source unless a
future release manifest explicitly covers them.

For a phone-connected local test:

```powershell
.\Tools\sync-public-catalog.ps1 -DeviceSerial <phone-serial> -CatalogPath .\samples\catalogs\rusty-xr-android-companion.example.catalog.json -SkipApks
```
