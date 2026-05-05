# Streaming And Sensor Protocols

This app keeps live protocol work in phone-side adapters. Rusty XR core should
own public contracts and descriptors; Android app code owns sockets, Android
permissions, Bluetooth, storage, and native-runtime decisions.

## Implemented In This App

- OSC send: UDP OSC messages with `int`, `float`, `string`, `bool`, `nil`,
  `impulse`, and `blob-hex` arguments.
- OSC receive: phone-side UDP listener that shows recent decoded OSC packets
  and parse failures.
- Polar H10 availability: Android BLE scan plus standard Battery Level read.
  The phone does not need to forward Polar data to the headset when the sensor
  can connect to the phone and headset separately.
- LSL monitor surface: Kotlin monitor abstraction plus native bridge shape. The
  default public build reports native LSL unavailable until an audited Android
  `liblsl` payload is supplied.

## License-Safe Android LSL References

RECORDA and SENDA are useful public Android LSL examples from NeuropsyOL:

- [RECORDA](https://github.com/NeuropsyOL/RECORDA): Android app for receiving
  LSL streams and recording them for export.
- [SENDA](https://github.com/NeuropsyOL/SENDA): Android app for sending phone
  sensor streams over LSL.

Their app code is GPL-3.0, so Rusty XR Android Companion treats them as
architecture references only. Do not copy code, UI text, assets, or project
structure from those apps into this MIT/public app. Safe learnings to carry
forward:

- Keep stream discovery separate from stream recording.
- Show stream inventory and stream health before starting a recording.
- Make recording finalization explicit so partial files are not mistaken for
  complete datasets.
- Keep high-rate stream capture off the UI thread.
- Treat Android power, foreground-service, and permission states as first-class
  diagnostics.
- Store recordings in app-private storage first, then export intentionally.

`liblsl-Android` and `liblsl-Java` are MIT-licensed upstream building blocks,
but an Android APK that bundles native `liblsl` still needs release metadata:
source URL, version, commit, ABI list, hashes, license notices, and native
library inventory.

## Polar H10 Path

The first phone-side Polar slice is intentionally small:

1. Scan for Polar, Heart Rate service, or Polar PMD advertisements.
2. Connect over Android BLE only long enough to discover services.
3. Read the standard BLE Battery Level characteristic.
4. Report whether Heart Rate and Polar PMD services are visible.
5. Close scan/GATT cleanly when the user stops the monitor.

Later slices can add secondary storage for RR/HR/ECG/ACC only after the public
API, file format, and dependency posture are decided. Prefer Android framework
BLE for the first storage pass. The Polar BLE SDK can be considered later only
after its license and redistribution posture are explicitly accepted.

The current Polar implementation does not decode or store ECG or accelerometer
samples. It only proves that the phone can discover a Polar H10, connect over
BLE, read the standard Battery Level characteristic, and detect whether the
standard Heart Rate service and Polar PMD service are visible. ECG and ACC
require a follow-up PMD control-point and data-characteristic implementation.

## Debug ADB Smoke Check

Debug builds expose an ADB-only Polar scan Activity so a developer can run a
quick phone-side smoke test without driving the UI:

```powershell
adb shell am start -n io.github.mesmerprism.rustyxr.companion.android/.debug.DebugPolarScanActivity --el timeout_ms 30000
adb logcat -d -s RustyXrPolarSmoke
```

This entrypoint is declared only in the debug source set. It should not become a
release control API unless it is converted into a deliberate diagnostics command
with public docs and stable output.

Public developer-tool direction:

- keep this kind of remote workflow because it makes open-source iteration much
  faster;
- move from ad hoc debug Activity to a documented diagnostics command surface;
- produce machine-readable JSON plus logcat summaries;
- keep release builds explicit and user-consented rather than silently exposing
  arbitrary control actions to other apps.

Debug builds also expose a Quest transport suite for validating phone-to-headset
ADB and low-risk protocol reachability:

```powershell
adb shell am start -n io.github.mesmerprism.rustyxr.companion.android/.debug.DebugQuestTransportSuiteActivity --es endpoint 10.100.241.68:5555 --ei osc_port 9000
adb logcat -d -s RustyXrQuestSuite
adb pull /storage/emulated/0/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/diagnostics/
```

The suite runs safe checks only: phone-side ADB connect, reversible temp-file
capability probe, wake, installed package listing, foreground package query,
Home, Back, and one OSC UDP probe. It writes a JSON report under app-private
external diagnostics storage.

## Feasible Protocol Order

1. OSC: implemented first because it is low-dependency, LAN-friendly, and
   aligns with the Windows companion controls.
2. WebSocket: good next step for pairing, profile sync, and dashboard state.
3. Polar BLE status: implemented as availability and battery check; data
   storage can be added without involving the headset.
4. LSL native receive/record: keep behind the existing monitor abstraction
   until native packaging and XDF/export decisions are audited.
5. Video: keep out of the phone app for now unless a specific phone camera or
   bridge workflow appears. Prefer contract-first work before native media
   dependencies.
