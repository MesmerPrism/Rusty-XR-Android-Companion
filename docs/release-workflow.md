# Release Workflow

This repo is not release-ready yet.

Before a public APK release:

- resolve ADB protocol library provenance and license
- generate or maintain third-party notices
- document Android Gradle, Kotlin, AndroidX, Compose, Material, and test
  dependencies
- decide debug versus release signing
- generate `RELEASE_MANIFEST.json`
- generate `SHA256SUMS.txt`
- list APK permissions and included native libraries
- run tests, lint, assemble, and public boundary scan

Do not publish APKs that bundle native monitor libraries until their source,
version, ABI, hash, license, and redistribution posture are documented.
