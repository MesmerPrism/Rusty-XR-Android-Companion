package io.github.mesmerprism.rustyxr.companion.android.session

import android.content.Context
import android.util.Log
import io.github.mesmerprism.rustyxr.companion.android.agent.AgentCommandGate
import io.github.mesmerprism.rustyxr.companion.android.data.ApkBundle
import io.github.mesmerprism.rustyxr.companion.android.data.ApkTarget
import io.github.mesmerprism.rustyxr.companion.android.data.DeviceProfile
import io.github.mesmerprism.rustyxr.companion.android.data.RuntimeProfile
import io.github.mesmerprism.rustyxr.companion.android.data.RustyXrCatalogLoader
import io.github.mesmerprism.rustyxr.companion.android.data.RustyXrCatalogs
import io.github.mesmerprism.rustyxr.companion.android.data.UserRuntimeProfileStore
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsBundleExporter
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsEvent
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsSnapshot
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsStage
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.FailureClass
import io.github.mesmerprism.rustyxr.companion.android.transport.AdbTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.AdbUtility
import io.github.mesmerprism.rustyxr.companion.android.transport.LiveAdbTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.MonitorReading
import io.github.mesmerprism.rustyxr.companion.android.transport.MonitorSubscription
import io.github.mesmerprism.rustyxr.companion.android.transport.MonitorTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.OscArgumentDraftParser
import io.github.mesmerprism.rustyxr.companion.android.transport.OscInboundPacket
import io.github.mesmerprism.rustyxr.companion.android.transport.OscMessage
import io.github.mesmerprism.rustyxr.companion.android.transport.OscUdpTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarSensorMonitor
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarSensorReading
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportOperationKind
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportOperationProgress
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportKind
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val AgentCommandEnableMs = 15L * 60L * 1000L

class QuestSessionController(
    appContext: Context,
    private val catalogLoader: RustyXrCatalogLoader,
    private val userRuntimeProfileStore: UserRuntimeProfileStore,
    private val manifestWriter: SessionManifestWriter,
    private val diagnosticsBundleExporter: DiagnosticsBundleExporter,
    private val adbTransport: AdbTransport,
    private val monitorTransport: MonitorTransport,
    private val oscTransport: OscUdpTransport,
    private val polarMonitor: PolarSensorMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val applicationContext = appContext.applicationContext
    private val agentCommandGate = AgentCommandGate(applicationContext)
    private val _uiState = MutableStateFlow(
        QuestSessionUiState(
            endpointDraft = "192.168.43.1:5555"
        )
    )
    private var monitorJob: Job? = null
    private var oscListenerJob: Job? = null
    private var polarMonitorJob: Job? = null
    private var transportProgressJob: Job? = null
    private var operationProgressJob: Job? = null
    private var lastMonitorLogKey: String? = null
    private var bundleInstallContext: BundleInstallContext? = null

    val uiState: StateFlow<QuestSessionUiState> = _uiState.asStateFlow()

    val utilityActions: List<UtilityAction> = listOf(
        UtilityAction(AdbUtility.Home, "Home", "Return to the Quest launcher."),
        UtilityAction(AdbUtility.Back, "Back", "Send a back key event to the active Quest app."),
        UtilityAction(AdbUtility.Wake, "Wake", "Wake the Quest display before sending other commands."),
        UtilityAction(AdbUtility.ListInstalledPackages, "List Apps", "Read the Quest package list over ADB."),
        UtilityAction(AdbUtility.Reboot, "Reboot", "Reboot the Quest from the active ADB session.")
    )

    private data class BundleInstallContext(
        val bundleLabel: String,
        val totalApps: Int,
        val currentIndex: Int,
        val currentTarget: ApkTarget
    )

    private data class CatalogSnapshot(
        val catalogs: RustyXrCatalogs,
        val runtimeProfiles: List<RuntimeProfile>,
        val userRuntimeProfileIds: Set<String>
    )

    init {
        appendLog(
            level = LogLevel.Info,
            message = "Rusty XR Companion initialized.",
            detail = "Quest-first ADB control is ready to load the staged APK library."
        )
        refreshCatalogs()
        restartMonitorSubscription()
        observeOperationProgress()
        refreshAgentCommandStatus()
    }

    private fun observeOperationProgress() {
        operationProgressJob?.cancel()
        operationProgressJob = scope.launch {
            adbTransport.operationProgress.collectLatest { progress ->
                progress ?: return@collectLatest
                _uiState.update { state ->
                    applyOperationProgress(state, progress)
                }
            }
        }
    }

    fun markForegroundServiceRunning(isRunning: Boolean) {
        val status = if (isRunning) {
            "Foreground service active. Background transport host is alive."
        } else {
            "Foreground service stopped."
        }
        _uiState.update { it.copy(serviceStatus = status) }
        appendLog(LogLevel.Info, status)
    }

    fun refreshCatalogs() {
        scope.launch {
            _uiState.update {
                it.copy(catalogRefreshStatus = "Refreshing staged or bundled APK library...")
            }

            runCatching { loadCatalogSnapshot() }
                .onSuccess { snapshot ->
                    val catalogs = snapshot.catalogs
                    _uiState.update { state ->
                        val selectedAppId = state.selectedAppId
                            ?.takeIf { requested -> catalogs.apkTargets.any { it.id == requested } }
                            ?: catalogs.apkTargets.firstOrNull()?.id
                        val selectedTarget = catalogs.apkTargets.firstOrNull { it.id == selectedAppId }
                        val selectedHotloadId = pickHotloadSelection(
                            requestedId = state.selectedHotloadId,
                            profiles = snapshot.runtimeProfiles,
                            target = selectedTarget
                        )
                        val selectedDeviceProfileId = state.selectedDeviceProfileId
                            ?.takeIf { requested -> catalogs.deviceProfiles.any { it.id == requested } }
                            ?: catalogs.deviceProfiles.firstOrNull()?.id
                        val selectedBundleId = state.selectedBundleId
                            ?.takeIf { requested -> catalogs.apkBundles.any { it.id == requested } }
                            ?: catalogs.apkBundles.firstOrNull()?.id

                        state.copy(
                            catalogRefreshStatus = "Loaded ${catalogs.source.label}: ${catalogs.apkTargets.size} app(s), ${catalogs.apkBundles.size} bundle(s), ${snapshot.runtimeProfiles.size} runtime preset(s), and ${catalogs.deviceProfiles.size} device preset(s).",
                            catalogSourceLabel = catalogs.source.label,
                            catalogManifestPath = catalogs.sourcePath,
                            apkTargets = catalogs.apkTargets,
                            apkBundles = catalogs.apkBundles,
                            hotloadProfiles = snapshot.runtimeProfiles,
                            userRuntimeProfileIds = snapshot.userRuntimeProfileIds,
                            deviceProfiles = catalogs.deviceProfiles,
                            selectedAppId = selectedAppId,
                            selectedBundleId = selectedBundleId,
                            selectedHotloadId = selectedHotloadId,
                            selectedDeviceProfileId = selectedDeviceProfileId
                        )
                    }

                    appendLog(
                        level = LogLevel.Info,
                        message = "APK library refreshed.",
                        detail = _uiState.value.catalogRefreshStatus
                    )
                }
                .onFailure { throwable ->
                    val message = throwable.message ?: "Unknown catalog load failure."
                    _uiState.update {
                        it.copy(catalogRefreshStatus = "Catalog refresh failed: $message")
                    }
                    appendLog(
                        level = LogLevel.Failure,
                        message = "Failed to load APK library.",
                        detail = message
                    )
                }
        }
    }

    fun setEndpointDraft(value: String) {
        _uiState.update { it.copy(endpointDraft = value) }
    }

    fun setMonitorStreamName(value: String) {
        _uiState.update { it.copy(monitorStreamName = value) }
        restartMonitorSubscription()
    }

    fun setMonitorStreamType(value: String) {
        _uiState.update { it.copy(monitorStreamType = value) }
        restartMonitorSubscription()
    }

    fun setMonitorChannelIndex(value: String) {
        val parsed = value.toIntOrNull() ?: 0
        _uiState.update { it.copy(monitorChannelIndex = parsed.coerceAtLeast(0)) }
        restartMonitorSubscription()
    }

    fun setOscHostDraft(value: String) {
        _uiState.update { it.copy(oscHostDraft = value) }
    }

    fun setOscPortDraft(value: String) {
        _uiState.update { it.copy(oscPortDraft = value) }
    }

    fun setOscListenPortDraft(value: String) {
        _uiState.update { it.copy(oscListenPortDraft = value) }
    }

    fun setOscAddressDraft(value: String) {
        _uiState.update { it.copy(oscAddressDraft = value) }
    }

    fun setOscArgumentsDraft(value: String) {
        _uiState.update { it.copy(oscArgumentsDraft = value) }
    }

    fun setBrowserUrlDraft(value: String) {
        _uiState.update { it.copy(browserUrlDraft = value) }
    }

    fun setProfileEditorNameDraft(value: String) {
        _uiState.update { it.copy(profileEditorNameDraft = value) }
    }

    fun setProfileEditorDescriptionDraft(value: String) {
        _uiState.update { it.copy(profileEditorDescriptionDraft = value) }
    }

    fun setProfileEditorPackageIdsDraft(value: String) {
        _uiState.update { it.copy(profileEditorPackageIdsDraft = value) }
    }

    fun setProfileEditorValuesDraft(value: String) {
        _uiState.update { it.copy(profileEditorValuesDraft = value) }
    }

    fun selectApp(appId: String) {
        _uiState.update { state ->
            val selectedTarget = state.apkTargets.firstOrNull { it.id == appId }
            state.copy(
                selectedAppId = appId,
                selectedHotloadId = pickHotloadSelection(
                    requestedId = state.selectedHotloadId,
                    profiles = state.hotloadProfiles,
                    target = selectedTarget
                )
            )
        }

        appendLog(LogLevel.Info, "Selected library app.", appId)
    }

    fun selectBundle(bundleId: String) {
        _uiState.update { it.copy(selectedBundleId = bundleId) }
        appendLog(LogLevel.Info, "Selected APK bundle.", bundleId)
    }

    fun selectHotload(profileId: String) {
        _uiState.update { it.copy(selectedHotloadId = profileId) }
        appendLog(LogLevel.Info, "Selected runtime preset.", profileId)
    }

    fun selectDeviceProfile(profileId: String) {
        _uiState.update { it.copy(selectedDeviceProfileId = profileId) }
        appendLog(LogLevel.Info, "Selected device profile.", profileId)
    }

    fun startNewRuntimeProfileDraft() {
        val selectedApp = selectedApkTarget()
        _uiState.update {
            it.copy(
                profileEditorStatus = "New local runtime profile draft.",
                profileEditorDetail = "Draft is scoped to ${selectedApp?.packageId ?: "all packages"}.",
                profileEditorEditingId = null,
                profileEditorNameDraft = selectedApp?.let { app -> "${app.label} Local Profile" } ?: "Local Runtime Profile",
                profileEditorDescriptionDraft = "",
                profileEditorPackageIdsDraft = selectedApp?.packageId ?: "*",
                profileEditorValuesDraft = ""
            )
        }
        appendLog(LogLevel.Info, "Started local runtime profile draft.", selectedApp?.packageId ?: "*")
    }

    fun copySelectedRuntimeProfileToDraft() {
        val profile = selectedHotloadProfile()
        val selectedApp = selectedApkTarget()
        if (profile == null) {
            _uiState.update {
                it.copy(
                    profileEditorStatus = "Copy blocked. No runtime profile selected.",
                    profileEditorDetail = "Select a runtime profile before copying it into a local draft."
                )
            }
            appendLog(LogLevel.Warning, "Local runtime profile copy blocked.", "No runtime profile selected.")
            return
        }

        val isLocal = uiState.value.userRuntimeProfileIds.contains(profile.id)
        _uiState.update {
            it.copy(
                profileEditorStatus = if (isLocal) {
                    "Editing local runtime profile ${profile.id}."
                } else {
                    "Copied catalog runtime profile into a local draft."
                },
                profileEditorDetail = if (profile.values.isEmpty()) {
                    "The selected profile has no launch extras. Add key=value lines before saving."
                } else {
                    "Loaded ${profile.values.size} launch extra(s) into the editor."
                },
                profileEditorEditingId = if (isLocal) profile.id else null,
                profileEditorNameDraft = if (isLocal) profile.label else "${profile.label} Copy",
                profileEditorDescriptionDraft = profile.description,
                profileEditorPackageIdsDraft = profile.packageIds.ifEmpty {
                    listOfNotNull(selectedApp?.packageId).ifEmpty { listOf("*") }
                }.joinToString(", "),
                profileEditorValuesDraft = profile.values.entries.joinToString("\n") { entry ->
                    "${entry.key}=${entry.value}"
                }
            )
        }
        appendLog(LogLevel.Info, "Copied runtime profile into editor.", profile.id)
    }

    fun saveRuntimeProfileDraft() {
        val state = uiState.value
        val selectedApp = selectedApkTarget()
        val label = state.profileEditorNameDraft.trim().ifBlank {
            selectedApp?.let { "${it.label} Local Profile" } ?: "Local Runtime Profile"
        }
        val valuesResult = parseLaunchExtrasDraft(state.profileEditorValuesDraft)
        if (valuesResult.isFailure) {
            val message = valuesResult.exceptionOrNull()?.message ?: "Invalid launch extras."
            _uiState.update {
                it.copy(
                    profileEditorStatus = "Local runtime profile not saved.",
                    profileEditorDetail = message
                )
            }
            appendLog(LogLevel.Warning, "Local runtime profile not saved.", message)
            return
        }

        val values = valuesResult.getOrThrow()
        if (values.isEmpty()) {
            val message = "Add at least one key=value launch extra before saving a local runtime profile."
            _uiState.update {
                it.copy(
                    profileEditorStatus = "Local runtime profile not saved.",
                    profileEditorDetail = message
                )
            }
            appendLog(LogLevel.Warning, "Local runtime profile not saved.", message)
            return
        }

        val packageIds = parsePackageIdsDraft(state.profileEditorPackageIdsDraft).ifEmpty {
            listOfNotNull(selectedApp?.packageId).ifEmpty { listOf("*") }
        }

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val existing = userRuntimeProfileStore.loadProfiles()
                    val existingIds = (uiState.value.hotloadProfiles.map { it.id } + existing.map { it.id })
                        .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
                    val editingId = state.profileEditorEditingId
                        ?.takeIf { requested -> state.userRuntimeProfileIds.contains(requested) }
                    val profileId = editingId ?: userRuntimeProfileStore.resolveUniqueProfileId(label, existingIds)
                    userRuntimeProfileStore.upsert(
                        RuntimeProfile(
                            id = profileId,
                            label = label,
                            description = state.profileEditorDescriptionDraft.trim(),
                            packageIds = packageIds,
                            values = values
                        )
                    )
                }
            }.onSuccess { saved ->
                reloadCatalogs(preferredRuntimeProfileId = saved.id)
                _uiState.update {
                    it.copy(
                        profileEditorStatus = "Saved local runtime profile ${saved.id}.",
                        profileEditorDetail = "Profile has ${saved.values.size} launch extra(s) for ${saved.packageIds.joinToString(", ")}.",
                        profileEditorEditingId = saved.id,
                        selectedHotloadId = saved.id
                    )
                }
                appendLog(LogLevel.Info, "Saved local runtime profile.", saved.id)
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unknown profile save failure."
                _uiState.update {
                    it.copy(
                        profileEditorStatus = "Local runtime profile save failed.",
                        profileEditorDetail = message
                    )
                }
                appendLog(LogLevel.Failure, "Local runtime profile save failed.", message)
            }
        }
    }

    fun deleteSelectedRuntimeProfile() {
        val state = uiState.value
        val selectedId = state.selectedHotloadId
        if (selectedId == null || !state.userRuntimeProfileIds.contains(selectedId)) {
            val message = "Only local runtime profiles can be deleted from the phone app."
            _uiState.update {
                it.copy(
                    profileEditorStatus = "Delete blocked.",
                    profileEditorDetail = message
                )
            }
            appendLog(LogLevel.Warning, "Local runtime profile delete blocked.", message)
            return
        }

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    userRuntimeProfileStore.delete(selectedId)
                }
            }.onSuccess { deleted ->
                if (deleted) {
                    reloadCatalogs(preferredRuntimeProfileId = null)
                    _uiState.update {
                        it.copy(
                            profileEditorStatus = "Deleted local runtime profile $selectedId.",
                            profileEditorDetail = "Catalog profiles remain read-only.",
                            profileEditorEditingId = null,
                            profileEditorNameDraft = "",
                            profileEditorDescriptionDraft = "",
                            profileEditorPackageIdsDraft = "",
                            profileEditorValuesDraft = ""
                        )
                    }
                    appendLog(LogLevel.Info, "Deleted local runtime profile.", selectedId)
                } else {
                    _uiState.update {
                        it.copy(
                            profileEditorStatus = "Local runtime profile was not found.",
                            profileEditorDetail = selectedId
                        )
                    }
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unknown profile delete failure."
                _uiState.update {
                    it.copy(
                        profileEditorStatus = "Local runtime profile delete failed.",
                        profileEditorDetail = message
                    )
                }
                appendLog(LogLevel.Failure, "Local runtime profile delete failed.", message)
            }
        }
    }

    // ACTIONS

    fun connectQuest() {
        val endpoint = uiState.value.endpointDraft.trim()
        if (endpoint.isEmpty()) {
            _uiState.update {
                it.copy(
                    connectionSummary = "Connect blocked. Quest endpoint is empty.",
                    connectionDetail = "Enter a Wi-Fi ADB endpoint before connecting.",
                    phoneNetworkSummary = "Phone network check skipped.",
                    phoneNetworkDetail = "A Quest endpoint is required before the app can compare networks.",
                    lastActionLabel = "Connect Quest blocked",
                    lastActionDetail = "Enter a Wi-Fi ADB endpoint before connecting."
                )
            }
            appendLog(LogLevel.Warning, "Quest endpoint is empty.", "Enter a Wi-Fi ADB endpoint before connecting.")
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Connect Quest",
                detail = "Trying hotspot endpoint $endpoint from the phone."
            ) { state ->
                state.copy(
                    connectionSummary = "Connecting to $endpoint...",
                    connectionDetail = "Trying the saved Quest hotspot endpoint from the phone.",
                    phoneNetworkSummary = "Checking the phone network against $endpoint...",
                    phoneNetworkDetail = "Comparing the Quest endpoint with the phone's local IPv4 interfaces."
                )
            }
            startTransportProgressFeedback("Connect Quest")
            val result = try {
                adbTransport.connect(endpoint)
            } finally {
                stopTransportProgressFeedback()
            }
            finishAction("Connect Quest", result) { state ->
                val activeEndpoint = if (result.kind == TransportKind.Success) {
                    result.endpoint ?: state.activeEndpoint
                } else {
                    null
                }
                state.copy(
                    activeEndpoint = activeEndpoint,
                    connectionSummary = result.summary,
                    connectionDetail = buildConnectionDetail(result, activeEndpoint),
                    phoneNetworkSummary = result.networkSummary.ifBlank { state.phoneNetworkSummary },
                    phoneNetworkDetail = result.networkDetail.ifBlank { state.phoneNetworkDetail }
                )
            }
        }
    }

    fun refreshUsbDiagnostics() {
        scope.launch {
            startAction(
                actionLabel = "USB Diagnostics",
                detail = "Inspecting the phone's current USB device list and interface exposure."
            ) { state ->
                state.copy(
                    usbSummary = "Refreshing USB diagnostics...",
                    usbDetail = "Reading UsbManager.deviceList and checking for an ADB-class interface."
                )
            }
            startTransportProgressFeedback("USB Diagnostics")
            val result = try {
                adbTransport.inspectUsb()
            } finally {
                stopTransportProgressFeedback()
            }
            finishAction("USB Diagnostics", result) { state ->
                state.copy(
                    usbSummary = result.summary,
                    usbDetail = result.detail
                )
            }
        }
    }

    fun probeUsbAdb() {
        scope.launch {
            startAction(
                actionLabel = "USB ADB Probe",
                detail = "Opening a simple Quest shell over USB, reading safe values, and verifying reversible ADB access."
            ) { state ->
                state.copy(
                    connectionSummary = "Probing Quest USB ADB...",
                    connectionDetail = "Checking whether the phone app can open a shell over the Quest ADB USB interface without touching Wi-Fi.",
                    usbSummary = "Preparing a direct Quest USB ADB probe...",
                    usbDetail = "This probe reads harmless Quest state and verifies reversible debug-property access."
                )
            }
            startTransportProgressFeedback("USB ADB Probe")
            val result = try {
                adbTransport.probeUsbAdb()
            } finally {
                stopTransportProgressFeedback()
            }
            finishAction("USB ADB Probe", result) { state ->
                state.copy(
                    connectionSummary = result.summary,
                    connectionDetail = buildConnectionDetail(result, state.activeEndpoint),
                    usbSummary = summarizeUsbFromConnectionResult(result, state.usbSummary),
                    usbDetail = detailUsbFromConnectionResult(result, state.usbDetail)
                )
            }
        }
    }

    fun enableWifiAdbFromUsb() {
        scope.launch {
            startAction(
                actionLabel = "USB Wi-Fi ADB",
                detail = "Looking for the Quest over USB, requesting access if needed, then switching it to tcpip:5555."
            ) { state ->
                state.copy(
                    connectionSummary = "Enabling Wi-Fi ADB from USB...",
                    connectionDetail = "Looking for a Quest USB debug session and resolving the hotspot endpoint.",
                    phoneNetworkSummary = "Waiting for a resolved Quest endpoint...",
                    phoneNetworkDetail = "Once USB bootstrap resolves the Quest IP, the app will compare it against the phone's local IPv4 interfaces.",
                    usbSummary = "Inspecting USB devices for an ADB-class Quest interface...",
                    usbDetail = "The phone app is checking whether Android exposes the Quest through UsbManager.deviceList."
                )
            }
            startTransportProgressFeedback("USB Wi-Fi ADB")
            val result = try {
                adbTransport.enableWifiFromUsb()
            } finally {
                stopTransportProgressFeedback()
            }
            finishAction("USB Wi-Fi ADB", result) { state ->
                val activeEndpoint = if (result.kind == TransportKind.Success) {
                    result.endpoint ?: state.activeEndpoint
                } else {
                    null
                }
                state.copy(
                    endpointDraft = result.endpoint ?: state.endpointDraft,
                    activeEndpoint = activeEndpoint,
                    connectionSummary = result.summary,
                    connectionDetail = buildConnectionDetail(result, activeEndpoint),
                    phoneNetworkSummary = result.networkSummary.ifBlank { state.phoneNetworkSummary },
                    phoneNetworkDetail = result.networkDetail.ifBlank { state.phoneNetworkDetail },
                    usbSummary = summarizeUsbFromConnectionResult(result, state.usbSummary),
                    usbDetail = detailUsbFromConnectionResult(result, state.usbDetail)
                )
            }
        }
    }

    fun installSelectedApk() {
        val app = selectedApkTarget()
        if (app == null) {
            _uiState.update {
                it.copy(
                    installStatus = "Install blocked. No library app selected.",
                    installDetail = "Pick an APK library app before running install.",
                    lastActionLabel = "Install App blocked",
                    lastActionDetail = "Pick an APK library app before running install."
                )
            }
            appendLog(LogLevel.Warning, "No library app selected.", "Pick an APK library app before running install.")
            return
        }
        if (app.file.isBlank()) {
            _uiState.update {
                it.copy(
                    installStatus = "Install blocked. No APK file configured.",
                    installDetail = "This catalog entry has metadata only. Stage a user-supplied APK or choose an entry with apkFile.",
                    lastActionLabel = "Install App blocked",
                    lastActionDetail = "No apkFile is configured for ${app.id}."
                )
            }
            appendLog(LogLevel.Warning, "No APK file configured.", app.id)
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Install App",
                detail = "Installing ${app.label} from staged file ${app.file}."
            ) { state ->
                state.copy(
                    installStatus = "Installing ${app.label}...",
                    installDetail = "Pushing ${app.file} from phone storage to the Quest over ADB."
                )
            }
            val result = adbTransport.install(app.file, app.packageId)
            finishAction("Install App", result) { state ->
                state.copy(
                    installStatus = result.summary,
                    installDetail = buildActionDetail(result.detail, state.activeEndpoint)
                )
            }
        }
    }

    fun installSelectedBundle() {
        val bundle = selectedBundle()
        if (bundle == null) {
            _uiState.update {
                it.copy(
                    bundleInstallStatus = "Bundle install blocked. No bundle selected.",
                    bundleInstallDetail = "Choose an APK bundle before installing it.",
                    lastActionLabel = "Install Bundle blocked",
                    lastActionDetail = "Choose an APK bundle before installing it."
                )
            }
            appendLog(LogLevel.Warning, "No APK bundle selected.", "Choose an APK bundle before installing it.")
            return
        }

        val targets = bundle.appIds.mapNotNull { appId ->
            uiState.value.apkTargets.firstOrNull { it.id == appId }
        }
        if (targets.size != bundle.appIds.size) {
            _uiState.update {
                it.copy(
                    bundleInstallStatus = "Bundle install blocked. Bundle entries are incomplete.",
                    bundleInstallDetail = "Refresh the APK library so all bundle app ids resolve to staged apps.",
                    lastActionLabel = "Install Bundle blocked",
                    lastActionDetail = "Bundle references missing app ids."
                )
            }
            appendLog(LogLevel.Warning, "Bundle app ids are missing.", bundle.id)
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Install Bundle",
                detail = "Installing ${bundle.label} with ${targets.size} app(s) in order."
            ) { state ->
                state.copy(
                    bundleInstallStatus = "Installing ${bundle.label}...",
                    bundleInstallDetail = "Preparing the ordered bundle install."
                )
            }

            val succeeded = mutableListOf<String>()
            var failure: TransportResult? = null
            try {
                for ((index, target) in targets.withIndex()) {
                    bundleInstallContext = BundleInstallContext(
                        bundleLabel = bundle.label,
                        totalApps = targets.size,
                        currentIndex = index,
                        currentTarget = target
                    )
                    _uiState.update { state ->
                        val progress = ActionProgressUiState(
                            active = true,
                            label = "App ${index + 1}/${targets.size} queued",
                            detail = "Preparing ${target.label} from ${target.file}.",
                            fraction = index.toFloat() / targets.size.toFloat()
                        )
                        state.copy(
                            bundleInstallStatus = "Installing ${bundle.label} (${index + 1}/${targets.size})...",
                            bundleInstallDetail = "Installing ${target.label} from ${target.file}.",
                            bundleInstallProgress = progress,
                            lastActionProgress = progress
                        )
                    }

                    val result = adbTransport.install(target.file, target.packageId)
                    recordTransport("Install Bundle ${target.label}", result, bundle.id)
                    if (result.kind == TransportKind.Success) {
                        succeeded += target.label
                        _uiState.update { state ->
                            val completed = index + 1
                            val progress = ActionProgressUiState(
                                active = true,
                                label = "Installed $completed/${targets.size} app(s)",
                                detail = if (completed < targets.size) {
                                    "${target.label} finished. Preparing ${targets[completed].label} next."
                                } else {
                                    "${target.label} finished. Finalizing bundle result."
                                },
                                fraction = completed.toFloat() / targets.size.toFloat()
                            )
                            state.copy(
                                bundleInstallDetail = progress.detail,
                                bundleInstallProgress = progress,
                                lastActionProgress = progress
                            )
                        }
                    } else {
                        failure = result
                        break
                    }
                }
            } finally {
                bundleInstallContext = null
            }

            val finalResult = if (failure == null) {
                TransportResult(
                    kind = TransportKind.Success,
                    summary = "Installed bundle ${bundle.label}.",
                    detail = "Installed ${succeeded.size} app(s): ${succeeded.joinToString(", ")}."
                )
            } else {
                val failed = failure
                TransportResult(
                    kind = TransportKind.Error,
                    summary = "Bundle install failed for ${bundle.label}.",
                    detail = buildString {
                        append("Installed ${succeeded.size} app(s) before the failure.")
                        if (succeeded.isNotEmpty()) {
                            append(" Completed: ${succeeded.joinToString(", ")}.")
                        }
                        append(" ${failed.summary} ${failed.detail}".trim())
                    }.trim()
                )
            }

            finishAction("Install Bundle", finalResult) { state ->
                state.copy(
                    bundleInstallStatus = finalResult.summary,
                    bundleInstallDetail = buildActionDetail(finalResult.detail, state.activeEndpoint)
                )
            }
        }
    }

    fun uploadSelectedHotload() {
        val app = selectedApkTarget()
        val profile = selectedHotloadProfile()
        if (app == null || profile == null) {
            _uiState.update {
                it.copy(
                    hotloadStatus = "Runtime preset upload blocked.",
                    hotloadDetail = "Select both a library app and a runtime preset first.",
                    lastActionLabel = "Upload Runtime Preset blocked",
                    lastActionDetail = "Select both a library app and a runtime preset first."
                )
            }
            appendLog(
                LogLevel.Warning,
                "Runtime preset upload blocked.",
                "Select both a library app and a runtime preset first."
            )
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Upload Runtime Preset",
                detail = if (profile.hasHotloadFile) {
                    "Uploading ${profile.file} to ${app.packageId}."
                } else {
                    "Selected ${profile.id} for launch extras on ${app.packageId}."
                }
            ) { state ->
                state.copy(
                    hotloadStatus = if (profile.hasHotloadFile) {
                        "Uploading ${profile.file}..."
                    } else {
                        "Runtime profile selected for launch."
                    },
                    hotloadDetail = if (profile.hasHotloadFile) {
                        "Writing runtime_overrides.csv for ${app.packageId} over ADB."
                    } else {
                        "This profile supplies ${profile.values.size} Activity extra(s); use Launch Selected App to send them."
                    }
                )
            }
            if (!profile.hasHotloadFile) {
                val result = TransportResult(
                    kind = TransportKind.Success,
                    summary = "Runtime profile ${profile.id} ready for launch.",
                    detail = "No CSV hotload file is attached. Launch will pass ${profile.values.size} Activity extra(s)."
                )
                finishAction("Upload Runtime Preset", result) { state ->
                    state.copy(
                        hotloadStatus = result.summary,
                        hotloadDetail = result.detail
                    )
                }
                return@launch
            }
            val result = adbTransport.pushHotload(app.packageId, profile.file)
            finishAction("Upload Runtime Preset", result) { state ->
                state.copy(
                    hotloadStatus = result.summary,
                    hotloadDetail = buildActionDetail(result.detail, state.activeEndpoint)
                )
            }
        }
    }

    fun applySelectedDeviceProfile() {
        val profile = selectedDeviceProfile()
        if (profile == null) {
            _uiState.update {
                it.copy(
                    deviceProfileStatus = "Device preset apply blocked.",
                    deviceProfileDetail = "Select a device preset before applying device properties.",
                    lastActionLabel = "Apply Device Preset blocked",
                    lastActionDetail = "Select a device preset before applying device properties."
                )
            }
            appendLog(
                LogLevel.Warning,
                "Device preset apply blocked.",
                "Select a device preset before applying device properties."
            )
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Apply Device Preset",
                detail = "Applying ${profile.id} with ${profile.props.size} verified property updates."
            ) { state ->
                state.copy(
                    deviceProfileStatus = "Applying ${profile.id}...",
                    deviceProfileDetail = "Sending Quest setprop commands and reading each property back."
                )
            }
            val result = adbTransport.applyDeviceProfile(profile.id, profile.props)
            finishAction("Apply Device Preset", result) { state ->
                state.copy(
                    deviceProfileStatus = result.summary,
                    deviceProfileDetail = buildActionDetail(result.detail, state.activeEndpoint)
                )
            }
        }
    }

    fun launchSelectedPackage() {
        val app = selectedApkTarget()
        val profile = selectedHotloadProfile()
        val launchExtras = profile?.values.orEmpty()
        if (app == null) {
            _uiState.update {
                it.copy(
                    launchStatus = "Launch blocked. No library app selected.",
                    launchDetail = "Select a library app before issuing launch.",
                    lastActionLabel = "Launch App blocked",
                    lastActionDetail = "Select a library app before issuing launch."
                )
            }
            appendLog(LogLevel.Warning, "Launch blocked.", "Select a library app before issuing launch.")
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Launch App",
                detail = buildString {
                    append("Launching ${app.packageId}")
                    append(app.launchComponent.takeIf { it.isNotBlank() }?.let { " via $it" }.orEmpty())
                    if (launchExtras.isNotEmpty()) {
                        append(" with runtime profile ${profile?.id}.")
                    } else {
                        append(".")
                    }
                }
            ) { state ->
                state.copy(
                    launchStatus = "Launching ${app.label}...",
                    launchDetail = if (app.launchComponent.isNotBlank()) {
                        if (launchExtras.isEmpty()) {
                            "Sending an explicit launch component over ADB."
                        } else {
                            "Sending an explicit launch component with ${launchExtras.size} runtime extra(s)."
                        }
                    } else {
                        if (launchExtras.isEmpty()) {
                            "Sending force-stop and launcher commands over ADB."
                        } else {
                            "Sending a launcher Activity intent with ${launchExtras.size} runtime extra(s)."
                        }
                    }
                )
            }
            val result = if (app.launchComponent.isNotBlank()) {
                adbTransport.launchIntent(app.packageId, app.launchComponent, launchExtras)
            } else {
                adbTransport.launch(app.packageId, launchExtras)
            }
            finishAction("Launch App", result) { state ->
                state.copy(
                    launchStatus = result.summary,
                    launchDetail = buildActionDetail(result.detail, state.activeEndpoint),
                    activeForegroundPackageId = if (result.kind == TransportKind.Success) app.packageId else state.activeForegroundPackageId,
                    foregroundStatus = if (result.kind == TransportKind.Success) {
                        "Foreground package assumed to be ${app.packageId} after launch."
                    } else {
                        state.foregroundStatus
                    },
                    foregroundDetail = if (result.kind == TransportKind.Success) {
                        "Run Refresh Active App to verify the currently resumed Quest package."
                    } else {
                        state.foregroundDetail
                    }
                )
            }
        }
    }

    fun forceStopSelectedPackage() {
        val app = selectedApkTarget()
        if (app == null) {
            _uiState.update {
                it.copy(
                    launchStatus = "Force-stop blocked. No library app selected.",
                    launchDetail = "Select a library app before issuing force-stop.",
                    lastActionLabel = "Force-stop blocked",
                    lastActionDetail = "Select a library app before issuing force-stop."
                )
            }
            appendLog(LogLevel.Warning, "Force-stop blocked.", "Select a library app before issuing force-stop.")
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Force-stop App",
                detail = "Stopping ${app.packageId} on Quest."
            ) { state ->
                state.copy(
                    launchStatus = "Force-stopping ${app.label}...",
                    launchDetail = "Sending am force-stop over ADB."
                )
            }
            val result = adbTransport.forceStop(app.packageId)
            finishAction("Force-stop App", result) { state ->
                state.copy(
                    launchStatus = result.summary,
                    launchDetail = buildActionDetail(result.detail, state.activeEndpoint),
                    activeForegroundPackageId = state.activeForegroundPackageId.takeUnless { it == app.packageId }
                )
            }
        }
    }

    fun openBrowserUrl() {
        val app = selectedApkTarget()
        val url = uiState.value.browserUrlDraft.trim()
        if (app == null) {
            _uiState.update {
                it.copy(
                    browserStatus = "Browser open blocked. No library app selected.",
                    browserDetail = "Select a library app so the app can resolve the preferred browser package.",
                    lastActionLabel = "Open Browser blocked",
                    lastActionDetail = "Select a library app before opening a URL."
                )
            }
            appendLog(LogLevel.Warning, "Browser open blocked.", "Select a library app before opening a URL.")
            return
        }
        if (url.isEmpty()) {
            _uiState.update {
                it.copy(
                    browserStatus = "Browser open blocked. URL is empty.",
                    browserDetail = "Enter a URL before opening the Quest browser.",
                    lastActionLabel = "Open Browser blocked",
                    lastActionDetail = "Enter a URL before opening the Quest browser."
                )
            }
            appendLog(LogLevel.Warning, "Browser open blocked.", "Enter a URL before opening the Quest browser.")
            return
        }

        val browserPackageId = app.resolvedBrowserPackageId().orEmpty()
        scope.launch {
            startAction(
                actionLabel = "Open Browser",
                detail = "Opening $url on Quest${browserPackageId.takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty()}."
            ) { state ->
                state.copy(
                    browserStatus = "Opening browser...",
                    browserDetail = if (browserPackageId.isNotBlank()) {
                        "Launching VIEW intent in $browserPackageId."
                    } else {
                        "Launching a generic VIEW intent on Quest."
                    }
                )
            }
            val result = adbTransport.openUrl(url, browserPackageId)
            finishAction("Open Browser", result) { state ->
                state.copy(
                    browserStatus = result.summary,
                    browserDetail = buildActionDetail(result.detail, state.activeEndpoint),
                    lastBrowserUrl = if (result.kind == TransportKind.Success) url else state.lastBrowserUrl,
                    activeForegroundPackageId = when {
                        result.kind == TransportKind.Success && browserPackageId.isNotBlank() -> browserPackageId
                        else -> state.activeForegroundPackageId
                    }
                )
            }
        }
    }

    fun closeSelectedBrowser() {
        val app = selectedApkTarget()
        val browserPackageId = app?.resolvedBrowserPackageId()
        if (app == null || browserPackageId == null) {
            _uiState.update {
                it.copy(
                    browserStatus = "Close browser blocked. No browser package is configured.",
                    browserDetail = "Set browserPackageId in the staged library manifest, or tag the selected app as a browser target.",
                    lastActionLabel = "Close Browser blocked",
                    lastActionDetail = "No browser package is configured for the selected app."
                )
            }
            appendLog(LogLevel.Warning, "Close browser blocked.", "No browser package is configured.")
            return
        }

        scope.launch {
            startAction(
                actionLabel = "Close Browser",
                detail = "Force-stopping $browserPackageId on Quest."
            ) { state ->
                state.copy(
                    browserStatus = "Closing browser...",
                    browserDetail = "Force-stopping the configured Quest browser package."
                )
            }
            val result = adbTransport.forceStop(browserPackageId)
            finishAction("Close Browser", result) { state ->
                state.copy(
                    browserStatus = result.summary,
                    browserDetail = buildActionDetail(result.detail, state.activeEndpoint),
                    activeForegroundPackageId = state.activeForegroundPackageId.takeUnless { it == browserPackageId }
                )
            }
        }
    }

    fun refreshForegroundPackage() {
        scope.launch {
            startAction(
                actionLabel = "Refresh Active App",
                detail = "Querying the currently resumed Quest package."
            ) { state ->
                state.copy(
                    foregroundStatus = "Refreshing active app...",
                    foregroundDetail = "Reading dumpsys activity activities over Quest ADB."
                )
            }
            val result = adbTransport.queryForegroundPackage()
            finishAction("Refresh Active App", result) { state ->
                state.copy(
                    foregroundStatus = result.summary,
                    foregroundDetail = buildActionDetail(result.detail, state.activeEndpoint),
                    activeForegroundPackageId = result.packageId ?: state.activeForegroundPackageId
                )
            }
        }
    }

    fun runUtility(action: UtilityAction) {
        if (action.utility == AdbUtility.ListInstalledPackages) {
            listInstalledPackages()
            return
        }

        scope.launch {
            startAction(
                actionLabel = action.label,
                detail = action.description
            ) { state ->
                state.copy(
                    utilityStatus = "Running ${action.label}...",
                    utilityDetail = action.description
                )
            }
            val result = adbTransport.runUtility(action.utility)
            finishAction(action.label, result, action.description) { state ->
                state.copy(
                    utilityStatus = result.summary,
                    utilityDetail = buildActionDetail(result.detail, state.activeEndpoint)
                )
            }
        }
    }

    fun sendOscMessage() {
        val state = uiState.value
        val port = state.oscPortDraft.toIntOrNull()
        if (port == null || port !in 1..65535) {
            val detail = "OSC destination port must be between 1 and 65535."
            _uiState.update {
                it.copy(
                    oscSendStatus = "OSC packet not sent.",
                    oscSendDetail = detail
                )
            }
            appendLog(LogLevel.Warning, "OSC packet not sent.", detail)
            return
        }

        val message = runCatching {
            OscMessage(
                address = state.oscAddressDraft.trim(),
                arguments = OscArgumentDraftParser.parse(state.oscArgumentsDraft)
            )
        }.getOrElse { throwable ->
            val detail = throwable.message ?: "Invalid OSC message draft."
            _uiState.update {
                it.copy(
                    oscSendStatus = "OSC packet not sent.",
                    oscSendDetail = detail
                )
            }
            appendLog(LogLevel.Warning, "OSC packet not sent.", detail)
            return
        }

        scope.launch {
            _uiState.update {
                it.copy(
                    oscSendStatus = "Sending OSC packet...",
                    oscSendDetail = "${message.summary()} -> ${state.oscHostDraft.trim()}:$port"
                )
            }
            runCatching {
                oscTransport.send(
                    host = state.oscHostDraft,
                    port = port,
                    message = message
                )
            }.onSuccess { receipt ->
                _uiState.update {
                    it.copy(
                        oscSendStatus = receipt.summary(),
                        oscSendDetail = receipt.detail()
                    )
                }
                appendLog(LogLevel.Info, receipt.summary(), receipt.detail())
            }.onFailure { throwable ->
                val detail = throwable.message ?: "OSC UDP send failed."
                _uiState.update {
                    it.copy(
                        oscSendStatus = "OSC send failed.",
                        oscSendDetail = detail
                    )
                }
                appendLog(LogLevel.Failure, "OSC send failed.", detail)
            }
        }
    }

    fun startOscListener() {
        val port = uiState.value.oscListenPortDraft.toIntOrNull()
        if (port == null || port !in 1..65535) {
            val detail = "OSC listen port must be between 1 and 65535."
            _uiState.update {
                it.copy(
                    oscListenStatus = "OSC listener not started.",
                    oscListenDetail = detail
                )
            }
            appendLog(LogLevel.Warning, "OSC listener not started.", detail)
            return
        }

        oscListenerJob?.cancel()
        oscListenerJob = scope.launch {
            _uiState.update {
                it.copy(
                    oscListenStatus = "OSC listener running.",
                    oscListenDetail = "Listening for UDP OSC packets on phone port $port."
                )
            }
            appendLog(LogLevel.Info, "OSC listener started.", "port=$port")

            runCatching {
                oscTransport.listen(port).collect { packet ->
                    applyOscInboundPacket(packet)
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    return@onFailure
                }
                val detail = throwable.message ?: "OSC listener failed."
                _uiState.update {
                    it.copy(
                        oscListenStatus = "OSC listener stopped.",
                        oscListenDetail = detail
                    )
                }
                appendLog(LogLevel.Failure, "OSC listener stopped.", detail)
            }
        }
    }

    fun stopOscListener() {
        oscListenerJob?.cancel()
        oscListenerJob = null
        _uiState.update {
            it.copy(
                oscListenStatus = "OSC listener stopped.",
                oscListenDetail = "No incoming UDP OSC packets are being inspected."
            )
        }
        appendLog(LogLevel.Info, "OSC listener stopped.")
    }

    fun startPolarMonitor() {
        polarMonitorJob?.cancel()
        polarMonitorJob = scope.launch {
            appendLog(LogLevel.Info, "Polar monitor started.")
            polarMonitor.monitor().collect { reading ->
                applyPolarReading(reading)
            }
        }
    }

    fun stopPolarMonitor() {
        polarMonitorJob?.cancel()
        polarMonitorJob = null
        _uiState.update {
            it.copy(
                polarStatus = "Polar monitor stopped.",
                polarDetail = "BLE scan and GATT connection are closed."
            )
        }
        appendLog(LogLevel.Info, "Polar monitor stopped.")
    }

    fun writeSessionManifest() {
        scope.launch {
            startAction(
                actionLabel = "Write Session Manifest",
                detail = "Writing a local session snapshot into phone storage."
            ) { state -> state }
            _uiState.update { state ->
                state.withActionProgress(
                    actionLabel = "Write Session Manifest",
                    progress = ActionProgressUiState(
                        active = true,
                        label = "Writing session snapshot",
                        detail = "Saving the current session state into app-local storage.",
                        fraction = 0.55f
                    )
                )
            }
            val manifest = manifestWriter.write(
                state = uiState.value,
                selectedApk = selectedApkTarget(),
                selectedBundle = selectedBundle(),
                selectedHotload = selectedHotloadProfile(),
                selectedDeviceProfile = selectedDeviceProfile()
            )
            _uiState.update {
                it.copy(
                    latestManifestPath = manifest.absolutePath,
                    lastActionLabel = "Write Session Manifest",
                    lastActionDetail = "Wrote ${manifest.absolutePath}",
                    lastActionProgress = ActionProgressUiState()
                )
            }
            appendLog(LogLevel.Info, "Session manifest written.", manifest.absolutePath)
        }
    }

    fun exportDiagnostics() {
        scope.launch {
            startAction(
                actionLabel = "Export Diagnostics",
                detail = "Exporting the latest diagnostics trace as a recoverable bundle."
            ) { state ->
                state.copy(
                    diagnosticsExportStatus = "Exporting diagnostics bundle...",
                    diagnosticsExportPath = null
                )
            }

            val snapshot = (adbTransport as? LiveAdbTransport)?.latestDiagnostics()
            if (snapshot == null || snapshot.events.isEmpty()) {
                _uiState.update {
                    it.clearActionProgress("Export Diagnostics").copy(
                        diagnosticsExportStatus = "No diagnostics trace available. Run a transport action first.",
                        lastActionLabel = "Export Diagnostics",
                        lastActionDetail = "No diagnostics events to export."
                    )
                }
                appendLog(LogLevel.Warning, "Diagnostics export skipped.", "No transport trace recorded yet.")
                return@launch
            }

            _uiState.update { state ->
                state.withActionProgress(
                    actionLabel = "Export Diagnostics",
                    progress = ActionProgressUiState(
                        active = true,
                        label = "Writing diagnostics bundle",
                        detail = "Packing manifest, transport trace, and UI summary into a zip bundle.",
                        fraction = 0.55f
                    )
                )
            }
            runCatching {
                diagnosticsBundleExporter.export(snapshot, uiState.value)
            }.onSuccess { bundleFile ->
                val failureLabel = snapshot.failureClass.takeIf { it != FailureClass.None }?.name
                _uiState.update {
                    it.clearActionProgress("Export Diagnostics").copy(
                        diagnosticsExportStatus = "Exported diagnostics bundle.",
                        diagnosticsExportPath = bundleFile.absolutePath,
                        diagnosticsFailureClass = failureLabel,
                        lastActionLabel = "Export Diagnostics",
                        lastActionDetail = "Bundle: ${bundleFile.absolutePath}"
                    )
                }
                appendLog(LogLevel.Info, "Diagnostics bundle exported.", bundleFile.absolutePath)
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unknown export failure."
                _uiState.update {
                    it.clearActionProgress("Export Diagnostics").copy(
                        diagnosticsExportStatus = "Diagnostics export failed: $message",
                        lastActionLabel = "Export Diagnostics",
                        lastActionDetail = message
                    )
                }
                appendLog(LogLevel.Failure, "Diagnostics export failed.", message)
            }
        }
    }

    fun enableAgentCommands() {
        val snapshot = agentCommandGate.enableFor(AgentCommandEnableMs)
        _uiState.update {
            it.copy(
                agentCommandStatus = "Agent command mode enabled.",
                agentCommandDetail = "PC-driven commands are accepted until ${snapshot.enabledUntilUtc}.",
                agentCommandLastReportPath = snapshot.lastReportPath
            )
        }
        appendLog(LogLevel.Info, "Agent command mode enabled.", snapshot.enabledUntilUtc.orEmpty())
    }

    fun disableAgentCommands() {
        val snapshot = agentCommandGate.disable()
        _uiState.update {
            it.copy(
                agentCommandStatus = "Agent command mode disabled.",
                agentCommandDetail = "PC-driven commands will be rejected until command mode is enabled again.",
                agentCommandLastReportPath = snapshot.lastReportPath
            )
        }
        appendLog(LogLevel.Info, "Agent command mode disabled.")
    }

    fun refreshAgentCommandStatus() {
        val snapshot = agentCommandGate.snapshot()
        _uiState.update {
            it.copy(
                agentCommandStatus = if (snapshot.active) {
                    "Agent command mode enabled."
                } else {
                    "Agent command mode disabled."
                },
                agentCommandDetail = if (snapshot.active) {
                    "PC-driven commands are accepted until ${snapshot.enabledUntilUtc}."
                } else {
                    "Enable a time-limited command window before running PC-driven app commands over ADB."
                },
                agentCommandLastReportPath = snapshot.lastReportPath
            )
        }
    }

    // HELPERS

    private fun listInstalledPackages() {
        scope.launch {
            startAction(
                actionLabel = "List Installed Packages",
                detail = "Reading the Quest package list over ADB."
            ) { state ->
                state.copy(
                    installedPackagesStatus = "Listing installed packages...",
                    installedPackagesDetail = "Running pm list packages over Quest ADB."
                )
            }
            val result = adbTransport.runUtility(AdbUtility.ListInstalledPackages)
            finishAction("List Installed Packages", result) { state ->
                state.copy(
                    installedPackagesStatus = result.summary,
                    installedPackagesDetail = when {
                        result.items.isNotEmpty() -> "${result.items.take(12).joinToString(", ")}${if (result.items.size > 12) " ..." else ""}"
                        else -> buildActionDetail(result.detail, state.activeEndpoint)
                    },
                    installedPackageIds = if (result.kind == TransportKind.Success) result.items else state.installedPackageIds
                )
            }
        }
    }

    private fun restartMonitorSubscription() {
        monitorJob?.cancel()
        val subscription = MonitorSubscription(
            streamName = uiState.value.monitorStreamName,
            streamType = uiState.value.monitorStreamType,
            channelIndex = uiState.value.monitorChannelIndex
        )
        monitorJob = scope.launch {
            monitorTransport.monitor(subscription).collectLatest { reading ->
                applyMonitorReading(reading)
            }
        }
    }

    private fun applyMonitorReading(reading: MonitorReading) {
        val monitorLogKey = "${reading.status}\n${reading.detail}"
        if (lastMonitorLogKey != monitorLogKey) {
            lastMonitorLogKey = monitorLogKey
            Log.i(
                "QuestMonitor",
                "${reading.status} ${reading.detail} stream=${reading.streamName}/${reading.streamType} channel=${reading.channelIndex} rate=${reading.sampleRateHz}"
            )
            appendLog(
                level = monitorLogLevel(reading.status),
                message = reading.status,
                detail = reading.detail
            )
        }
        _uiState.update {
            it.copy(
                monitorStatus = reading.status,
                monitorDetail = reading.detail,
                monitorValue = reading.value,
                monitorSampleRateHz = reading.sampleRateHz
            )
        }
    }

    private fun applyOscInboundPacket(packet: OscInboundPacket) {
        val line = "${timestampNow()} ${packet.logLine()}"
        _uiState.update {
            it.copy(
                oscListenStatus = packet.status,
                oscListenDetail = packet.detail,
                oscReceivedMessages = listOf(line) + it.oscReceivedMessages.take(19)
            )
        }

        val level = if (packet.message == null) LogLevel.Warning else LogLevel.Info
        appendLog(level, packet.status, packet.logLine())
    }

    private fun applyPolarReading(reading: PolarSensorReading) {
        _uiState.update {
            it.copy(
                polarStatus = reading.status,
                polarDetail = reading.detail,
                polarDeviceName = reading.deviceName ?: it.polarDeviceName,
                polarDeviceAddress = reading.deviceAddress ?: it.polarDeviceAddress,
                polarRssi = reading.rssi ?: it.polarRssi,
                polarBatteryPercent = reading.batteryPercent ?: it.polarBatteryPercent,
                polarHeartRateServiceVisible = reading.heartRateServiceVisible || it.polarHeartRateServiceVisible,
                polarPmdServiceVisible = reading.polarPmdServiceVisible || it.polarPmdServiceVisible
            )
        }
        appendLog(LogLevel.Info, reading.status, reading.detail)
    }

    private fun monitorLogLevel(status: String): LogLevel {
        return when {
            status.contains("error", ignoreCase = true) -> LogLevel.Failure
            status.contains("unavailable", ignoreCase = true) -> LogLevel.Warning
            status.contains("not found", ignoreCase = true) -> LogLevel.Warning
            status.contains("idle", ignoreCase = true) -> LogLevel.Warning
            status.contains("waiting", ignoreCase = true) -> LogLevel.Warning
            status.contains("lost", ignoreCase = true) -> LogLevel.Warning
            status.contains("channel unavailable", ignoreCase = true) -> LogLevel.Warning
            else -> LogLevel.Info
        }
    }

    private fun selectedApkTarget(): ApkTarget? {
        val appId = uiState.value.selectedAppId ?: return null
        return uiState.value.apkTargets.firstOrNull { it.id == appId }
    }

    private fun selectedBundle(): ApkBundle? {
        val bundleId = uiState.value.selectedBundleId ?: return null
        return uiState.value.apkBundles.firstOrNull { it.id == bundleId }
    }

    private fun selectedHotloadProfile(): RuntimeProfile? {
        val profileId = uiState.value.selectedHotloadId ?: return null
        return uiState.value.hotloadProfiles.firstOrNull { it.id == profileId }
    }

    private fun selectedDeviceProfile(): DeviceProfile? {
        val profileId = uiState.value.selectedDeviceProfileId ?: return null
        return uiState.value.deviceProfiles.firstOrNull { it.id == profileId }
    }

    private suspend fun loadCatalogSnapshot(): CatalogSnapshot {
        val catalogs = catalogLoader.load()
        val userProfiles = withContext(Dispatchers.IO) {
            userRuntimeProfileStore.loadProfiles()
        }
        val runtimeProfiles = mergeRuntimeProfiles(catalogs.hotloadProfiles, userProfiles)
        return CatalogSnapshot(
            catalogs = catalogs,
            runtimeProfiles = runtimeProfiles,
            userRuntimeProfileIds = userProfiles.mapTo(linkedSetOf()) { it.id }
        )
    }

    private suspend fun reloadCatalogs(preferredRuntimeProfileId: String?) {
        runCatching { loadCatalogSnapshot() }
            .onSuccess { snapshot ->
                val catalogs = snapshot.catalogs
                _uiState.update { state ->
                    val selectedAppId = state.selectedAppId
                        ?.takeIf { requested -> catalogs.apkTargets.any { it.id == requested } }
                        ?: catalogs.apkTargets.firstOrNull()?.id
                    val selectedTarget = catalogs.apkTargets.firstOrNull { it.id == selectedAppId }
                    val selectedHotloadId = pickHotloadSelection(
                        requestedId = preferredRuntimeProfileId ?: state.selectedHotloadId,
                        profiles = snapshot.runtimeProfiles,
                        target = selectedTarget
                    )
                    val selectedDeviceProfileId = state.selectedDeviceProfileId
                        ?.takeIf { requested -> catalogs.deviceProfiles.any { it.id == requested } }
                        ?: catalogs.deviceProfiles.firstOrNull()?.id
                    val selectedBundleId = state.selectedBundleId
                        ?.takeIf { requested -> catalogs.apkBundles.any { it.id == requested } }
                        ?: catalogs.apkBundles.firstOrNull()?.id

                    state.copy(
                        catalogRefreshStatus = "Loaded ${catalogs.source.label}: ${catalogs.apkTargets.size} app(s), ${catalogs.apkBundles.size} bundle(s), ${snapshot.runtimeProfiles.size} runtime preset(s), and ${catalogs.deviceProfiles.size} device preset(s).",
                        catalogSourceLabel = catalogs.source.label,
                        catalogManifestPath = catalogs.sourcePath,
                        apkTargets = catalogs.apkTargets,
                        apkBundles = catalogs.apkBundles,
                        hotloadProfiles = snapshot.runtimeProfiles,
                        userRuntimeProfileIds = snapshot.userRuntimeProfileIds,
                        deviceProfiles = catalogs.deviceProfiles,
                        selectedAppId = selectedAppId,
                        selectedBundleId = selectedBundleId,
                        selectedHotloadId = selectedHotloadId,
                        selectedDeviceProfileId = selectedDeviceProfileId
                    )
                }
            }
            .onFailure { throwable ->
                val message = throwable.message ?: "Unknown catalog load failure."
                _uiState.update {
                    it.copy(catalogRefreshStatus = "Catalog refresh failed: $message")
                }
                appendLog(LogLevel.Failure, "Failed to reload APK library.", message)
            }
    }

    private fun mergeRuntimeProfiles(
        catalogProfiles: List<RuntimeProfile>,
        userProfiles: List<RuntimeProfile>
    ): List<RuntimeProfile> {
        val profilesById = linkedMapOf<String, RuntimeProfile>()
        catalogProfiles.forEach { profile ->
            profilesById[profile.id.lowercase(Locale.ROOT)] = profile
        }
        userProfiles.forEach { profile ->
            profilesById[profile.id.lowercase(Locale.ROOT)] = profile
        }
        return profilesById.values.toList()
    }

    private fun parsePackageIdsDraft(text: String): List<String> {
        return text
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun parseLaunchExtrasDraft(text: String): Result<Map<String, String>> {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEachIndexed
            }

            val separator = line.indexOf('=')
            if (separator <= 0) {
                return Result.failure(
                    IllegalArgumentException("Line ${index + 1} must use key=value syntax.")
                )
            }

            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("Line ${index + 1} has an empty launch-extra key.")
                )
            }
            values[key] = value
        }

        return Result.success(values)
    }

    private fun pickHotloadSelection(
        requestedId: String?,
        profiles: List<RuntimeProfile>,
        target: ApkTarget?
    ): String? {
        val matchingProfiles = profiles.filter { it.matchesTarget(target) }
        if (matchingProfiles.isEmpty()) {
            return null
        }

        val current = matchingProfiles.firstOrNull { it.id == requestedId }
        return current?.id ?: matchingProfiles.first().id
    }

    private fun preparingProgress(detail: String): ActionProgressUiState {
        return ActionProgressUiState(
            active = true,
            label = "Preparing request",
            detail = detail
        )
    }

    private fun QuestSessionUiState.withActionProgress(
        actionLabel: String,
        progress: ActionProgressUiState
    ): QuestSessionUiState {
        val updated = when (actionLabel) {
            "Connect Quest",
            "USB Diagnostics",
            "USB ADB Probe",
            "USB Wi-Fi ADB" -> copy(connectionProgress = progress)
            "Install App" -> copy(installProgress = progress)
            "Install Bundle" -> copy(bundleInstallProgress = progress)
            "Upload Runtime Preset" -> copy(hotloadProgress = progress)
            "Apply Device Preset" -> copy(deviceProfileProgress = progress)
            "Launch App",
            "Force-stop App" -> copy(launchProgress = progress)
            "Open Browser",
            "Close Browser" -> copy(browserProgress = progress)
            "Refresh Active App" -> copy(foregroundProgress = progress)
            "List Installed Packages" -> copy(installedPackagesProgress = progress)
            "Home",
            "Back",
            "Wake",
            "Reboot" -> copy(utilityProgress = progress)
            "Export Diagnostics" -> copy(diagnosticsExportProgress = progress)
            else -> this
        }
        return updated.copy(lastActionProgress = progress)
    }

    private fun QuestSessionUiState.clearActionProgress(actionLabel: String): QuestSessionUiState =
        withActionProgress(actionLabel, ActionProgressUiState())

    private fun startAction(
        actionLabel: String,
        detail: String,
        updateState: (QuestSessionUiState) -> QuestSessionUiState
    ) {
        _uiState.update { state ->
            updateState(state)
                .withActionProgress(actionLabel, preparingProgress(detail))
                .copy(
                    lastActionLabel = "$actionLabel in progress",
                    lastActionDetail = detail
                )
        }
    }

    private fun finishAction(
        actionLabel: String,
        result: TransportResult,
        extraDetail: String = "",
        updateState: (QuestSessionUiState) -> QuestSessionUiState
    ) {
        _uiState.update { state ->
            updateState(state)
                .clearActionProgress(actionLabel)
                .copy(
                    lastActionLabel = actionLabel,
                    lastActionDetail = buildLastActionDetail(result, extraDetail)
                )
        }
        recordTransport(actionLabel, result, extraDetail)
    }

    private fun applyOperationProgress(
        state: QuestSessionUiState,
        progress: TransportOperationProgress
    ): QuestSessionUiState {
        val bundleContext = bundleInstallContext
        if (progress.kind == TransportOperationKind.InstallApk && bundleContext != null) {
            val overallFraction = (
                bundleContext.currentIndex.toFloat() +
                    (progress.fraction ?: 0f).coerceIn(0f, 1f)
                ) / bundleContext.totalApps.toFloat()
            val detail = buildOperationProgressDetail(
                progress,
                prefix = "${bundleContext.currentTarget.label} (${bundleContext.currentIndex + 1}/${bundleContext.totalApps})"
            )
            val bundleProgress = ActionProgressUiState(
                active = true,
                label = buildProgressLabel(
                    progress = progress,
                    fractionOverride = overallFraction,
                    prefix = "App ${bundleContext.currentIndex + 1}/${bundleContext.totalApps}"
                ),
                detail = detail,
                fraction = overallFraction
            )
            return state.copy(
                bundleInstallStatus = "${progress.title} for ${bundleContext.currentTarget.label}",
                bundleInstallDetail = detail,
                bundleInstallProgress = bundleProgress,
                lastActionDetail = detail,
                lastActionProgress = bundleProgress
            )
        }

        val detail = buildOperationProgressDetail(progress)
        val actionProgress = ActionProgressUiState(
            active = true,
            label = buildProgressLabel(progress),
            detail = detail,
            fraction = progress.fraction
        )

        return when (progress.kind) {
            TransportOperationKind.InstallApk -> state.copy(
                installStatus = progress.title,
                installDetail = detail,
                installProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.PushHotload -> state.copy(
                hotloadStatus = progress.title,
                hotloadDetail = detail,
                hotloadProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.ApplyDeviceProfile -> state.copy(
                deviceProfileStatus = progress.title,
                deviceProfileDetail = detail,
                deviceProfileProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.LaunchApp,
            TransportOperationKind.ForceStopApp -> state.copy(
                launchStatus = progress.title,
                launchDetail = detail,
                launchProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.OpenUrl -> state.copy(
                browserStatus = progress.title,
                browserDetail = detail,
                browserProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.QueryForegroundPackage -> state.copy(
                foregroundStatus = progress.title,
                foregroundDetail = detail,
                foregroundProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.ListInstalledPackages -> state.copy(
                installedPackagesStatus = progress.title,
                installedPackagesDetail = detail,
                installedPackagesProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
            TransportOperationKind.UtilityCommand -> state.copy(
                utilityStatus = progress.title,
                utilityDetail = detail,
                utilityProgress = actionProgress,
                lastActionDetail = detail,
                lastActionProgress = actionProgress
            )
        }
    }

    private fun buildProgressLabel(
        progress: TransportOperationProgress,
        fractionOverride: Float? = null,
        prefix: String? = null
    ): String {
        return buildList {
            prefix?.takeIf { it.isNotBlank() }?.let(::add)
            if (progress.stepIndex > 0 && progress.stepCount > 0) {
                add("Step ${progress.stepIndex.coerceIn(1, progress.stepCount)}/${progress.stepCount}")
            }
            (fractionOverride ?: progress.fraction)?.let { add("${(it * 100f).roundToInt()}%") }
            val currentBytes = progress.currentBytes
            val totalBytes = progress.totalBytes
            if (currentBytes != null && totalBytes != null) {
                add("${formatByteCount(currentBytes)} / ${formatByteCount(totalBytes)}")
            }
        }.joinToString(" • ").ifBlank {
            progress.title
        }
    }

    private fun buildOperationProgressDetail(
        progress: TransportOperationProgress,
        prefix: String? = null
    ): String {
        return listOfNotNull(
            prefix?.takeIf { it.isNotBlank() }?.let { "$it." },
            progress.detail.takeIf { it.isNotBlank() },
            progress.currentBytes?.let { currentBytes ->
                progress.totalBytes?.let { totalBytes ->
                    "Transferred ${formatByteCount(currentBytes)} of ${formatByteCount(totalBytes)}."
                }
            }
        ).joinToString(" ").ifBlank {
            progress.title
        }
    }

    private fun formatByteCount(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble().coerceAtLeast(0.0)
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.roundToInt()} ${units[unitIndex]}"
        } else {
            String.format("%.1f %s", value, units[unitIndex])
        }
    }

    private fun recordTransport(
        actionLabel: String,
        result: TransportResult,
        extraDetail: String = ""
    ) {
        val level = when (result.kind) {
            TransportKind.Success -> LogLevel.Info
            TransportKind.Partial -> LogLevel.Warning
            TransportKind.Stub -> LogLevel.Warning
            TransportKind.Error -> LogLevel.Failure
        }
        val itemPreview = result.items.take(8).joinToString(", ")
        val detail = listOf(
            result.packageId?.let { "package=$it" }.orEmpty(),
            result.detail,
            result.networkSummary,
            result.networkDetail,
            itemPreview.takeIf { it.isNotBlank() }?.let { "items=$it" }.orEmpty(),
            extraDetail
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")

        appendLog(level, "$actionLabel: ${result.summary}", detail)
    }

    private fun buildConnectionDetail(
        result: TransportResult,
        activeEndpoint: String?
    ): String {
        return listOfNotNull(
            activeEndpoint?.let { "Active endpoint: $it." },
            result.endpoint?.takeIf { activeEndpoint == null }?.let { "Resolved endpoint: $it." },
            result.detail.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank {
            "No additional Quest transport detail."
        }
    }

    private fun buildActionDetail(
        resultDetail: String,
        activeEndpoint: String?
    ): String {
        return listOfNotNull(
            activeEndpoint?.let { "Quest endpoint: $it." },
            resultDetail.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank {
            "No additional action detail."
        }
    }

    private fun buildLastActionDetail(
        result: TransportResult,
        extraDetail: String = ""
    ): String {
        return listOfNotNull(
            result.summary,
            result.endpoint?.let { "Endpoint: $it." },
            result.packageId?.let { "Package: $it." },
            result.detail.takeIf { it.isNotBlank() },
            result.networkSummary.takeIf { it.isNotBlank() },
            result.networkDetail.takeIf { it.isNotBlank() },
            result.items.takeIf { it.isNotEmpty() }?.let { "Items: ${it.take(8).joinToString(", ")}." },
            extraDetail.takeIf { it.isNotBlank() }
        ).joinToString(" ")
    }

    private fun startTransportProgressFeedback(actionLabel: String) {
        val liveTransport = adbTransport as? LiveAdbTransport ?: return
        transportProgressJob?.cancel()
        transportProgressJob = scope.launch {
            var lastEventTimestamp = Long.MIN_VALUE
            var lastEventStage: DiagnosticsStage? = null
            var lastEventMessage = ""
            while (isActive) {
                val snapshot = liveTransport.latestDiagnostics()
                val event = snapshot.events.lastOrNull()
                if (event != null) {
                    val changed = event.timestampMs != lastEventTimestamp ||
                        event.stage != lastEventStage ||
                        event.message != lastEventMessage
                    if (changed) {
                        _uiState.update { state ->
                            applyTransportProgress(state, actionLabel, snapshot, event)
                        }
                        lastEventTimestamp = event.timestampMs
                        lastEventStage = event.stage
                        lastEventMessage = event.message
                    }
                }
                if (snapshot.endMs != null) {
                    break
                }
                delay(150)
            }
        }
    }

    private fun stopTransportProgressFeedback() {
        transportProgressJob?.cancel()
        transportProgressJob = null
    }

    private fun applyTransportProgress(
        state: QuestSessionUiState,
        actionLabel: String,
        snapshot: DiagnosticsSnapshot,
        event: DiagnosticsEvent
    ): QuestSessionUiState {
        val summary = transportProgressSummary(snapshot.actionLabel, event)
        val detail = transportProgressDetail(snapshot.actionLabel, event)
        val progress = buildDiagnosticsProgressState(snapshot.actionLabel, event)
        val lastDetail = listOf(summary, detail)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { event.message }

        return when (snapshot.actionLabel) {
            "inspectUsb" -> state.copy(
                usbSummary = summary,
                usbDetail = detail,
                connectionProgress = progress,
                lastActionLabel = "$actionLabel in progress",
                lastActionDetail = lastDetail,
                lastActionProgress = progress
            )
            "probeUsbAdb" -> state.copy(
                connectionSummary = summary,
                connectionDetail = detail,
                usbSummary = transportUsbSummary(event),
                usbDetail = transportUsbDetail(event, detail),
                connectionProgress = progress,
                lastActionLabel = "$actionLabel in progress",
                lastActionDetail = lastDetail,
                lastActionProgress = progress
            )
            "connect" -> state.copy(
                connectionSummary = summary,
                connectionDetail = detail,
                phoneNetworkSummary = transportNetworkSummary(state.phoneNetworkSummary, event),
                phoneNetworkDetail = transportNetworkDetail(state.phoneNetworkDetail, event),
                connectionProgress = progress,
                lastActionLabel = "$actionLabel in progress",
                lastActionDetail = lastDetail,
                lastActionProgress = progress
            )
            "enableWifiFromUsb" -> state.copy(
                connectionSummary = summary,
                connectionDetail = detail,
                usbSummary = transportUsbSummary(event),
                usbDetail = transportUsbDetail(event, detail),
                phoneNetworkSummary = transportNetworkSummary(state.phoneNetworkSummary, event),
                phoneNetworkDetail = transportNetworkDetail(state.phoneNetworkDetail, event),
                connectionProgress = progress,
                lastActionLabel = "$actionLabel in progress",
                lastActionDetail = lastDetail,
                lastActionProgress = progress
            )
            else -> state.copy(
                lastActionLabel = "$actionLabel in progress",
                lastActionDetail = lastDetail,
                lastActionProgress = progress
            )
        }
    }

    private fun buildDiagnosticsProgressState(
        actionName: String,
        event: DiagnosticsEvent
    ): ActionProgressUiState {
        val stages = diagnosticsActionStages(actionName)
        val index = stages.indexOf(event.stage).takeIf { it >= 0 } ?: 0
        val stepCount = stages.size.coerceAtLeast(1)
        val fraction = ((index + 1).toFloat() / stepCount.toFloat()).coerceIn(0f, 1f)
        return ActionProgressUiState(
            active = true,
            label = "Stage ${index + 1}/$stepCount • ${(fraction * 100f).roundToInt()}%",
            detail = transportProgressDetail(actionName, event),
            fraction = fraction
        )
    }

    private fun diagnosticsActionStages(actionName: String): List<DiagnosticsStage> {
        return when (actionName) {
            "inspectUsb" -> listOf(
                DiagnosticsStage.UsbDeviceDiscovery,
                DiagnosticsStage.UsbAdbInterfaceCheck
            )
            "probeUsbAdb" -> listOf(
                DiagnosticsStage.UsbDeviceDiscovery,
                DiagnosticsStage.UsbAdbInterfaceCheck,
                DiagnosticsStage.UsbPermissionRequest,
                DiagnosticsStage.UsbAdbConnect,
                DiagnosticsStage.AdbAuthHandshake,
                DiagnosticsStage.ShellEchoVerify,
                DiagnosticsStage.AdbCapabilityProbe
            )
            "connect" -> listOf(
                DiagnosticsStage.EndpointParse,
                DiagnosticsStage.SubnetCheck,
                DiagnosticsStage.TcpConnect,
                DiagnosticsStage.ShellEchoVerify,
                DiagnosticsStage.AdbCapabilityProbe
            )
            "enableWifiFromUsb" -> listOf(
                DiagnosticsStage.UsbDeviceDiscovery,
                DiagnosticsStage.UsbAdbInterfaceCheck,
                DiagnosticsStage.UsbPermissionRequest,
                DiagnosticsStage.UsbAdbConnect,
                DiagnosticsStage.AdbAuthHandshake,
                DiagnosticsStage.ShellRouteQuery,
                DiagnosticsStage.EndpointParse,
                DiagnosticsStage.SubnetCheck,
                DiagnosticsStage.TcpipEnable,
                DiagnosticsStage.TcpConnect,
                DiagnosticsStage.TcpAdbHandshake,
                DiagnosticsStage.ShellEchoVerify
            )
            else -> DiagnosticsStage.entries
        }
    }

    private fun transportProgressSummary(
        actionName: String,
        event: DiagnosticsEvent
    ): String {
        return when (event.stage) {
            DiagnosticsStage.UsbDeviceDiscovery -> if (event.success) {
                "Quest USB device found. Inspecting interfaces..."
            } else {
                "No USB devices are visible to the phone app."
            }
            DiagnosticsStage.UsbAdbInterfaceCheck -> if (event.success) {
                "Quest ADB USB interface found."
            } else {
                "Quest USB device is visible, but no ADB interface is exposed."
            }
            DiagnosticsStage.UsbPermissionRequest -> if (event.success) {
                "USB access granted. Opening the Quest debug link..."
            } else {
                "Waiting for Android to hand the Quest USB device to the app..."
            }
            DiagnosticsStage.UsbAdbConnect -> if (event.success) {
                "Opening Quest USB ADB channel..."
            } else {
                "Quest USB ADB channel could not be opened."
            }
            DiagnosticsStage.AdbAuthHandshake -> when {
                event.message.contains("AUTH token received", ignoreCase = true) ->
                    "Quest challenged the phone key. Signing the USB ADB session..."
                event.message.contains("AUTH signature sent", ignoreCase = true) ->
                    "Signed the Quest challenge. Waiting for the headset to finish USB ADB handshake..."
                event.message.contains("AUTH RSA public key sent", ignoreCase = true) ->
                    "Sent the phone's public ADB key to the Quest. Watch the headset for the trust prompt..."
                event.message.contains("ADB handshake complete", ignoreCase = true) ->
                    "Quest USB ADB handshake complete. Querying the headset network route..."
                event.message.contains("ADB protocol notice", ignoreCase = true) ->
                    "Quest ADB protocol notice received."
                event.message.contains("Connection thread error", ignoreCase = true) ->
                    "Quest USB ADB channel dropped after handshake."
                event.message.contains("timed out", ignoreCase = true) ->
                    "Quest USB ADB handshake timed out."
                else -> event.message
            }
            DiagnosticsStage.ShellRouteQuery -> if (event.success) {
                "Quest route query returned. Resolving the headset Wi-Fi endpoint..."
            } else {
                "Quest USB ADB is up, but the route query failed."
            }
            DiagnosticsStage.EndpointParse -> if (event.success) {
                "Quest Wi-Fi endpoint resolved. Comparing phone and headset networks..."
            } else {
                "Quest answered over USB, but it did not report a usable Wi-Fi address."
            }
            DiagnosticsStage.SubnetCheck -> if (event.success) {
                "Quest endpoint resolved. Checking whether the phone is on the same network..."
            } else {
                "Quest endpoint resolved, but the phone does not appear to be on the same network."
            }
            DiagnosticsStage.TcpipEnable -> if (event.success) {
                if (event.message.contains("completed", ignoreCase = true)) {
                    "Quest accepted tcpip:5555. Waiting for Wi-Fi ADB to come up..."
                } else {
                    "Switching Quest ADB to Wi-Fi mode..."
                }
            } else {
                "Quest rejected the request to restart ADB over Wi-Fi."
            }
            DiagnosticsStage.TcpConnect -> if (event.success) {
                "Quest Wi-Fi ADB endpoint responded. Finalizing the connection..."
            } else {
                "Quest Wi-Fi ADB endpoint did not respond from the phone."
            }
            DiagnosticsStage.TcpAdbHandshake -> if (event.success) {
                "Quest Wi-Fi ADB handshake complete."
            } else {
                "Quest Wi-Fi ADB handshake failed."
            }
            DiagnosticsStage.ShellEchoVerify -> if (event.success) {
                if (actionName == "probeUsbAdb") {
                    "Quest shell probe succeeded. Verifying reversible ADB state changes..."
                } else {
                    "Quest Wi-Fi ADB session verified."
                }
            } else {
                if (actionName == "probeUsbAdb") {
                    "Quest USB ADB shell probe failed."
                } else {
                    "Quest Wi-Fi ADB connected, but shell verification failed."
                }
            }
            DiagnosticsStage.AdbCapabilityProbe -> if (event.success) {
                if (actionName == "probeUsbAdb") {
                    "Quest USB ADB probe completed and reversible state access worked."
                } else {
                    "Quest diagnostics capability probe succeeded."
                }
            } else {
                if (actionName == "probeUsbAdb") {
                    "Quest USB ADB is up, but the reversible state probe failed."
                } else {
                    "Quest diagnostics capability probe failed."
                }
            }
        }.let { summary ->
            if (actionName == "inspectUsb" && event.stage !in setOf(DiagnosticsStage.UsbDeviceDiscovery, DiagnosticsStage.UsbAdbInterfaceCheck)) {
                "Refreshing USB diagnostics..."
            } else {
                summary
            }
        }
    }

    private fun transportProgressDetail(
        actionName: String,
        event: DiagnosticsEvent
    ): String {
        val raw = listOf(event.detail, event.payload)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val condensed = if (raw.length > 280) raw.take(277) + "..." else raw
        if (condensed.isNotBlank()) {
            return condensed
        }

        return when (event.stage) {
            DiagnosticsStage.UsbDeviceDiscovery ->
                "Checking whether Android exposes the Quest through UsbManager.deviceList."
            DiagnosticsStage.UsbAdbInterfaceCheck ->
                "Inspecting the visible Quest USB interfaces for the ADB class triplet."
            DiagnosticsStage.UsbPermissionRequest ->
                "Android is deciding whether this app gets direct USB access to the Quest."
            DiagnosticsStage.UsbAdbConnect ->
                "Claiming the Quest bulk endpoints and starting the ADB handshake."
            DiagnosticsStage.AdbAuthHandshake -> when {
                event.message.contains("AUTH token received", ignoreCase = true) ->
                    "The Quest sent an ADB auth challenge token to the phone app."
                event.message.contains("AUTH signature sent", ignoreCase = true) ->
                    "The phone app signed the Quest token with its stored ADB private key."
                event.message.contains("AUTH RSA public key sent", ignoreCase = true) ->
                    "The phone app sent its public ADB key because the Quest still wanted explicit trust confirmation."
                event.message.contains("ADB handshake complete", ignoreCase = true) ->
                    "USB ADB is up. The next step is reading the Quest's route table to find its Wi-Fi address."
                else ->
                    "USB ADB handshake is still in progress."
            }
            DiagnosticsStage.ShellRouteQuery ->
                "Running `ip route` over USB ADB to find the Quest's current Wi-Fi source address."
            DiagnosticsStage.EndpointParse ->
                "Parsing a usable IPv4 source address from the Quest route output."
            DiagnosticsStage.SubnetCheck ->
                "Comparing the Quest Wi-Fi endpoint with the phone's active IPv4 interfaces."
            DiagnosticsStage.TcpipEnable ->
                "Sending `tcpip:5555` over USB so the Quest restarts ADB in Wi-Fi mode."
            DiagnosticsStage.TcpConnect ->
                "Trying to reach the Quest's Wi-Fi ADB socket from the phone."
            DiagnosticsStage.TcpAdbHandshake ->
                "Negotiating the ADB handshake over the Quest's Wi-Fi endpoint."
            DiagnosticsStage.ShellEchoVerify ->
                if (actionName == "probeUsbAdb") {
                    "Reading Quest model and battery state over USB ADB before the reversible debug-property probe."
                } else {
                    "Running a simple shell check over Wi-Fi ADB to confirm the session is usable."
                }
            DiagnosticsStage.AdbCapabilityProbe ->
                if (actionName == "probeUsbAdb") {
                    "Setting and restoring a temporary Quest debug property to prove reversible ADB state mutation."
                } else {
                    "Reading the optional diagnostics capability probe property on the Quest."
                }
        }.let {
            if (actionName == "inspectUsb") {
                condensed.ifBlank { it }
            } else {
                it
            }
        }
    }

    private fun transportUsbSummary(event: DiagnosticsEvent): String {
        return when (event.stage) {
            DiagnosticsStage.UsbDeviceDiscovery,
            DiagnosticsStage.UsbAdbInterfaceCheck,
            DiagnosticsStage.UsbPermissionRequest,
            DiagnosticsStage.UsbAdbConnect -> transportProgressSummary("enableWifiFromUsb", event)
            DiagnosticsStage.AdbAuthHandshake,
            DiagnosticsStage.ShellRouteQuery,
            DiagnosticsStage.EndpointParse,
            DiagnosticsStage.SubnetCheck,
            DiagnosticsStage.TcpipEnable,
            DiagnosticsStage.TcpConnect,
            DiagnosticsStage.TcpAdbHandshake,
            DiagnosticsStage.ShellEchoVerify,
            DiagnosticsStage.AdbCapabilityProbe -> "Quest USB ADB link is active."
        }
    }

    private fun transportUsbDetail(
        event: DiagnosticsEvent,
        fallbackDetail: String
    ): String {
        return when (event.stage) {
            DiagnosticsStage.UsbDeviceDiscovery,
            DiagnosticsStage.UsbAdbInterfaceCheck,
            DiagnosticsStage.UsbPermissionRequest,
            DiagnosticsStage.UsbAdbConnect -> fallbackDetail
            else -> "USB access and Quest ADB interface detection already succeeded for this run."
        }
    }

    private fun transportNetworkSummary(
        previousSummary: String,
        event: DiagnosticsEvent
    ): String {
        return when (event.stage) {
            DiagnosticsStage.EndpointParse -> if (event.success) {
                "Quest Wi-Fi endpoint resolved from the USB route query."
            } else {
                "Quest route output did not expose a usable Wi-Fi endpoint."
            }
            DiagnosticsStage.SubnetCheck -> event.message
            DiagnosticsStage.TcpipEnable,
            DiagnosticsStage.TcpConnect,
            DiagnosticsStage.TcpAdbHandshake,
            DiagnosticsStage.ShellEchoVerify ->
                "Testing whether the phone can reach the Quest over Wi-Fi ADB..."
            else -> previousSummary
        }
    }

    private fun transportNetworkDetail(
        previousDetail: String,
        event: DiagnosticsEvent
    ): String {
        return when (event.stage) {
            DiagnosticsStage.EndpointParse,
            DiagnosticsStage.SubnetCheck,
            DiagnosticsStage.TcpipEnable,
            DiagnosticsStage.TcpConnect,
            DiagnosticsStage.TcpAdbHandshake,
            DiagnosticsStage.ShellEchoVerify -> transportProgressDetail("network", event)
            else -> previousDetail
        }
    }

    private fun summarizeUsbFromConnectionResult(
        result: TransportResult,
        previousSummary: String
    ): String {
        return when {
            result.summary.contains("USB ADB probe", ignoreCase = true) -> "Quest USB ADB link is active."
            result.summary.contains("Could not resolve the Quest hotspot IP", ignoreCase = true) ->
                "Quest USB ADB link is up, but the route query did not expose a Wi-Fi endpoint."
            result.summary.contains("Enabled Wi-Fi ADB from USB", ignoreCase = true) ->
                "Quest USB bootstrap completed."
            result.summary.contains("USB", ignoreCase = true) -> result.summary
            result.detail.contains("vid=", ignoreCase = true) -> "USB device inventory captured."
            else -> previousSummary
        }
    }

    private fun detailUsbFromConnectionResult(
        result: TransportResult,
        previousDetail: String
    ): String {
        return when {
            result.summary.contains("USB ADB probe", ignoreCase = true) -> result.detail
            result.summary.contains("Could not resolve the Quest hotspot IP", ignoreCase = true) -> result.detail
            result.summary.contains("Enabled Wi-Fi ADB from USB", ignoreCase = true) -> result.detail
            result.detail.contains("vid=", ignoreCase = true) -> result.detail
            result.summary.contains("USB", ignoreCase = true) -> result.detail.ifBlank { previousDetail }
            else -> previousDetail
        }
    }

    private fun appendLog(
        level: LogLevel,
        message: String,
        detail: String = ""
    ) {
        _uiState.update { state ->
            val entry = SessionLogEntry(
                timestamp = timestampNow(),
                level = level,
                message = message,
                detail = detail
            )
            state.copy(logs = listOf(entry) + state.logs.take(79))
        }
    }

    private fun timestampNow(): String {
        return java.time.ZonedDateTime.now()
            .toLocalTime()
            .withNano(0)
            .toString()
    }
}
