# Agent Command Workflow

The Android app exposes a dedicated command activity for PC-based agents that
need to drive the phone while the phone talks to a selected Quest over Wi-Fi
ADB. This is a product feature, not a debug-only activity.

Command mode is disabled by default. In the phone app, open **Logs > Agent
Commands** and enable the 15 minute command window before running commands from
the PC. Debug builds also accept `--ez allow_dev_session true` so automated
local validation can start a temporary command window without changing release
behavior.

Reports and live progress files are written to:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/agent-commands/
```

`latest.json` always mirrors the newest report.
`current.json` mirrors the latest progress event while a command is active, and
each command also appends an `agent_command_<stamp>_<command>.events.jsonl`
progress log. This is the preferred evidence source for long relay sessions
because it records command acceptance, relay connect start, relay ACK, camera
selection, encoder start, first packet/frame, and final report writes before
the command finishes.

For relay or smoke runs that keep sockets open for more than a few seconds,
prepare the phone first so Android doze/background policy does not tear down
the command sockets while the phone is USB-powered:

```powershell
.\Tools\prepare-agent-command-phone.ps1 -DeviceSerial <phone-serial> -Mode Prepare
```

Restore the previous phone power and idle settings after the run:

```powershell
.\Tools\prepare-agent-command-phone.ps1 -DeviceSerial <phone-serial> -Mode Restore
```

## Command Shape

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command quest-suite `
  --es endpoint <quest-ip>:5555
```

Pull the newest report:

```powershell
adb -s <phone-serial> pull /sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/agent-commands/latest.json .
```

On unstable hotspot links, prefer the focused commands (`quest-connect`,
`quest-install`, `quest-launch`, `quest-foreground`, and `quest-stop`) over one
long `quest-suite` run. The suite is useful as a smoke test, but it opens
several ADB sessions in sequence and can expose Wi-Fi or headset daemon
instability that does not affect the individual workflow steps.

## Supported Commands

- `polar-pmd-smoke`: scan/connect to a Polar H10, start PMD ECG and ACC, and
  report decoded frame/sample counts.
- `q2q-relay`: run a bounded phone-native Rusty XR Q2Q relay test as sender,
  receiver, or duplex. The sender path emits synthetic or Camera2 H.264
  `RXYRVID1` packets from Android `MediaCodec`; the receiver path validates
  and counts incoming `RXYRVID1` H.264 packets and can optionally render one
  receiver lane to a phone surface.
- `quest-suite`: connect to the Quest, wake it, optionally
  install/launch/query/stop a target app, send home/back, then run non-critical
  package inventory and OSC UDP probes.
- `quest-inspect-usb`: inspect the phone-visible USB device inventory.
- `quest-probe-usb`: open a direct Quest USB ADB shell probe from the phone.
- `quest-enable-wifi-adb`: use the phone-to-headset USB ADB link to restart
  the selected Quest on `tcpip:5555`, resolve its Wi-Fi endpoint, and connect.
- `quest-connect`: connect only.
- `quest-install`: install a staged user-supplied APK by `apk_file` and
  `package_id` using Android package-manager streaming.
- `quest-launch`: launch `package_id`, optionally with `component` and
  `extras_json`.
- `quest-stop`: force-stop `package_id`.
- `quest-foreground`: query the current foreground package.
- `quest-list-packages`: list installed packages.
- `quest-utility`: run `home`, `back`, `wake`, `reboot`, or
  `list-installed-packages`.

## Examples

Polar ECG/ACC smoke:

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command polar-pmd-smoke `
  --el timeout_ms 45000
```

Q2Q relay duplex:

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command q2q-relay `
  --es q2q_mode duplex `
  --es relay_host <relay-host> `
  --ei relay_port 9443 `
  --es relay_token <shared-token> `
  --ez allow_dev_session true `
  --es send_session_id <phone-to-quest-session> `
  --es receive_session_id <quest-to-phone-session> `
  --es eyes left,right `
  --el session_duration_s 7200
```

`session_duration_s` is the single session budget. If `duration_ms` and
`connect_timeout_ms` are omitted, Q2Q uses that same budget for capture and
waiting. A value of `0` means unbounded until the command, app, or relay is
stopped manually. For setup calls where the other side may be late, prefer one
large session budget over several short unrelated timers.

Phone camera sender:

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command q2q-relay `
  --es q2q_mode sender `
  --es relay_host <relay-host> `
  --ei relay_port 9443 `
  --es relay_token <shared-token> `
  --ez allow_dev_session true `
  --es send_session_id <phone-camera-session> `
  --es eyes left,right `
  --es source_mode camera2_surface `
  --es quality_profile camera-native-max `
  --ei bitrate_bps 20000000 `
  --ei frame_rate_hz 60 `
  --el session_duration_s 7200
```

The default `camera-native-max` profile selects the largest MediaCodec-capable
Camera2 output size for the requested facing and prefers the highest available
AE FPS range. Lower resolution, frame rate, or bitrate should be an explicit
operator choice after the first high-quality run proves the camera and relay
path.

Convenience wrapper from the repo root:

```powershell
.\Tools\invoke-q2q-agent-command.ps1 `
  -DeviceSerial <phone-serial> `
  -Mode sender `
  -RelayHost <relay-host> `
  -RelayPort 9443 `
  -RelayToken <shared-token> `
  -SendSessionId <phone-camera-session> `
  -SourceMode camera2_surface `
  -SessionDurationS 7200 `
  -AllowDevSession
```

Install a staged APK:

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command quest-install `
  --es endpoint <quest-ip>:5555 `
  --es apk_file example-target.apk `
  --es package_id com.example.target
```

Launch with runtime profile extras:

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command quest-launch `
  --es endpoint <quest-ip>:5555 `
  --es package_id com.example.target `
  --es extras_json '{ "rustyxr.profile": "dev-smoke", "rustyxr.feature": "true" }'
```

Stop a target app:

```powershell
adb -s <phone-serial> shell am start `
  -a io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND `
  -n io.github.mesmerprism.rustyxr.companion.android/.agent.AgentCommandActivity `
  --es command quest-stop `
  --es endpoint <quest-ip>:5555 `
  --es package_id com.example.target
```

## Staged APK Requirement

`quest-install` uses the same phone-local APK staging folder as the UI:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/apks/
```

Use the existing staging helper from the repo root:

```powershell
.\Tools\push-staged-apks.ps1 -DeviceSerial <phone-serial> -ApkPath <path-to-your-app.apk>
```
