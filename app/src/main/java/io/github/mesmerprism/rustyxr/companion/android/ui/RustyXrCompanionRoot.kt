package io.github.mesmerprism.rustyxr.companion.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mesmerprism.rustyxr.companion.android.data.ApkTarget
import io.github.mesmerprism.rustyxr.companion.android.data.RuntimeProfile
import io.github.mesmerprism.rustyxr.companion.android.session.ActionProgressUiState
import io.github.mesmerprism.rustyxr.companion.android.session.LogLevel
import io.github.mesmerprism.rustyxr.companion.android.session.QuestSessionController
import io.github.mesmerprism.rustyxr.companion.android.session.QuestSessionUiState
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.AccentRed
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.AccentRedDeep
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.Ink
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.Line
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.Muted
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.Paper
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.SuccessInk
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.SurfaceWarm
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.SurfaceWarmAlt
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.WarningInk
import kotlin.math.roundToInt

private enum class AppScreen(val label: String) {
    Session("Session"),
    Control("Library"),
    Streams("Streams"),
    Monitor("Monitor"),
    Logs("Logs")
}

@Composable
fun RustyXrCompanionRoot(
    state: QuestSessionUiState,
    controller: QuestSessionController
) {
    var selectedScreenName by rememberSaveable { mutableStateOf(AppScreen.Session.name) }
    val selectedScreen = AppScreen.valueOf(selectedScreenName)

    Scaffold(
        containerColor = Paper,
        topBar = {
            HeaderStrip(
                serviceStatus = state.serviceStatus,
                catalogStatus = state.catalogRefreshStatus
            )
        },
        bottomBar = {
            BottomTabBar(
                selected = selectedScreen,
                onSelected = { selectedScreenName = it.name }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(innerPadding)
        ) {
            when (selectedScreen) {
                AppScreen.Session -> SessionScreen(state, controller)
                AppScreen.Streams -> StreamsScreen(state, controller)
                AppScreen.Monitor -> MonitorScreen(state, controller)
                AppScreen.Control -> ControlScreen(state, controller)
                AppScreen.Logs -> LogsScreen(state, controller)
            }
        }
    }
}

@Composable
private fun HeaderStrip(
    serviceStatus: String,
    catalogStatus: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text = "Field Controller",
            style = MaterialTheme.typography.labelMedium,
            color = Muted
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Rusty XR Companion",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = serviceStatus,
            style = MaterialTheme.typography.labelLarge,
            color = AccentRedDeep
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = catalogStatus,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Line, thickness = 1.dp)
    }
}

@Composable
private fun BottomTabBar(
    selected: AppScreen,
    onSelected: (AppScreen) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .border(width = 1.dp, color = Line)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppScreen.entries.forEach { screen ->
            val isSelected = screen == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelected(screen) },
                color = if (isSelected) SurfaceWarm else SurfaceWarmAlt,
                shadowElevation = if (isSelected) 1.dp else 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                shape = RoundedCornerShape(0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (isSelected) AccentRed else Color.Transparent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = screen.label,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) AccentRedDeep else Ink,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(
    state: QuestSessionUiState,
    controller: QuestSessionController
) {
    val selectedApp = state.apkTargets.firstOrNull { it.id == state.selectedAppId }
    val selectedBundle = state.apkBundles.firstOrNull { it.id == state.selectedBundleId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabPanel("Current Session", "Current Quest-side target, catalog source, and latest effect") {
                DetailBlock("Last action", state.lastActionLabel)
                DetailBlock("Last effect", state.lastActionDetail, mono = true)
                ProgressReadout(
                    label = "Live progress",
                    progress = state.lastActionProgress
                )
                DetailBlock("Manifest", state.latestManifestPath ?: "No manifest written yet.", mono = true)
                DetailBlock("Catalog source", state.catalogSourceLabel)
                DetailBlock("Catalog path", state.catalogManifestPath ?: "Using bundled sample library.", mono = true)
                DetailBlock("Selected app", selectedApp?.label ?: "No library app selected.")
                DetailBlock("Selected bundle", selectedBundle?.label ?: "No bundle selected.")
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Refresh Catalogs", modifier = Modifier.weight(1f)) {
                        controller.refreshCatalogs()
                    }
                    SecondaryActionButton("Write Manifest", modifier = Modifier.weight(1f)) {
                        controller.writeSessionManifest()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Line, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
                FeedbackBlock(
                    label = "Diagnostics Export",
                    summary = state.diagnosticsExportStatus,
                    detail = listOfNotNull(
                        state.diagnosticsExportPath,
                        state.diagnosticsFailureClass?.let { "Failure class: $it" }
                    ).joinToString(" — ").ifBlank { "Run a transport action, then export the trace as a recoverable bundle." },
                    monoDetail = true,
                    progress = state.diagnosticsExportProgress
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryActionButton("Export Diagnostics Bundle") {
                    controller.exportDiagnostics()
                }
            }
        }

        item {
            LabPanel("Quest Endpoint", "USB bootstrap first after reboot, then stay on hotspot Wi-Fi") {
                DetailBlock("Target endpoint", state.endpointDraft, mono = true)
                DetailBlock(
                    "Connected Quest endpoint",
                    state.activeEndpoint ?: "No active Quest Wi-Fi ADB link.",
                    mono = true
                )
                FeedbackBlock(
                    label = "Quest link",
                    summary = state.connectionSummary,
                    detail = state.connectionDetail,
                    monoDetail = true,
                    progress = state.connectionProgress
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailBlock(
                    label = "USB cable role",
                    value = "Phone must be on the USB host side. Open this app first, connect the Quest to the phone, keep the headset awake, and accept the USB debugging prompt in-headset. If the cable only charges, reseat it or use a phone-side OTG adapter or powered USB-C hub."
                )
                Spacer(modifier = Modifier.height(2.dp))
                FeedbackBlock(
                    label = "Phone network",
                    summary = state.phoneNetworkSummary,
                    detail = state.phoneNetworkDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeedbackBlock(
                    label = "USB diagnostics",
                    summary = state.usbSummary,
                    detail = state.usbDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.endpointDraft,
                    onValueChange = controller::setEndpointDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Quest IP:port", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                SecondaryActionButton("Probe USB ADB") {
                    controller.probeUsbAdb()
                }
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryActionButton("Enable Wi-Fi ADB From USB") {
                    controller.enableWifiAdbFromUsb()
                }
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryActionButton("Refresh USB Diagnostics") {
                    controller.refreshUsbDiagnostics()
                }
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryActionButton("Connect Quest") {
                    controller.connectQuest()
                }
            }
        }
    }
}

@Composable
private fun MonitorScreen(
    state: QuestSessionUiState,
    controller: QuestSessionController
) {
    val monitorValue = state.monitorValue

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabPanel("LSL Monitor", "Live runtime status, latest sample, and automatic reconnect progress") {
                Text(
                    text = monitorValue?.let(::formatValue) ?: "No sample",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AccentRedDeep
                )
                if (monitorValue != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { monitorValue.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = AccentRed,
                        trackColor = SurfaceWarmAlt
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                StatusLine("Status", state.monitorStatus)
                StatusLine("Detail", state.monitorDetail)
                StatusLine("Stream", state.monitorStreamName, mono = true)
                StatusLine("Type", state.monitorStreamType, mono = true)
                StatusLine("Channel", state.monitorChannelIndex.toString(), mono = true)
                StatusLine("Rate", "${state.monitorSampleRateHz.roundToInt()} Hz", mono = true)
            }
        }

        item {
            LabPanel("Monitor Filters", "Change the target stream contract and the monitor will restart") {
                OutlinedTextField(
                    value = state.monitorStreamName,
                    onValueChange = controller::setMonitorStreamName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Stream name", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.monitorStreamType,
                    onValueChange = controller::setMonitorStreamType,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Stream type", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.monitorChannelIndex.toString(),
                    onValueChange = controller::setMonitorChannelIndex,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Channel index", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun StreamsScreen(
    state: QuestSessionUiState,
    controller: QuestSessionController
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabPanel("OSC", "Dependency-free UDP control and diagnostics path") {
                FeedbackBlock(
                    label = "Send",
                    summary = state.oscSendStatus,
                    detail = state.oscSendDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.oscHostDraft,
                    onValueChange = controller::setOscHostDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.oscPortDraft,
                    onValueChange = controller::setOscPortDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Destination port", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.oscAddressDraft,
                    onValueChange = controller::setOscAddressDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.oscArgumentsDraft,
                    onValueChange = controller::setOscArgumentsDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Arguments", style = MaterialTheme.typography.labelLarge) },
                    minLines = 2,
                    maxLines = 5
                )
                Spacer(modifier = Modifier.height(10.dp))
                PrimaryActionButton("Send OSC Packet") {
                    controller.sendOscMessage()
                }
            }
        }

        item {
            LabPanel("OSC Listener", "Inspect incoming OSC from tools, bridges, or headset apps") {
                FeedbackBlock(
                    label = "Listener",
                    summary = state.oscListenStatus,
                    detail = state.oscListenDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.oscListenPortDraft,
                    onValueChange = controller::setOscListenPortDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Listen port", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Start Listener", modifier = Modifier.weight(1f)) {
                        controller.startOscListener()
                    }
                    SecondaryActionButton("Stop Listener", modifier = Modifier.weight(1f)) {
                        controller.stopOscListener()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (state.oscReceivedMessages.isEmpty()) {
                    EmptyMessage("No incoming OSC packets captured yet.")
                } else {
                    state.oscReceivedMessages.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelLarge,
                            color = Muted
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }

        item {
            LabPanel("Polar H10", "Phone-side availability and battery check") {
                Text(
                    text = state.polarBatteryPercent?.let { "$it%" } ?: "--",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AccentRedDeep
                )
                Spacer(modifier = Modifier.height(10.dp))
                FeedbackBlock(
                    label = "Status",
                    summary = state.polarStatus,
                    detail = state.polarDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusLine("Device", state.polarDeviceName ?: "None")
                StatusLine("Address", state.polarDeviceAddress ?: "Unknown", mono = true)
                StatusLine("RSSI", state.polarRssi?.let { "$it dBm" } ?: "Unknown", mono = true)
                StatusLine("HR service", if (state.polarHeartRateServiceVisible) "visible" else "unknown")
                StatusLine("PMD service", if (state.polarPmdServiceVisible) "visible" else "unknown")
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Start Polar Check", modifier = Modifier.weight(1f)) {
                        controller.startPolarMonitor()
                    }
                    SecondaryActionButton("Stop", modifier = Modifier.weight(1f)) {
                        controller.stopPolarMonitor()
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlScreen(
    state: QuestSessionUiState,
    controller: QuestSessionController
) {
    val selectedApp = state.apkTargets.firstOrNull { it.id == state.selectedAppId }
    val matchingHotloadProfiles = state.hotloadProfiles.filter { it.matchesTarget(selectedApp) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabPanel("APK Library", "Staged apps from the PC-synced manifest plus live Quest install state") {
                FeedbackBlock(
                    label = "Foreground App",
                    summary = state.foregroundStatus,
                    detail = state.foregroundDetail,
                    monoDetail = true,
                    progress = state.foregroundProgress
                )
                Spacer(modifier = Modifier.height(10.dp))
                FeedbackBlock(
                    label = "Installed Packages",
                    summary = state.installedPackagesStatus,
                    detail = state.installedPackagesDetail,
                    monoDetail = true,
                    progress = state.installedPackagesProgress
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (state.apkTargets.isEmpty()) {
                    EmptyMessage("No staged APK library is available yet.")
                } else {
                    state.apkTargets.forEach { target ->
                        SelectableRow(
                            selected = target.id == state.selectedAppId,
                            title = target.label,
                            meta = buildTargetMeta(target, state),
                            supporting = buildTargetSupporting(target),
                            onClick = { controller.selectApp(target.id) }
                        )
                    }
                }
            }
        }

        item {
            LabPanel("Bundle Installs", "Choose a named install set and push it to Quest in order") {
                FeedbackBlock(
                    label = "Bundle Install",
                    summary = state.bundleInstallStatus,
                    detail = state.bundleInstallDetail,
                    monoDetail = true,
                    progress = state.bundleInstallProgress
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (state.apkBundles.isEmpty()) {
                    EmptyMessage("No APK bundles are defined in the library manifest.")
                } else {
                    state.apkBundles.forEach { bundle ->
                        SelectableRow(
                            selected = bundle.id == state.selectedBundleId,
                            title = bundle.label,
                            meta = "${bundle.appIds.size} app(s)",
                            supporting = bundle.description.ifBlank { bundle.appIds.joinToString(", ") },
                            onClick = { controller.selectBundle(bundle.id) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                PrimaryActionButton("Install Selected Bundle") {
                    controller.installSelectedBundle()
                }
            }
        }

        item {
            LabPanel("App Actions", "Install, launch, and stop the selected library app on Quest") {
                FeedbackBlock(
                    label = "Install App",
                    summary = state.installStatus,
                    detail = state.installDetail,
                    monoDetail = true,
                    progress = state.installProgress
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryActionButton("Install Selected App") {
                    controller.installSelectedApk()
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Line, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
                FeedbackBlock(
                    label = "Launch / Stop",
                    summary = state.launchStatus,
                    detail = state.launchDetail,
                    monoDetail = true,
                    progress = state.launchProgress
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Launch Selected App", modifier = Modifier.weight(1f)) {
                        controller.launchSelectedPackage()
                    }
                    SecondaryActionButton("Force-stop Selected", modifier = Modifier.weight(1f)) {
                        controller.forceStopSelectedPackage()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("Refresh Active App", modifier = Modifier.weight(1f)) {
                        controller.refreshForegroundPackage()
                    }
                    SecondaryActionButton("List Installed Apps", modifier = Modifier.weight(1f)) {
                        controller.runUtility(
                            controller.utilityActions.first { it.utility == io.github.mesmerprism.rustyxr.companion.android.transport.AdbUtility.ListInstalledPackages }
                        )
                    }
                }
            }
        }

        item {
            LabPanel("Browser", "Open a URL on Quest and close the configured browser package") {
                FeedbackBlock(
                    label = "Browser Control",
                    summary = state.browserStatus,
                    detail = state.browserDetail,
                    monoDetail = true,
                    progress = state.browserProgress
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.browserUrlDraft,
                    onValueChange = controller::setBrowserUrlDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Quest URL", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusLine("Last URL", state.lastBrowserUrl ?: "No URL opened yet.", mono = true)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Open URL", modifier = Modifier.weight(1f)) {
                        controller.openBrowserUrl()
                    }
                    SecondaryActionButton("Close Browser", modifier = Modifier.weight(1f)) {
                        controller.closeSelectedBrowser()
                    }
                }
            }
        }

        item {
            LabPanel("Utilities", "High-frequency Quest navigation and device commands") {
                FeedbackBlock(
                    label = "Utility Status",
                    summary = state.utilityStatus,
                    detail = state.utilityDetail,
                    monoDetail = true,
                    progress = state.utilityProgress
                )
                Spacer(modifier = Modifier.height(12.dp))
                controller.utilityActions.chunked(2).forEach { rowActions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowActions.forEach { action ->
                            SecondaryActionButton(
                                label = action.label,
                                modifier = Modifier.weight(1f)
                            ) {
                                controller.runUtility(action)
                            }
                        }
                        if (rowActions.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        item {
            LabPanel("Runtime Preset", "Profiles imported from the preset catalog for the selected Quest app") {
                FeedbackBlock(
                    label = "Runtime Preset Upload",
                    summary = state.hotloadStatus,
                    detail = state.hotloadDetail,
                    monoDetail = true,
                    progress = state.hotloadProgress
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (matchingHotloadProfiles.isEmpty()) {
                    EmptyMessage("No runtime presets match the selected app package.")
                } else {
                    matchingHotloadProfiles.forEach { profile ->
                        val isLocal = state.userRuntimeProfileIds.contains(profile.id)

                        SelectableRow(
                            selected = profile.id == state.selectedHotloadId,
                            title = profile.label,
                            meta = buildRuntimeProfileMeta(profile, isLocal),
                            supporting = buildRuntimeProfileSupporting(profile),
                            onClick = { controller.selectHotload(profile.id) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                PrimaryActionButton("Upload Selected Runtime Preset") {
                    controller.uploadSelectedHotload()
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Line, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
                FeedbackBlock(
                    label = "Local Runtime Profile",
                    summary = state.profileEditorStatus,
                    detail = state.profileEditorDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.profileEditorNameDraft,
                    onValueChange = controller::setProfileEditorNameDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile name", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.profileEditorPackageIdsDraft,
                    onValueChange = controller::setProfileEditorPackageIdsDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Package IDs", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.profileEditorDescriptionDraft,
                    onValueChange = controller::setProfileEditorDescriptionDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description", style = MaterialTheme.typography.labelLarge) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.profileEditorValuesDraft,
                    onValueChange = controller::setProfileEditorValuesDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Launch extras", style = MaterialTheme.typography.labelLarge) },
                    minLines = 5,
                    maxLines = 8
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("New Local", modifier = Modifier.weight(1f)) {
                        controller.startNewRuntimeProfileDraft()
                    }
                    SecondaryActionButton("Copy Selected", modifier = Modifier.weight(1f)) {
                        controller.copySelectedRuntimeProfileToDraft()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Save Local", modifier = Modifier.weight(1f)) {
                        controller.saveRuntimeProfileDraft()
                    }
                    SecondaryActionButton("Delete Local", modifier = Modifier.weight(1f)) {
                        controller.deleteSelectedRuntimeProfile()
                    }
                }
            }
        }

        item {
            LabPanel("Device Preset", "Profiles imported from the bundled device preset catalog") {
                FeedbackBlock(
                    label = "Device Preset Status",
                    summary = state.deviceProfileStatus,
                    detail = state.deviceProfileDetail,
                    monoDetail = true,
                    progress = state.deviceProfileProgress
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (state.deviceProfiles.isEmpty()) {
                    EmptyMessage("No device profiles loaded.")
                } else {
                    state.deviceProfiles.forEach { profile ->
                        SelectableRow(
                            selected = profile.id == state.selectedDeviceProfileId,
                            title = profile.label,
                            meta = "${profile.props.size} prop(s)",
                            supporting = profile.description.ifBlank { profile.id },
                            onClick = { controller.selectDeviceProfile(profile.id) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                PrimaryActionButton("Apply Selected Device Preset") {
                    controller.applySelectedDeviceProfile()
                }
            }
        }
    }
}

@Composable
private fun LogsScreen(
    state: QuestSessionUiState,
    controller: QuestSessionController
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LabPanel("Latest Manifest", "Local session snapshot plus active Quest-side control state") {
                StatusLine("Path", state.latestManifestPath ?: "No manifest written yet.", mono = true)
                StatusLine("Catalog", state.catalogSourceLabel)
                StatusLine("Active App", state.activeForegroundPackageId ?: "Unknown", mono = true)
                StatusLine("Selected Bundle", state.selectedBundleId ?: "None", mono = true)
                StatusLine("Last URL", state.lastBrowserUrl ?: "None", mono = true)
                Spacer(modifier = Modifier.height(12.dp))
                SecondaryActionButton("Write Session Manifest") {
                    controller.writeSessionManifest()
                }
            }
        }

        item {
            LabPanel("Agent Commands", "Time-limited command window for PC-driven phone workflows") {
                FeedbackBlock(
                    label = "Command Mode",
                    summary = state.agentCommandStatus,
                    detail = state.agentCommandDetail,
                    monoDetail = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusLine(
                    "Last report",
                    state.agentCommandLastReportPath ?: "No agent command report yet.",
                    mono = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton("Enable 15 Min", modifier = Modifier.weight(1f)) {
                        controller.enableAgentCommands()
                    }
                    SecondaryActionButton("Disable", modifier = Modifier.weight(1f)) {
                        controller.disableAgentCommands()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryActionButton("Refresh Agent Status") {
                    controller.refreshAgentCommandStatus()
                }
            }
        }

        item {
            LabPanel("Activity Log", "Newest entries first") {
                if (state.logs.isEmpty()) {
                    EmptyMessage("No log entries yet.")
                } else {
                    state.logs.forEach { entry ->
                        val levelColor = when (entry.level) {
                            LogLevel.Info -> SuccessInk
                            LogLevel.Warning -> WarningInk
                            LogLevel.Failure -> AccentRedDeep
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = entry.level.name.uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = levelColor
                                )
                                Text(
                                    text = entry.timestamp,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Muted
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink
                            )
                            if (entry.detail.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = entry.detail,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabPanel(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(0.dp)),
        color = SurfaceWarm,
        border = androidx.compose.foundation.BorderStroke(2.dp, Ink),
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(AccentRed)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted
                )
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    mono: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Ink
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = if (mono) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun DetailBlock(
    label: String,
    value: String,
    mono: Boolean = false
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Ink
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = value,
        style = if (mono) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        color = Muted
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun FeedbackBlock(
    label: String,
    summary: String,
    detail: String,
    monoDetail: Boolean = false,
    progress: ActionProgressUiState = ActionProgressUiState()
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Ink
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = Ink
    )
    if (detail.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detail,
            style = if (monoDetail) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
    ProgressReadout(progress = progress)
}

@Composable
private fun ProgressReadout(
    label: String = "Progress",
    progress: ActionProgressUiState
) {
    if (!progress.active) {
        return
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Ink
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = progress.label.ifBlank { "Working..." },
        style = MaterialTheme.typography.labelLarge,
        color = AccentRedDeep
    )
    Spacer(modifier = Modifier.height(6.dp))
    val fraction = progress.fraction
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = AccentRed,
            trackColor = SurfaceWarmAlt
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = AccentRed,
            trackColor = SurfaceWarmAlt
        )
    }
    if (progress.detail.isNotBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = progress.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

private fun buildTargetMeta(
    target: ApkTarget,
    state: QuestSessionUiState
): String {
    val status = buildList {
        if (state.installedPackageIds.contains(target.packageId)) add("installed")
        if (state.activeForegroundPackageId == target.packageId) add("active")
    }
    return buildList {
        add(target.packageId)
        add(target.file)
        if (status.isNotEmpty()) add(status.joinToString(" / "))
    }.joinToString(" | ")
}

private fun buildTargetSupporting(
    target: ApkTarget
): String {
    return buildList {
        if (target.description.isNotBlank()) add(target.description)
        if (target.launchComponent.isNotBlank()) add("launch: ${target.launchComponent}")
        target.resolvedBrowserPackageId()?.let { add("browser: $it") }
        if (target.tags.isNotEmpty()) add("tags: ${target.tags.joinToString(", ")}")
    }.joinToString(" | ").ifBlank {
        "Staged library entry with no extra metadata."
    }
}

private fun buildRuntimeProfileMeta(
    profile: RuntimeProfile,
    isLocal: Boolean
): String {
    return buildList {
        if (isLocal) add("local")
        if (profile.channel.isNotBlank()) add(profile.channel)
        if (profile.version.isNotBlank()) add("v${profile.version}")
        if (profile.studyLock) add("study-lock")
        if (profile.hasLaunchExtras) add("${profile.values.size} extra(s)")
        if (profile.hasHotloadFile) add(profile.file)
    }.joinToString(" | ").ifBlank { "runtime profile" }
}

private fun buildRuntimeProfileSupporting(
    profile: RuntimeProfile
): String {
    return profile.description.ifBlank {
        when {
            profile.hasHotloadFile -> "CSV: ${profile.file}"
            profile.packageIds.isNotEmpty() -> "Packages: ${profile.packageIds.joinToString(", ")}"
            else -> "Launch extras are sent with Launch Selected App."
        }
    }
}

@Composable
private fun SelectableRow(
    selected: Boolean,
    title: String,
    meta: String,
    supporting: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(
                width = 2.dp,
                color = if (selected) Ink else Line,
                shape = RoundedCornerShape(0.dp)
            )
            .background(if (selected) SurfaceWarmAlt else SurfaceWarm)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(74.dp)
                .background(if (selected) AccentRed else Color.Transparent)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelLarge,
                color = AccentRedDeep
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentRed,
            contentColor = SurfaceWarm
        ),
        shape = RoundedCornerShape(0.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        shape = RoundedCornerShape(0.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
}

private fun formatValue(value: Float): String {
    return String.format("%.3f", value)
}

