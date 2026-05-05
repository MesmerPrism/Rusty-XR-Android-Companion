# Quest Connection

The Android companion uses direct ADB protocol support instead of launching an
external `adb` binary.

## USB

The phone acts as an Android USB host. The app looks for the standard ADB USB
interface and asks Android for permission before opening it. The Quest still
requires the normal ADB authorization prompt.

For the phone-to-headset cable step, the important detail is role direction:

- The phone must be on the USB host side for Android to enumerate the headset
  as a USB device. In a direct USB-C connection, that also means the phone
  should not negotiate as a charge-only peripheral.
- The Quest should be awake and unplugged from any PC.
- Use a cable that carries data. If a direct USB-C cable only starts charging
  and no USB device appears in diagnostics, retry with a phone-side OTG adapter
  or a powered USB-C hub.
- Accept both prompts when they appear: Android USB device permission on the
  phone, and USB debugging authorization inside the headset.

This follows Android's USB host model: a device in USB host mode powers the bus
and enumerates connected USB devices. A powered hub can make the bus-power side
less ambiguous, but the phone still has to be the Android host that sees the
Quest as a USB device. The app can detect and request permission for exposed
USB devices, but ordinary public app APIs cannot force the phone's USB-C
power/data role if the cable or OS negotiates the wrong direction.

## Wi-Fi ADB

After USB trust is established, the app can ask the headset to listen on TCP
port `5555`, read the headset Wi-Fi address, compare it with the phone network,
and reconnect over TCP.

A known endpoint can also be entered directly as:

```text
192.168.1.25:5555
```

## Status

The first public slice keeps the proven connection, shell probe, install,
launch, stop, package-list, foreground-query, and utility-key paths. Broader
device snapshots will be aligned with the Windows companion diagnostics model
in a later iteration.
