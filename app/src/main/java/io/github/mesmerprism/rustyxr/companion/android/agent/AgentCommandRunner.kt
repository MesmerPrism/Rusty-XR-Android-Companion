package io.github.mesmerprism.rustyxr.companion.android.agent

import android.content.Context
import android.content.Intent
import android.view.Surface
import io.github.mesmerprism.rustyxr.companion.android.transport.AdbUtility
import io.github.mesmerprism.rustyxr.companion.android.transport.AndroidPolarPmdSmokeMonitor
import io.github.mesmerprism.rustyxr.companion.android.transport.LiveAdbTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.OscArgument
import io.github.mesmerprism.rustyxr.companion.android.transport.OscMessage
import io.github.mesmerprism.rustyxr.companion.android.transport.OscUdpTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarPmdAccFrameSummary
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarPmdControlSummary
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarPmdEcgFrameSummary
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarPmdSettingsSummary
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarPmdSmokeResult
import io.github.mesmerprism.rustyxr.companion.android.transport.Q2QRelayRequest
import io.github.mesmerprism.rustyxr.companion.android.transport.Q2QRelayTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportKind
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

class AgentCommandRunner(
    context: Context,
    private val receiverDisplaySurface: Surface? = null,
    private val progressSink: AgentCommandProgressSink = NoopAgentCommandProgressSink
) {
    private val applicationContext = context.applicationContext

    suspend fun run(intent: Intent): JSONObject = withContext(Dispatchers.IO) {
        val command = intent.getStringExtra(ExtraCommand)
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        val startedAt = Instant.now()
        val result = when (command) {
            CommandPolarPmdSmoke -> runPolarPmdSmoke(intent, startedAt)
            CommandQ2QRelay -> runQ2QRelay(intent, startedAt)
            CommandQuestSuite -> runQuestSuite(intent, startedAt)
            CommandQuestInspectUsb -> runQuestUsbTransportCommand(startedAt, "inspect-usb")
            CommandQuestProbeUsb -> runQuestUsbTransportCommand(startedAt, "probe-usb")
            CommandQuestEnableWifiAdb -> runQuestUsbTransportCommand(startedAt, "enable-wifi-adb")
            CommandQuestConnect -> runQuestSingleTransportCommand(intent, startedAt, "connect")
            CommandQuestInstall -> runQuestSingleTransportCommand(intent, startedAt, "install")
            CommandQuestLaunch -> runQuestSingleTransportCommand(intent, startedAt, "launch")
            CommandQuestStop -> runQuestSingleTransportCommand(intent, startedAt, "stop")
            CommandQuestForeground -> runQuestSingleTransportCommand(intent, startedAt, "foreground")
            CommandQuestListPackages -> runQuestSingleTransportCommand(intent, startedAt, "list-packages")
            CommandQuestUtility -> runQuestSingleTransportCommand(intent, startedAt, "utility")
            else -> errorReport(
                startedAt = startedAt,
                command = command.ifBlank { "missing" },
                summary = "Unknown agent command.",
                detail = "Use one of: ${SupportedCommands.joinToString(", ")}."
            )
        }
        result
    }

    private suspend fun runPolarPmdSmoke(intent: Intent, startedAt: Instant): JSONObject {
        val timeoutMs = intent.getLongExtra(ExtraTimeoutMs, DefaultPolarTimeoutMs)
            .coerceIn(MinPolarTimeoutMs, MaxPolarTimeoutMs)
        val deviceAddress = intent.getStringExtra(ExtraDeviceAddress)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val result = AndroidPolarPmdSmokeMonitor(applicationContext).run(
            timeoutMs = timeoutMs,
            deviceAddress = deviceAddress
        )
        return JSONObject().apply {
            put("schemaVersion", SchemaVersion)
            put("command", CommandPolarPmdSmoke)
            put("overall", if (result.passed) "passed" else "failed")
            put("startedAtUtc", startedAt.toString())
            put("endedAtUtc", Instant.now().toString())
            put("polar", result.toJson())
        }
    }

    private suspend fun runQ2QRelay(intent: Intent, startedAt: Instant): JSONObject {
        val mode = intent.requiredStringOrNull(ExtraQ2QMode)
            ?: intent.requiredStringOrNull(ExtraMode)
            ?: DefaultQ2QMode
        val sourceMode = intent.getStringExtra(ExtraSourceMode)?.trim().orEmpty()
        val normalizedSourceMode = normalizeQ2QSourceMode(sourceMode)
        val sessionBudgetMs = sessionDurationMs(intent)
        val durationMs = durationMs(intent, sessionBudgetMs)
        val connectTimeoutMs = connectTimeoutMs(intent, sessionBudgetMs)
        val defaultWidth = if (normalizedSourceMode == SourceModeCamera2) DefaultQ2QCameraRequestedWidth else DefaultQ2QSyntheticWidth
        val defaultHeight = if (normalizedSourceMode == SourceModeCamera2) DefaultQ2QCameraRequestedHeight else DefaultQ2QSyntheticHeight
        val sessionId = intent.requiredStringOrNull(ExtraSessionId)
        val sendSessionId = intent.requiredStringOrNull(ExtraSendSessionId)
            ?: sessionId
            ?: DefaultQ2QSendSessionId
        val receiveSessionId = intent.requiredStringOrNull(ExtraReceiveSessionId)
            ?: sessionId
            ?: DefaultQ2QReceiveSessionId
        progressSink.event(
            "q2q_request_building",
            JSONObject().apply {
                put("mode", mode)
                put("sourceMode", normalizedSourceMode)
                put("sessionBudgetMs", sessionBudgetMs)
                put("durationMs", durationMs)
                put("connectTimeoutMs", connectTimeoutMs)
            }
        )
        val request = Q2QRelayRequest(
            mode = mode,
            relayHost = intent.requiredStringOrNull(ExtraRelayHost) ?: DefaultQ2QRelayHost,
            relayPort = intent.getIntExtra(ExtraRelayPort, DefaultQ2QRelayPort).coerceIn(1, 65_535),
            channel = intent.getStringExtra(ExtraRelayChannel)?.trim().orEmpty(),
            token = intent.getStringExtra(ExtraRelayToken)?.trim().orEmpty(),
            tls = intent.getBooleanExtra(ExtraRelayTls, false),
            insecureTls = intent.getBooleanExtra(ExtraRelayInsecureTls, false),
            serverName = intent.getStringExtra(ExtraRelayServerName)?.trim().orEmpty(),
            sendSessionId = sendSessionId,
            receiveSessionId = receiveSessionId,
            eyes = parseEyes(intent.requiredStringOrNull(ExtraEyes)),
            durationMs = durationMs,
            connectTimeoutMs = connectTimeoutMs,
            width = intent.getIntExtra(ExtraWidth, defaultWidth).coerceIn(0, MaxQ2QDimension),
            height = intent.getIntExtra(ExtraHeight, defaultHeight).coerceIn(0, MaxQ2QDimension),
            bitrateBps = intent.getIntExtra(ExtraBitrateBps, DefaultQ2QBitrateBps)
                .coerceIn(100_000, MaxQ2QBitrateBps),
            frameRateHz = intent.getIntExtra(ExtraFrameRateHz, DefaultQ2QFrameRateHz)
                .coerceIn(1, 60),
            sourceMode = normalizedSourceMode,
            cameraId = intent.getStringExtra(ExtraCameraId)?.trim().orEmpty(),
            cameraFacing = intent.getStringExtra(ExtraCameraFacing)?.trim().orEmpty(),
            sameCameraToEyes = intent.getBooleanExtra(ExtraSameCameraToEyes, true),
            qualityProfile = intent.getStringExtra(ExtraQualityProfile)?.trim().orEmpty(),
            label = intent.getStringExtra(ExtraLabel)?.trim().orEmpty()
        )
        progressSink.event(
            "q2q_request_ready",
            JSONObject().apply {
                put("mode", request.mode)
                put("relayHost", request.relayHost)
                put("relayPort", request.relayPort)
                put("channel", request.channel.ifBlank { "media" })
                put("sendSessionId", request.sendSessionId)
                put("receiveSessionId", request.receiveSessionId)
                put("durationMs", request.durationMs)
                put("connectTimeoutMs", request.connectTimeoutMs)
                put("sourceMode", request.sourceMode)
                put("qualityProfile", request.qualityProfile.ifBlank { "camera-native-max" })
                put("width", request.width)
                put("height", request.height)
                put("frameRateHz", request.frameRateHz)
                put("bitrateBps", request.bitrateBps)
            }
        )
        val q2q = Q2QRelayTransport(
            applicationContext = applicationContext,
            receiverDisplaySurface = receiverDisplaySurface,
            receiverDisplayEye = intent.getStringExtra(ExtraDisplayEye)?.trim().orEmpty(),
            progressSink = progressSink
        ).run(request)
        return JSONObject().apply {
            put("schemaVersion", SchemaVersion)
            put("command", CommandQ2QRelay)
            put("overall", q2q.optString("overall", "failed"))
            put("startedAtUtc", startedAt.toString())
            put("endedAtUtc", Instant.now().toString())
            put("q2qRelay", q2q)
        }
    }

    private suspend fun runQuestSuite(intent: Intent, startedAt: Instant): JSONObject {
        val endpoint = intent.endpoint()
        val oscPort = intent.getIntExtra(ExtraOscPort, DefaultOscPort).coerceIn(1, 65_535)
        val packageId = intent.requiredStringOrNull(ExtraPackageId)
        val apkFile = intent.requiredStringOrNull(ExtraApkFile)
        val launchComponent = intent.requiredStringOrNull(ExtraComponent)
        val extras = intent.extrasJsonMap()
        val steps = JSONArray()
        val transport = LiveAdbTransport(applicationContext)

        val connect = runTransportStep(steps, "connect") {
            transport.connect(endpoint)
        }

        if (connect.kind == TransportKind.Success) {
            runTransportStep(steps, "wake") {
                transport.runUtility(AdbUtility.Wake)
            }
            if (apkFile != null && packageId != null) {
                runTransportStep(steps, "install") {
                    transport.install(apkFile, packageId)
                }
            }
            if (packageId != null) {
                runTransportStep(steps, "launch", critical = apkFile != null) {
                    if (launchComponent != null) {
                        transport.launchIntent(packageId, launchComponent, extras)
                    } else {
                        transport.launch(packageId, extras)
                    }
                }
                runTransportStep(steps, "queryForegroundPackageAfterLaunch", critical = false) {
                    transport.queryForegroundPackage()
                }
                runTransportStep(steps, "stop", critical = false) {
                    transport.forceStop(packageId)
                }
            }
            runTransportStep(steps, "home") {
                transport.runUtility(AdbUtility.Home)
            }
            runTransportStep(steps, "back") {
                transport.runUtility(AdbUtility.Back)
            }
            runTransportStep(steps, "listInstalledPackages", critical = false) {
                transport.runUtility(AdbUtility.ListInstalledPackages)
            }
            runOscStep(steps, endpoint, oscPort)
        }

        return JSONObject().apply {
            put("schemaVersion", SchemaVersion)
            put("command", CommandQuestSuite)
            put("overall", determineOverall(steps))
            put("endpoint", endpoint)
            put("oscPort", oscPort)
            put("packageId", packageId ?: JSONObject.NULL)
            put("apkFile", apkFile ?: JSONObject.NULL)
            put("component", launchComponent ?: JSONObject.NULL)
            put("launchExtrasCount", extras.size)
            put("startedAtUtc", startedAt.toString())
            put("endedAtUtc", Instant.now().toString())
            put("steps", steps)
        }
    }

    private suspend fun runQuestSingleTransportCommand(
        intent: Intent,
        startedAt: Instant,
        command: String
    ): JSONObject {
        val endpoint = intent.endpoint()
        val steps = JSONArray()
        val transport = LiveAdbTransport(applicationContext)
        val connect = runTransportStep(steps, "connect") { transport.connect(endpoint) }

        if (connect.kind == TransportKind.Success) {
            when (command) {
                "connect" -> Unit
                "install" -> {
                    val apkFile = intent.requiredString(ExtraApkFile)
                    val packageId = intent.requiredString(ExtraPackageId)
                    runTransportStep(steps, "install") { transport.install(apkFile, packageId) }
                }
                "launch" -> {
                    val packageId = intent.requiredString(ExtraPackageId)
                    val component = intent.requiredStringOrNull(ExtraComponent)
                    val extras = intent.extrasJsonMap()
                    runTransportStep(steps, "launch") {
                        if (component != null) {
                            transport.launchIntent(packageId, component, extras)
                        } else {
                            transport.launch(packageId, extras)
                        }
                    }
                }
                "stop" -> {
                    val packageId = intent.requiredString(ExtraPackageId)
                    runTransportStep(steps, "stop") { transport.forceStop(packageId) }
                }
                "foreground" -> runTransportStep(steps, "queryForegroundPackage") {
                    transport.queryForegroundPackage()
                }
                "list-packages" -> runTransportStep(steps, "listInstalledPackages") {
                    transport.runUtility(AdbUtility.ListInstalledPackages)
                }
                "utility" -> {
                    val utility = parseUtility(intent.requiredString(ExtraUtility))
                    runTransportStep(steps, "utility:${utility.name}") {
                        transport.runUtility(utility)
                    }
                }
            }
        }

        return JSONObject().apply {
            put("schemaVersion", SchemaVersion)
            put("command", "quest-$command")
            put("overall", determineOverall(steps))
            put("endpoint", endpoint)
            put("startedAtUtc", startedAt.toString())
            put("endedAtUtc", Instant.now().toString())
            put("steps", steps)
        }
    }

    private suspend fun runQuestUsbTransportCommand(
        startedAt: Instant,
        command: String
    ): JSONObject {
        val steps = JSONArray()
        val transport = LiveAdbTransport(applicationContext)
        when (command) {
            "inspect-usb" -> runTransportStep(steps, "inspectUsb") {
                transport.inspectUsb()
            }
            "probe-usb" -> runTransportStep(steps, "probeUsbAdb") {
                transport.probeUsbAdb()
            }
            "enable-wifi-adb" -> runTransportStep(steps, "enableWifiAdbFromUsb") {
                transport.enableWifiFromUsb()
            }
            else -> error("Unknown USB transport command `$command`.")
        }

        return JSONObject().apply {
            put("schemaVersion", SchemaVersion)
            put("command", "quest-$command")
            put("overall", determineOverall(steps))
            put("startedAtUtc", startedAt.toString())
            put("endedAtUtc", Instant.now().toString())
            put("steps", steps)
        }
    }

    private suspend fun runTransportStep(
        steps: JSONArray,
        name: String,
        critical: Boolean = true,
        block: suspend () -> TransportResult
    ): TransportResult {
        val stepStartedAt = Instant.now()
        val result = runCatching { block() }.getOrElse { throwable ->
            TransportResult(
                kind = TransportKind.Error,
                summary = "$name threw ${throwable.javaClass.simpleName}.",
                detail = throwable.message ?: ""
            )
        }
        steps.put(result.toStepJson(name, critical, stepStartedAt))
        return result
    }

    private suspend fun runOscStep(
        steps: JSONArray,
        endpoint: String,
        oscPort: Int
    ) {
        val stepStartedAt = Instant.now()
        val host = endpoint.substringBefore(":")
        val result = runCatching {
            OscUdpTransport().send(
                host = host,
                port = oscPort,
                message = OscMessage(
                    address = "/rusty-xr/probe",
                    arguments = listOf(OscArgument.StringValue("phone-agent"))
                )
            )
        }
        val step = JSONObject().apply {
            put("name", "oscUdpSend")
            put("critical", false)
            put("kind", if (result.isSuccess) TransportKind.Success.name else TransportKind.Error.name)
            put("summary", result.getOrNull()?.summary() ?: "OSC UDP send failed.")
            put("detail", result.getOrNull()?.detail() ?: (result.exceptionOrNull()?.message ?: ""))
            put("host", host)
            put("port", oscPort)
            put("startedAtUtc", stepStartedAt.toString())
            put("endedAtUtc", Instant.now().toString())
        }
        steps.put(step)
    }

    private fun determineOverall(steps: JSONArray): String {
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            if (step.optBoolean("critical") && step.optString("kind") == TransportKind.Error.name) {
                return "failed"
            }
        }
        return "passed"
    }

    private fun errorReport(
        startedAt: Instant,
        command: String,
        summary: String,
        detail: String
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", SchemaVersion)
        put("command", command)
        put("overall", "failed")
        put("summary", summary)
        put("detail", detail)
        put("startedAtUtc", startedAt.toString())
        put("endedAtUtc", Instant.now().toString())
    }

    private fun Intent.endpoint(): String =
        requiredStringOrNull(ExtraEndpoint) ?: DefaultEndpoint

    private fun Intent.requiredString(extraName: String): String =
        requiredStringOrNull(extraName) ?: error("Missing required extra `$extraName`.")

    private fun Intent.requiredStringOrNull(extraName: String): String? =
        getStringExtra(extraName)?.trim()?.takeIf { it.isNotBlank() }

    private fun Intent.extrasJsonMap(): Map<String, String> {
        val raw = requiredStringOrNull(ExtraLaunchExtrasJson) ?: return emptyMap()
        val json = JSONObject(raw)
        return json.keys().asSequence()
            .associateWith { key -> json.opt(key)?.toString().orEmpty() }
    }

    private fun parseEyes(raw: String?): List<String> {
        val eyes = ArrayList<String>()
        fun add(value: String) {
            when (value.trim().lowercase(Locale.US)) {
                "left", "l" -> if (!eyes.contains("left")) eyes.add("left")
                "right", "r" -> if (!eyes.contains("right")) eyes.add("right")
                "mono", "m" -> if (!eyes.contains("mono")) eyes.add("mono")
                "both", "stereo" -> {
                    add("left")
                    add("right")
                }
            }
        }
        raw.orEmpty()
            .split(",", ";", " ")
            .filter { it.isNotBlank() }
            .forEach { add(it) }
        if (eyes.isEmpty()) {
            add("left")
            add("right")
        }
        return eyes
    }

    private fun sessionDurationMs(intent: Intent): Int {
        val fromMs = if (intent.hasExtra(ExtraSessionDurationMs)) {
            intent.longExtra(ExtraSessionDurationMs, 0L)
        } else {
            null
        }
        val fromSeconds = if (intent.hasExtra(ExtraSessionDurationS)) {
            intent.longExtra(ExtraSessionDurationS, 0L) * 1000L
        } else {
            null
        }
        val requested = fromMs ?: fromSeconds ?: DefaultQ2QDurationMs.toLong()
        return requested.coerceIn(0L, MaxQ2QDurationMs.toLong()).toInt()
    }

    private fun durationMs(intent: Intent, sessionBudgetMs: Int): Int {
        val requested = if (intent.hasExtra(ExtraDurationMs)) {
            intent.longExtra(ExtraDurationMs, sessionBudgetMs.toLong())
        } else {
            sessionBudgetMs.toLong()
        }
        return requested.coerceIn(MinQ2QDurationMs.toLong(), MaxQ2QDurationMs.toLong()).toInt()
    }

    private fun connectTimeoutMs(intent: Intent, sessionBudgetMs: Int): Int {
        val requested = if (intent.hasExtra(ExtraConnectTimeoutMs)) {
            intent.longExtra(ExtraConnectTimeoutMs, sessionBudgetMs.toLong())
        } else {
            sessionBudgetMs.toLong()
        }
        return requested.coerceIn(MinQ2QConnectTimeoutMs.toLong(), MaxQ2QConnectTimeoutMs.toLong()).toInt()
    }

    @Suppress("DEPRECATION")
    private fun Intent.longExtra(extraName: String, defaultValue: Long): Long {
        val value = extras?.get(extraName) ?: return defaultValue
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun normalizeQ2QSourceMode(value: String): String =
        when (value.trim().lowercase(Locale.US)) {
            "camera", "camera2", "phone-camera", "phone_camera", "camera2_surface" -> SourceModeCamera2
            else -> SourceModeSyntheticSurface
        }

    private fun parseUtility(value: String): AdbUtility {
        return when (value.trim().lowercase(Locale.US)) {
            "home" -> AdbUtility.Home
            "back" -> AdbUtility.Back
            "wake" -> AdbUtility.Wake
            "reboot" -> AdbUtility.Reboot
            "list", "list-installed-packages", "listinstalledpackages" -> AdbUtility.ListInstalledPackages
            else -> error("Unknown utility `$value`.")
        }
    }

    companion object {
        const val ActionRunAgentCommand = "io.github.mesmerprism.rustyxr.companion.android.RUN_AGENT_COMMAND"

        const val ExtraCommand = "command"
        const val ExtraEndpoint = "endpoint"
        const val ExtraOscPort = "osc_port"
        const val ExtraTimeoutMs = "timeout_ms"
        const val ExtraDurationMs = "duration_ms"
        const val ExtraConnectTimeoutMs = "connect_timeout_ms"
        const val ExtraDeviceAddress = "device_address"
        const val ExtraApkFile = "apk_file"
        const val ExtraPackageId = "package_id"
        const val ExtraComponent = "component"
        const val ExtraLaunchExtrasJson = "extras_json"
        const val ExtraUtility = "utility"
        const val ExtraMode = "mode"
        const val ExtraQ2QMode = "q2q_mode"
        const val ExtraSessionId = "session_id"
        const val ExtraSendSessionId = "send_session_id"
        const val ExtraReceiveSessionId = "receive_session_id"
        const val ExtraRelayHost = "relay_host"
        const val ExtraRelayPort = "relay_port"
        const val ExtraRelayToken = "relay_token"
        const val ExtraRelayTls = "relay_tls"
        const val ExtraRelayInsecureTls = "relay_insecure_tls"
        const val ExtraRelayServerName = "relay_server_name"
        const val ExtraRelayChannel = "relay_channel"
        const val ExtraSessionDurationMs = "session_duration_ms"
        const val ExtraSessionDurationS = "session_duration_s"
        const val ExtraEyes = "eyes"
        const val ExtraWidth = "width"
        const val ExtraHeight = "height"
        const val ExtraBitrateBps = "bitrate_bps"
        const val ExtraFrameRateHz = "frame_rate_hz"
        const val ExtraSourceMode = "source_mode"
        const val ExtraQualityProfile = "quality_profile"
        const val ExtraCameraId = "camera_id"
        const val ExtraCameraFacing = "camera_facing"
        const val ExtraSameCameraToEyes = "same_camera_to_eyes"
        const val ExtraDisplayReceiver = "display_receiver"
        const val ExtraDisplayEye = "display_eye"
        const val ExtraLabel = "label"

        const val CommandPolarPmdSmoke = "polar-pmd-smoke"
        const val CommandQ2QRelay = "q2q-relay"
        const val CommandQuestSuite = "quest-suite"
        const val CommandQuestInspectUsb = "quest-inspect-usb"
        const val CommandQuestProbeUsb = "quest-probe-usb"
        const val CommandQuestEnableWifiAdb = "quest-enable-wifi-adb"
        const val CommandQuestConnect = "quest-connect"
        const val CommandQuestInstall = "quest-install"
        const val CommandQuestLaunch = "quest-launch"
        const val CommandQuestStop = "quest-stop"
        const val CommandQuestForeground = "quest-foreground"
        const val CommandQuestListPackages = "quest-list-packages"
        const val CommandQuestUtility = "quest-utility"

        private const val SchemaVersion = "rusty.xr.android-companion.agent-command.v1"
        private const val DefaultEndpoint = "192.168.43.1:5555"
        private const val DefaultOscPort = 9000
        private const val DefaultPolarTimeoutMs = 45_000L
        private const val MinPolarTimeoutMs = 3_000L
        private const val MaxPolarTimeoutMs = 90_000L
        private const val DefaultQ2QMode = "duplex"
        private const val DefaultQ2QRelayHost = "127.0.0.1"
        private const val DefaultQ2QRelayPort = 9443
        private const val DefaultQ2QSendSessionId = "phone-to-quest"
        private const val DefaultQ2QReceiveSessionId = "quest-to-phone"
        private const val DefaultQ2QDurationMs = 0
        private const val MinQ2QDurationMs = 0
        private const val MaxQ2QDurationMs = 6 * 60 * 60 * 1000
        private const val MinQ2QConnectTimeoutMs = 0
        private const val MaxQ2QConnectTimeoutMs = 6 * 60 * 60 * 1000
        private const val DefaultQ2QSyntheticWidth = 1280
        private const val DefaultQ2QSyntheticHeight = 1280
        private const val DefaultQ2QCameraRequestedWidth = 0
        private const val DefaultQ2QCameraRequestedHeight = 0
        private const val MaxQ2QDimension = 4096
        private const val DefaultQ2QBitrateBps = 20_000_000
        private const val MaxQ2QBitrateBps = 50_000_000
        private const val DefaultQ2QFrameRateHz = 60
        private const val SourceModeSyntheticSurface = "synthetic_surface"
        private const val SourceModeCamera2 = "camera2_surface"

        private val SupportedCommands = listOf(
            CommandPolarPmdSmoke,
            CommandQ2QRelay,
            CommandQuestSuite,
            CommandQuestInspectUsb,
            CommandQuestProbeUsb,
            CommandQuestEnableWifiAdb,
            CommandQuestConnect,
            CommandQuestInstall,
            CommandQuestLaunch,
            CommandQuestStop,
            CommandQuestForeground,
            CommandQuestListPackages,
            CommandQuestUtility
        )
    }
}

private fun TransportResult.toStepJson(
    name: String,
    critical: Boolean,
    stepStartedAt: Instant
): JSONObject = JSONObject().apply {
    put("name", name)
    put("critical", critical)
    put("kind", kind.name)
    put("summary", summary)
    put("detail", detail)
    put("endpoint", endpoint ?: JSONObject.NULL)
    put("packageId", packageId ?: JSONObject.NULL)
    put("networkSummary", networkSummary)
    put("networkDetail", networkDetail)
    put("itemCount", items.size)
    put("itemsPreview", JSONArray(items.take(24)))
    put("startedAtUtc", stepStartedAt.toString())
    put("endedAtUtc", Instant.now().toString())
}

private fun PolarPmdSmokeResult.toJson(): JSONObject = JSONObject().apply {
    put("passed", passed)
    put("summary", summary)
    put("detail", detail)
    put("startedAtUtc", startedAtUtc)
    put("endedAtUtc", endedAtUtc)
    put("deviceName", deviceName ?: JSONObject.NULL)
    put("deviceAddress", deviceAddress ?: JSONObject.NULL)
    put("rssi", rssi ?: JSONObject.NULL)
    put("batteryPercent", batteryPercent ?: JSONObject.NULL)
    put("negotiatedMtu", negotiatedMtu ?: JSONObject.NULL)
    put("heartRateServiceVisible", heartRateServiceVisible)
    put("pmdServiceVisible", pmdServiceVisible)
    put("ecgFrameCount", ecgFrameCount)
    put("ecgSampleCount", ecgSampleCount)
    put("latestEcgFrame", latestEcgFrame?.toJson() ?: JSONObject.NULL)
    put("accFrameCount", accFrameCount)
    put("accSampleCount", accSampleCount)
    put("latestAccFrame", latestAccFrame?.toJson() ?: JSONObject.NULL)
    put("controlResponses", JSONArray(controlResponses.map { it.toJson() }))
    put("settings", JSONArray(settings.map { it.toJson() }))
    put("notes", JSONArray(notes))
}

private fun PolarPmdEcgFrameSummary.toJson(): JSONObject = JSONObject().apply {
    put("timestampNs", timestampNs)
    put("sampleCount", sampleCount)
    put("minMicroVolts", minMicroVolts)
    put("maxMicroVolts", maxMicroVolts)
    put("firstMicroVolts", firstMicroVolts)
}

private fun PolarPmdAccFrameSummary.toJson(): JSONObject = JSONObject().apply {
    put("timestampNs", timestampNs)
    put("sampleCount", sampleCount)
    put("firstXmg", firstXmg)
    put("firstYmg", firstYmg)
    put("firstZmg", firstZmg)
    put("compressed", compressed)
}

private fun PolarPmdControlSummary.toJson(): JSONObject = JSONObject().apply {
    put("frameId", frameId)
    put("opCode", opCode)
    put("measurementType", measurementType)
    put("errorCode", errorCode)
    put("success", success)
    put("payloadHex", payloadHex)
}

private fun PolarPmdSettingsSummary.toJson(): JSONObject = JSONObject().apply {
    put("measurementType", measurementType)
    put("sampleRates", JSONArray(sampleRates))
    put("resolutions", JSONArray(resolutions))
    put("ranges", JSONArray(ranges))
}
