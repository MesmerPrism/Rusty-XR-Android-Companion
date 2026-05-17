# Quest Connection

The Android companion uses direct ADB protocol support instead of launching an
external `adb` binary.

## Prerequisites And Gates

The phone app is a phone-side ADB client. It can do useful Quest operator work
after the Quest exposes ADB, but it cannot enable Meta Quest Developer Mode or
make a non-Developer-Mode headset expose ADB from nothing.

Keep these gates separate:

```text
Developer Mode gate
  normally enabled for a consumer Quest through the Meta account/mobile-app
  developer flow

ADB authorization gate
  the headset user authorizes this phone app's ADB key

ADB transport gate
  USB or Wi-Fi carries the already-authorized ADB session
```

If Developer Mode is off, the phone may see no USB device, or it may see a USB
device without the standard ADB interface. In that state there is usually no
headset "Allow USB debugging" prompt to accept, and the app cannot run shell
commands or enable Wi-Fi ADB.

If Developer Mode is on but this phone has never connected before, the expected
first connection requires both prompts:

- Android USB device permission on the phone.
- USB debugging authorization inside the headset for the phone app's ADB key.

After those prompts are accepted, the app can use USB ADB, ask the headset to
listen on TCP port `5555`, and reconnect over Wi-Fi when the network is
reachable.

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

Wi-Fi ADB is a handoff for an ADB relationship that already works. It is not a
bootstrap path for a locked headset, and it commonly has to be re-enabled after
headset reboot or ADB transport reset.

The dependency chain is:

```text
Developer Mode on
  -> Quest exposes ADB USB interface
  -> phone is USB host and sees that interface
  -> phone user grants USB device permission to this app
  -> headset user authorizes this app's ADB key
  -> app opens USB ADB
  -> app sends tcpip:5555
  -> app connects to <quest-ip>:5555 over Wi-Fi
```

## Troubleshooting Meaning

| Symptom | Likely meaning |
| --- | --- |
| Phone only charges, app sees no USB device | Phone is not USB host, cable is charge-only, USB-C role negotiated the wrong way, headset is asleep, or a hub/OTG adapter is needed. |
| USB device visible but no ADB interface | Quest is not exposing ADB; Developer Mode being off is the common cause. |
| USB permission granted but ADB times out | Headset debugging prompt was not accepted, headset is blocked/asleep, cable failed, or ADB authorization failed. |
| USB ADB works but Wi-Fi connect fails | Wrong Quest IP, different network/subnet, port `5555` not listening, or local network routing issue. |
| Wi-Fi ADB works until reboot | Expected for classic `adb tcpip`; use USB bootstrap again. |

## Status

The first public slice keeps the proven connection, shell probe, install,
launch, stop, package-list, foreground-query, and utility-key paths. Broader
device snapshots will be aligned with the Windows companion diagnostics model
in a later iteration.
