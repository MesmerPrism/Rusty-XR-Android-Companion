package io.github.mesmerprism.rustyxr.companion.android.session

import io.github.mesmerprism.rustyxr.companion.android.data.ApkBundle
import io.github.mesmerprism.rustyxr.companion.android.data.ApkTarget
import io.github.mesmerprism.rustyxr.companion.android.data.DeviceProfile
import io.github.mesmerprism.rustyxr.companion.android.data.RuntimeProfile
import io.github.mesmerprism.rustyxr.companion.android.transport.AdbUtility

enum class LogLevel {
    Info,
    Warning,
    Failure
}

data class SessionLogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val detail: String = ""
)

data class UtilityAction(
    val utility: AdbUtility,
    val label: String,
    val description: String
)

data class ActionProgressUiState(
    val active: Boolean = false,
    val label: String = "",
    val detail: String = "",
    val fraction: Float? = null
)

data class QuestSessionUiState(
    val serviceStatus: String = "Foreground service starting...",
    val catalogRefreshStatus: String = "Loading staged or bundled library...",
    val catalogSourceLabel: String = "bundled sample",
    val catalogManifestPath: String? = null,
    val endpointDraft: String = "",
    val activeEndpoint: String? = null,
    val connectionSummary: String = "No Quest ADB session yet.",
    val connectionDetail: String = "Enable Wi-Fi ADB from USB after reboot, or connect the saved hotspot endpoint.",
    val phoneNetworkSummary: String = "Phone network has not been checked against a Quest endpoint yet.",
    val phoneNetworkDetail: String = "After USB bootstrap or a manual connect attempt, the app compares the Quest endpoint with the phone's local IPv4 interfaces.",
    val usbSummary: String = "USB diagnostics have not been refreshed yet.",
    val usbDetail: String = "Use Refresh USB Diagnostics after connecting the Quest to the phone. The phone must be on the USB host side for the headset-side ADB interface to appear.",
    val connectionProgress: ActionProgressUiState = ActionProgressUiState(),
    val installStatus: String = "No install request yet. Sync the APK library from the PC first.",
    val installDetail: String = "Single-app install pushes the staged APK from the phone to the Quest over ADB.",
    val installProgress: ActionProgressUiState = ActionProgressUiState(),
    val bundleInstallStatus: String = "No bundle install request yet.",
    val bundleInstallDetail: String = "Choose an APK bundle to install the listed apps in order.",
    val bundleInstallProgress: ActionProgressUiState = ActionProgressUiState(),
    val hotloadStatus: String = "No runtime preset upload yet.",
    val hotloadDetail: String = "Runtime profiles can push a CSV preset or supply Activity extras when launching the selected Quest package.",
    val hotloadProgress: ActionProgressUiState = ActionProgressUiState(),
    val profileEditorStatus: String = "No local runtime profile edit yet.",
    val profileEditorDetail: String = "Local profiles are stored in app-private catalog storage and merged with imported catalog profiles.",
    val profileEditorEditingId: String? = null,
    val profileEditorNameDraft: String = "",
    val profileEditorDescriptionDraft: String = "",
    val profileEditorPackageIdsDraft: String = "",
    val profileEditorValuesDraft: String = "",
    val deviceProfileStatus: String = "No device preset applied yet.",
    val deviceProfileDetail: String = "Device presets apply Quest setprop values and verify them immediately.",
    val deviceProfileProgress: ActionProgressUiState = ActionProgressUiState(),
    val launchStatus: String = "No app control request yet.",
    val launchDetail: String = "Launch and force-stop controls operate on the selected library app.",
    val launchProgress: ActionProgressUiState = ActionProgressUiState(),
    val browserStatus: String = "No browser command yet.",
    val browserDetail: String = "Open a URL in the configured Quest browser package, then close it with force-stop.",
    val browserProgress: ActionProgressUiState = ActionProgressUiState(),
    val utilityStatus: String = "No utility command yet.",
    val utilityDetail: String = "Use the utility buttons to inspect packages or control Quest navigation.",
    val utilityProgress: ActionProgressUiState = ActionProgressUiState(),
    val foregroundStatus: String = "Foreground package not checked yet.",
    val foregroundDetail: String = "Use Refresh Active App after connecting to Quest.",
    val foregroundProgress: ActionProgressUiState = ActionProgressUiState(),
    val activeForegroundPackageId: String? = null,
    val installedPackagesStatus: String = "Installed package list not loaded yet.",
    val installedPackagesDetail: String = "Use List Installed Packages after connecting to Quest.",
    val installedPackagesProgress: ActionProgressUiState = ActionProgressUiState(),
    val installedPackageIds: List<String> = emptyList(),
    val lastActionLabel: String = "No phone action sent yet.",
    val lastActionDetail: String = "Use the session controls to connect, install, launch, or inspect the Quest.",
    val lastActionProgress: ActionProgressUiState = ActionProgressUiState(),
    val monitorStatus: String = "LSL monitor not started yet.",
    val monitorDetail: String = "The bundled LSL runtime will report whether native monitoring is available on this build.",
    val monitorStreamName: String = "quest_monitor",
    val monitorStreamType: String = "quest.telemetry",
    val monitorChannelIndex: Int = 0,
    val monitorValue: Float? = null,
    val monitorSampleRateHz: Float = 0f,
    val oscHostDraft: String = "192.168.43.1",
    val oscPortDraft: String = "9000",
    val oscListenPortDraft: String = "9001",
    val oscAddressDraft: String = "/rusty-xr/probe",
    val oscArgumentsDraft: String = "float:0.75",
    val oscSendStatus: String = "No OSC packet sent yet.",
    val oscSendDetail: String = "Send a small OSC control or probe message to the selected Quest-side endpoint.",
    val oscListenStatus: String = "OSC listener is stopped.",
    val oscListenDetail: String = "Start the listener to inspect UDP OSC packets arriving on the phone.",
    val oscReceivedMessages: List<String> = emptyList(),
    val polarStatus: String = "Polar monitor not started yet.",
    val polarDetail: String = "Use the phone connection for Polar H10 availability, battery, and optional secondary storage without forwarding data to the headset.",
    val polarDeviceName: String? = null,
    val polarDeviceAddress: String? = null,
    val polarRssi: Int? = null,
    val polarBatteryPercent: Int? = null,
    val polarHeartRateServiceVisible: Boolean = false,
    val polarPmdServiceVisible: Boolean = false,
    val browserUrlDraft: String = "https://",
    val lastBrowserUrl: String? = null,
    val apkTargets: List<ApkTarget> = emptyList(),
    val apkBundles: List<ApkBundle> = emptyList(),
    val hotloadProfiles: List<RuntimeProfile> = emptyList(),
    val userRuntimeProfileIds: Set<String> = emptySet(),
    val deviceProfiles: List<DeviceProfile> = emptyList(),
    val selectedAppId: String? = null,
    val selectedBundleId: String? = null,
    val selectedHotloadId: String? = null,
    val selectedDeviceProfileId: String? = null,
    val latestManifestPath: String? = null,
    val agentCommandStatus: String = "Agent command mode is disabled.",
    val agentCommandDetail: String = "Enable a time-limited command window before running PC-driven app commands over ADB.",
    val agentCommandLastReportPath: String? = null,
    val diagnosticsExportStatus: String = "No diagnostics bundle exported yet.",
    val diagnosticsExportPath: String? = null,
    val diagnosticsExportProgress: ActionProgressUiState = ActionProgressUiState(),
    val diagnosticsFailureClass: String? = null,
    val logs: List<SessionLogEntry> = emptyList()
)
