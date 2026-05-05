# Agent Command Workflow

The Android app exposes a dedicated command activity for PC-based agents that
need to drive the phone while the phone talks to a selected Quest over Wi-Fi
ADB. This is a product feature, not a debug-only activity.

Command mode is disabled by default. In the phone app, open **Logs > Agent
Commands** and enable the 15 minute command window before running commands from
the PC. Debug builds also accept `--ez allow_dev_session true` so automated
local validation can start a temporary command window without changing release
behavior.

Reports are written to:

```text
/sdcard/Android/data/io.github.mesmerprism.rustyxr.companion.android/files/agent-commands/
```

`latest.json` always mirrors the newest report.

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
