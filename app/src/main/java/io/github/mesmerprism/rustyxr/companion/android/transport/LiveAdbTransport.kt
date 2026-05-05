package io.github.mesmerprism.rustyxr.companion.android.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbConnectionObserver
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import com.cgutman.adblib.TcpChannel
import com.cgutman.adblib.UsbChannel
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsSession
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsSnapshot
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsStage
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.FailureClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections
import kotlin.coroutines.resume

class LiveAdbTransport(
    context: Context
) : AdbTransport {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mutex = Mutex()
    private var activeEndpoint: AdbEndpoint? = null
    private val usbPermissionAction = "${appContext.packageName}.USB_PERMISSION"
    private val adbCrypto: AdbCrypto by lazy { loadOrCreateCrypto() }
    private val diagnosticsSession = DiagnosticsSession()
    @Volatile
    private var lastCompletedDiagnostics: DiagnosticsSnapshot? = null
    @Volatile
    private var lastAttemptDiagnostics: DiagnosticsSnapshot? = null
    private val _operationProgress = MutableStateFlow<TransportOperationProgress?>(null)

    override val operationProgress: StateFlow<TransportOperationProgress?> = _operationProgress.asStateFlow()

    /** Returns an immutable snapshot of the latest diagnostics session. */
    fun latestDiagnostics(): DiagnosticsSnapshot =
        lastAttemptDiagnostics ?: lastCompletedDiagnostics ?: diagnosticsSession.snapshot()

    private inline fun <T> trackOperation(block: () -> T): T {
        return try {
            block()
        } finally {
            _operationProgress.value = null
        }
    }

    private fun emitOperationProgress(
        kind: TransportOperationKind,
        title: String,
        detail: String = "",
        stepIndex: Int = 0,
        stepCount: Int = 0,
        fraction: Float? = null,
        currentBytes: Long? = null,
        totalBytes: Long? = null,
        packageId: String? = null,
        utility: AdbUtility? = null
    ) {
        _operationProgress.value = TransportOperationProgress(
            kind = kind,
            title = title,
            detail = detail,
            stepIndex = stepIndex,
            stepCount = stepCount,
            fraction = fraction?.coerceIn(0f, 1f),
            currentBytes = currentBytes,
            totalBytes = totalBytes,
            packageId = packageId,
            utility = utility
        )
    }

    private fun createAdbObserver(): AdbConnectionObserver = object : AdbConnectionObserver {
        override fun onAuthToken() {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, true, "AUTH token received.")
        }
        override fun onAuthSignatureSent() {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, true, "AUTH signature sent.")
        }
        override fun onAuthPublicKeySent() {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, true, "AUTH RSA public key sent.")
        }
        override fun onProtocolNotice(message: String) {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, true, "ADB protocol notice.", message)
        }
        override fun onConnected(maxData: Int) {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, true, "ADB handshake complete.", "maxData=$maxData")
        }
        override fun onConnectionTimeout() {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, false, "ADB handshake timed out.")
        }
        override fun onConnectionThreadError(message: String?) {
            diagnosticsSession.record(DiagnosticsStage.AdbAuthHandshake, false, "Connection thread error.", message ?: "")
        }
        override fun onStreamOpenTimeout(destination: String?) {
            diagnosticsSession.record(DiagnosticsStage.ShellRouteQuery, false, "Stream open timed out.", destination ?: "")
        }
        override fun onStreamOpenRejected(destination: String?) {
            diagnosticsSession.record(DiagnosticsStage.ShellRouteQuery, false, "Stream open rejected.", destination ?: "")
        }
    }

    override suspend fun inspectUsb(): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            diagnosticsSession.start("inspectUsb")

            val usbDevices = waitForUsbDevices()
            diagnosticsSession.record(
                DiagnosticsStage.UsbDeviceDiscovery,
                usbDevices.isNotEmpty(),
                if (usbDevices.isNotEmpty()) "Found ${usbDevices.size} USB device(s)." else "No USB devices visible.",
                payload = buildUsbInventoryDetail(usbDevices)
            )
            if (usbDevices.isEmpty()) {
                finishDiagnostics(FailureClass.NoUsbExposure, preferForExport = false)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "No USB devices are visible to the phone app.",
                    detail = "UsbManager.deviceList is empty. Android is not exposing any host-side USB device to this app right now. The phone must be on the USB host side; if direct USB-C only charges, try a phone-side OTG adapter or powered hub."
                )
            }

            val adbDevice = findUsbAdbDevice(usbDevices)
            val detail = buildUsbInventoryDetail(usbDevices)
            diagnosticsSession.record(
                DiagnosticsStage.UsbAdbInterfaceCheck,
                adbDevice != null,
                if (adbDevice != null) "ADB interface found." else "No ADB interface on visible devices.",
                payload = detail
            )
            if (adbDevice == null) {
                finishDiagnostics(FailureClass.NoAdbInterface, preferForExport = false)
                return@withLock TransportResult(
                    kind = TransportKind.Partial,
                    summary = "USB devices are visible, but none expose an ADB interface.",
                    detail = detail
                )
            }

            finishDiagnostics(preferForExport = false)
            TransportResult(
                kind = TransportKind.Success,
                summary = "An ADB-class USB device is visible to the phone app.",
                detail = detail
            )
        }
    }

    override suspend fun probeUsbAdb(): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            diagnosticsSession.start("probeUsbAdb")

            val usbDevices = waitForUsbDevices()
            diagnosticsSession.record(
                DiagnosticsStage.UsbDeviceDiscovery,
                usbDevices.isNotEmpty(),
                if (usbDevices.isNotEmpty()) "Found ${usbDevices.size} USB device(s)." else "No USB devices visible.",
                payload = buildUsbInventoryDetail(usbDevices)
            )
            if (usbDevices.isEmpty()) {
                finishDiagnostics(FailureClass.NoUsbExposure, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "No USB device is visible to the phone app.",
                    detail = "Android is not exposing any USB host device to this app right now. Keep the Quest awake, leave it unplugged from the laptop, and make sure the phone is on the USB host side. If direct USB-C only charges, try a phone-side OTG adapter or powered hub."
                )
            }

            val usbDevice = findUsbAdbDevice(usbDevices)
            diagnosticsSession.record(
                DiagnosticsStage.UsbAdbInterfaceCheck,
                usbDevice != null,
                if (usbDevice != null) "ADB interface found." else "No ADB interface on visible devices.",
                payload = buildUsbInventoryDetail(usbDevices)
            )
            if (usbDevice == null) {
                finishDiagnostics(FailureClass.NoAdbInterface, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "A USB device is connected, but it is not exposing an ADB interface.",
                    detail = buildUsbInventoryDetail(usbDevices)
                )
            }

            val adbInterface = findAdbInterface(usbDevice)
            if (adbInterface == null) {
                finishDiagnostics(FailureClass.NoAdbInterface, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "Connected USB device is not exposing an ADB interface.",
                    detail = buildUsbInventoryDetail(listOf(usbDevice))
                )
            }

            val permissionGranted = ensureUsbPermission(usbDevice)
            diagnosticsSession.record(
                DiagnosticsStage.UsbPermissionRequest,
                permissionGranted,
                if (permissionGranted) "USB permission granted." else "USB permission not granted."
            )
            if (!permissionGranted) {
                finishDiagnostics(FailureClass.UsbPermissionTimeout, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "USB permission did not complete.",
                    detail = "Android did not hand the Quest USB device to this app. Close the Quest file browser if it opened, bring this app back to the front, and allow USB access for the phone app."
                )
            }

            return@withLock try {
                diagnosticsSession.record(DiagnosticsStage.UsbAdbConnect, true, "Opening USB ADB connection for shell probe.")
                val result = withUsbCommandRetry(usbDevice, adbInterface) { connection ->
                    val model = readQuestModel(connection)
                    val batteryDump = runShell(connection, "dumpsys battery")
                    val batterySummary = extractBatterySummary(batteryDump)
                    diagnosticsSession.record(
                        DiagnosticsStage.ShellEchoVerify,
                        true,
                        "Quest shell probe succeeded.",
                        payload = condensedOutput(
                            listOf(
                                "model=$model",
                                batterySummary?.let { "battery=$it" }
                            ).filterNotNull().joinToString(" ")
                        )
                    )
                    val capabilityProbe = runAdbCapabilityProbe(connection)
                    TransportResult(
                        kind = TransportKind.Success,
                        summary = "USB ADB probe succeeded.",
                        detail = listOf(
                            "Quest responded over USB ADB.",
                            "Model: $model.",
                            batterySummary?.let { "Battery: $it." },
                            capabilityProbe
                        ).filterNotNull().joinToString(" ")
                    )
                }
                finishDiagnostics(preferForExport = true)
                result
            } catch (exception: Exception) {
                diagnosticsSession.record(
                    DiagnosticsStage.ShellEchoVerify,
                    false,
                    "Quest shell probe failed.",
                    usbBootstrapErrorDetail(exception),
                    payload = exception.message ?: ""
                )
                finishDiagnostics(classifyBootstrapException(exception), preferForExport = true)
                TransportResult(
                    kind = TransportKind.Error,
                    summary = "USB ADB probe failed.",
                    detail = usbBootstrapErrorDetail(exception)
                )
            }
        }
    }

    override suspend fun enableWifiFromUsb(): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            diagnosticsSession.start("enableWifiFromUsb")

            val usbDevices = waitForUsbDevices()
            diagnosticsSession.record(
                DiagnosticsStage.UsbDeviceDiscovery,
                usbDevices.isNotEmpty(),
                if (usbDevices.isNotEmpty()) "Found ${usbDevices.size} USB device(s)." else "No USB devices visible.",
                payload = buildUsbInventoryDetail(usbDevices)
            )
            if (usbDevices.isEmpty()) {
                finishDiagnostics(FailureClass.NoUsbExposure, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "No USB device is visible to the phone app.",
                    detail = "Android is not exposing any USB host device to this app right now. Keep the Quest awake, leave it unplugged from the laptop, wait a moment after plugging into the phone, and make sure the phone is acting as the USB host side rather than as a charge-only peripheral. If direct USB-C only charges, try a phone-side OTG adapter or powered hub."
                )
            }

            val usbDevice = findUsbAdbDevice(usbDevices)
            diagnosticsSession.record(
                DiagnosticsStage.UsbAdbInterfaceCheck,
                usbDevice != null,
                if (usbDevice != null) "ADB interface found." else "No ADB interface on visible devices.",
                payload = buildUsbInventoryDetail(usbDevices)
            )
            if (usbDevice == null) {
                finishDiagnostics(FailureClass.NoAdbInterface, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "A USB device is connected, but it is not exposing an ADB interface.",
                    detail = buildUsbInventoryDetail(usbDevices)
                )
            }

            val adbInterface = findAdbInterface(usbDevice)
            if (adbInterface == null) {
                finishDiagnostics(FailureClass.NoAdbInterface, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "Connected USB device is not exposing an ADB interface.",
                    detail = buildUsbInventoryDetail(listOf(usbDevice))
                )
            }

            val permissionGranted = ensureUsbPermission(usbDevice)
            diagnosticsSession.record(
                DiagnosticsStage.UsbPermissionRequest,
                permissionGranted,
                if (permissionGranted) "USB permission granted." else "USB permission not granted."
            )
            if (!permissionGranted) {
                finishDiagnostics(FailureClass.UsbPermissionTimeout, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "USB permission did not complete.",
                    detail = "Android did not hand the Quest USB device to this app. Close the Quest file browser if it opened, bring this app back to the front, and allow USB access for the phone app."
                )
            }

            val routeOutput: String
            val addrOutput: String
            val tcpipOutput: String
            try {
                diagnosticsSession.record(DiagnosticsStage.UsbAdbConnect, true, "Opening USB ADB connection for route query.")
                val routeProbe = withUsbCommandRetry(usbDevice, adbInterface) { connection ->
                    val route = runService(connection, "shell:ip route")
                    val addr = runService(connection, "shell:ip addr show wlan0")
                    runCatching { runService(connection, "shell:exec date") }
                    route to addr
                }
                routeOutput = routeProbe.first
                addrOutput = routeProbe.second
                diagnosticsSession.record(
                    DiagnosticsStage.ShellRouteQuery, true, "Route query succeeded.",
                    payload = condensedOutput(
                        listOfNotNull(
                            routeOutput.takeIf { it.isNotBlank() }?.let { "ip route: $it" },
                            addrOutput.takeIf { it.isNotBlank() }?.let { "ip addr show wlan0: $it" }
                        ).joinToString(" | ")
                    )
                )
            } catch (exception: Exception) {
                val detail = usbBootstrapErrorDetail(exception)
                val failure = classifyBootstrapException(exception)
                diagnosticsSession.record(
                    DiagnosticsStage.ShellRouteQuery, false,
                    "USB ADB route query failed.", detail,
                    payload = exception.message ?: ""
                )
                finishDiagnostics(failure, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "USB ADB connection to Quest failed.",
                    detail = detail
                )
            }

            val networkStateSummary = condensedOutput(
                listOf(routeOutput, addrOutput)
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
            )
            val endpoint = parseEndpointFromNetworkState(routeOutput, addrOutput)
            diagnosticsSession.record(
                DiagnosticsStage.EndpointParse,
                endpoint != null,
                if (endpoint != null) "Parsed endpoint ${endpoint.normalized}." else "Could not parse endpoint from route output.",
                payload = condensedOutput(
                    listOfNotNull(
                        routeOutput.takeIf { it.isNotBlank() }?.let { "ip route: $it" },
                        addrOutput.takeIf { it.isNotBlank() }?.let { "ip addr show wlan0: $it" }
                    ).joinToString(" | ")
                )
            )
            if (endpoint == null) {
                finishDiagnostics(FailureClass.EndpointParseFailure, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "Could not resolve the Quest Wi-Fi IP.",
                    detail = "Quest network output did not expose a usable Wi-Fi IPv4 address. $networkStateSummary"
                )
            }

            val routeDetail = "Resolved Quest hotspot endpoint ${endpoint.normalized} from `$networkStateSummary`."
            val networkObservation = observeEndpointNetwork(endpoint)
            diagnosticsSession.record(
                DiagnosticsStage.SubnetCheck,
                networkObservation.sameSubnet != false,
                networkObservation.summary,
                networkObservation.detail
            )

            try {
                diagnosticsSession.record(DiagnosticsStage.TcpipEnable, true, "Sending tcpip:${endpoint.port} over USB.")
                tcpipOutput = withUsbCommandRetry(usbDevice, adbInterface) { connection ->
                    runService(connection, "tcpip:${endpoint.port}")
                }
                diagnosticsSession.record(
                    DiagnosticsStage.TcpipEnable, true, "tcpip command completed.",
                    payload = condensedOutput(tcpipOutput)
                )
            } catch (exception: Exception) {
                diagnosticsSession.record(
                    DiagnosticsStage.TcpipEnable, false,
                    "tcpip command failed.", exception.message ?: "",
                    payload = exception.message ?: ""
                )
                val connectAttempt = attemptTcpConnect(endpoint)
                if (connectAttempt.kind != TransportKind.Success) {
                    val fallbackDetail = usbTcpipErrorDetail(exception)
                    finishDiagnostics(classifyBootstrapException(exception), preferForExport = true)
                    return@withLock TransportResult(
                        kind = TransportKind.Error,
                        summary = "Failed to restart Quest ADB in Wi-Fi mode.",
                        detail = listOf(
                            routeDetail,
                            fallbackDetail
                        ).filter { it.isNotBlank() }.joinToString(" "),
                        endpoint = endpoint.normalized,
                        networkSummary = networkObservation.summary,
                        networkDetail = networkObservation.detail
                    )
                }
                diagnosticsSession.record(DiagnosticsStage.TcpConnect, true, "TCP fallback connect succeeded after tcpip failure.")
                finishDiagnostics(preferForExport = true)
                activeEndpoint = endpoint
                return@withLock connectAttempt.copy(
                    summary = "Enabled Wi-Fi ADB from USB and connected to ${endpoint.normalized}.",
                    detail = listOf(routeDetail, connectAttempt.detail)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    endpoint = endpoint.normalized,
                    networkSummary = networkObservation.summary,
                    networkDetail = networkObservation.detail
                )
            }

            delay(1200)
            diagnosticsSession.record(DiagnosticsStage.TcpConnect, true, "Attempting TCP connect to ${endpoint.normalized}.")
            val connectAttempt = attemptTcpConnect(endpoint)
            if (connectAttempt.kind != TransportKind.Success) {
                diagnosticsSession.record(
                    DiagnosticsStage.TcpConnect, false,
                    "TCP connect failed after tcpip enable.", connectAttempt.detail
                )
                finishDiagnostics(FailureClass.TcpConnectRefused, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Partial,
                    summary = bootstrapPartialSummary(endpoint, networkObservation),
                    detail = listOf(routeDetail, condensedOutput(tcpipOutput), connectAttempt.summary, connectAttempt.detail)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    endpoint = endpoint.normalized,
                    networkSummary = networkObservation.summary,
                    networkDetail = networkObservation.detail
                )
            }

            diagnosticsSession.record(DiagnosticsStage.TcpConnect, true, "TCP connect to ${endpoint.normalized} succeeded.")
            finishDiagnostics(preferForExport = true)
            activeEndpoint = endpoint
            connectAttempt.copy(
                summary = "Enabled Wi-Fi ADB from USB and connected to ${endpoint.normalized}.",
                detail = listOf(routeDetail, condensedOutput(tcpipOutput))
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                endpoint = endpoint.normalized,
                networkSummary = networkObservation.summary,
                networkDetail = networkObservation.detail
            )
        }
    }

    override suspend fun connect(endpoint: String): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            diagnosticsSession.start("connect")

            val parsed = parseEndpoint(endpoint)
            diagnosticsSession.record(
                DiagnosticsStage.EndpointParse,
                parsed != null,
                if (parsed != null) "Parsed endpoint ${parsed.normalized}." else "Invalid endpoint: $endpoint."
            )
            if (parsed == null) {
                finishDiagnostics(FailureClass.EndpointParseFailure, preferForExport = true)
                return@withLock TransportResult(
                    kind = TransportKind.Error,
                    summary = "Invalid Quest endpoint.",
                    detail = "Use a reachable Quest host like 10.130.207.42:5555."
                )
            }

            val networkObservation = observeEndpointNetwork(parsed)
            diagnosticsSession.record(
                DiagnosticsStage.SubnetCheck,
                networkObservation.sameSubnet != false,
                networkObservation.summary,
                networkObservation.detail
            )

            diagnosticsSession.record(DiagnosticsStage.TcpConnect, true, "Attempting TCP connect to ${parsed.normalized}.")
            val result = attemptTcpConnect(parsed)
            if (result.kind == TransportKind.Success) {
                activeEndpoint = parsed
                diagnosticsSession.record(DiagnosticsStage.TcpConnect, true, "TCP connect succeeded.")
                finishDiagnostics(preferForExport = true)
            } else {
                diagnosticsSession.record(DiagnosticsStage.TcpConnect, false, "TCP connect failed.", result.detail)
                finishDiagnostics(FailureClass.TcpConnectRefused, preferForExport = true)
            }
            result.copy(
                networkSummary = networkObservation.summary,
                networkDetail = networkObservation.detail
            )
        }
    }

    override suspend fun install(apkFile: String, packageId: String): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()
            trackOperation {
                    emitOperationProgress(
                        kind = TransportOperationKind.InstallApk,
                        title = "Checking staged APK",
                        detail = "Looking for $apkFile on phone storage before opening the Quest session.",
                        stepIndex = 1,
                        stepCount = 4,
                        fraction = 0.02f,
                        packageId = packageId
                    )
                val stagedApk = findStagedApk(apkFile)
                    ?: return@trackOperation TransportResult(
                        kind = TransportKind.Error,
                        summary = "Staged APK not found for $apkFile.",
                        detail = "Push the APK to ${describeStagingPaths(apkFile)} before installing from the phone."
                    )

                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.InstallApk,
                        title = "Opening Quest install session",
                        detail = "Connecting to ${endpoint.normalized} for $packageId.",
                        stepIndex = 2,
                        stepCount = 4,
                        fraction = 0.08f,
                        packageId = packageId
                    )
                    withTcpConnection(endpoint) { connection ->
                        val installOutput = installInputStream(
                            connection = connection,
                            source = stagedApk.inputStream(),
                            sizeBytes = stagedApk.length(),
                            onProgress = { sentBytes, totalBytes ->
                                emitOperationProgress(
                                    kind = TransportOperationKind.InstallApk,
                                    title = "Streaming APK to Quest",
                                    detail = "Writing $apkFile directly to the Quest package manager.",
                                    stepIndex = 3,
                                    stepCount = 4,
                                    fraction = 0.12f + (0.7f * transferFraction(sentBytes, totalBytes)),
                                    currentBytes = sentBytes,
                                    totalBytes = totalBytes,
                                    packageId = packageId
                                )
                            }
                        )
                        emitOperationProgress(
                            kind = TransportOperationKind.InstallApk,
                            title = "Verifying package install",
                            detail = "Checking pm path for $packageId after package manager returned.",
                            stepIndex = 4,
                            stepCount = 4,
                            fraction = 0.9f,
                            packageId = packageId
                        )
                        val verifyOutput = runShell(connection, "pm path ${shellQuote(packageId)}")

                        if (verifyOutput.contains("package:")) {
                            TransportResult(
                                kind = TransportKind.Success,
                                summary = "Installed $packageId from $apkFile.",
                                detail = condensedOutput(installOutput)
                            )
                        } else {
                            TransportResult(
                                kind = TransportKind.Error,
                                summary = "Install failed for $packageId.",
                                detail = condensedOutput("$installOutput\n$verifyOutput")
                            )
                        }
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Install failed for $packageId.",
                        detail = exception.message ?: exception.javaClass.simpleName
                    )
                }
            }
        }
    }

    override suspend fun pushHotload(packageId: String, fileName: String): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    val assetPath = "HotloadProfiles/$fileName"
                    emitOperationProgress(
                        kind = TransportOperationKind.PushHotload,
                        title = "Reading runtime preset",
                        detail = "Loading $assetPath from the bundled asset catalog.",
                        stepIndex = 1,
                        stepCount = 4,
                        fraction = 0.08f,
                        packageId = packageId
                    )
                    val hotloadBytes = appContext.assets.open(assetPath).use { it.readBytes() }
                    emitOperationProgress(
                        kind = TransportOperationKind.PushHotload,
                        title = "Opening Quest upload session",
                        detail = "Connecting to ${endpoint.normalized} for $packageId.",
                        stepIndex = 2,
                        stepCount = 4,
                        fraction = 0.18f,
                        packageId = packageId
                    )
                    withTcpConnection(endpoint) { connection ->
                        val remotePath = "/sdcard/Android/data/$packageId/files/runtime_hotload/runtime_overrides.csv"
                        pushInputStream(
                            connection = connection,
                            source = hotloadBytes.inputStream(),
                            sizeBytes = hotloadBytes.size.toLong(),
                            remotePath = remotePath,
                            onProgress = { sentBytes, totalBytes ->
                                emitOperationProgress(
                                    kind = TransportOperationKind.PushHotload,
                                    title = "Uploading runtime preset",
                                    detail = "Copying $fileName to $remotePath.",
                                    stepIndex = 3,
                                    stepCount = 4,
                                    fraction = 0.18f + (0.64f * transferFraction(sentBytes, totalBytes)),
                                    currentBytes = sentBytes,
                                    totalBytes = totalBytes,
                                    packageId = packageId
                                )
                            }
                        )
                        emitOperationProgress(
                            kind = TransportOperationKind.PushHotload,
                            title = "Runtime preset upload complete",
                            detail = remotePath,
                            stepIndex = 4,
                            stepCount = 4,
                            fraction = 0.96f,
                            packageId = packageId
                        )
                        TransportResult(
                            kind = TransportKind.Success,
                            summary = "Uploaded $fileName to $packageId.",
                            detail = remotePath
                        )
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Hotload upload failed for $packageId.",
                        detail = exception.message ?: exception.javaClass.simpleName
                    )
                }
            }
        }
    }

    override suspend fun applyDeviceProfile(
        profileId: String,
        props: Map<String, String>
    ): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.ApplyDeviceProfile,
                        title = "Opening Quest device-profile session",
                        detail = "Connecting to ${endpoint.normalized} for preset $profileId.",
                        stepIndex = 1,
                        stepCount = (props.size * 2) + 1,
                        fraction = 0.06f
                    )
                    withTcpConnection(endpoint) { connection ->
                        val applied = mutableListOf<String>()
                        val totalPhases = (props.size * 2).coerceAtLeast(1)
                        for ((index, entry) in props.entries.withIndex()) {
                            val key = entry.key
                            val value = entry.value
                            val applyPhase = (index * 2) + 1
                            emitOperationProgress(
                                kind = TransportOperationKind.ApplyDeviceProfile,
                                title = "Applying device property ${index + 1}/${props.size}",
                                detail = "Writing $key=$value on Quest.",
                                stepIndex = applyPhase + 1,
                                stepCount = totalPhases + 1,
                                fraction = 0.08f + (0.78f * (applyPhase - 1).toFloat() / totalPhases.toFloat())
                            )
                            runShell(connection, "setprop ${shellQuote(key)} ${shellQuote(value)}")
                            emitOperationProgress(
                                kind = TransportOperationKind.ApplyDeviceProfile,
                                title = "Verifying device property ${index + 1}/${props.size}",
                                detail = "Reading back $key from Quest after setprop.",
                                stepIndex = applyPhase + 2,
                                stepCount = totalPhases + 1,
                                fraction = 0.08f + (0.78f * applyPhase.toFloat() / totalPhases.toFloat())
                            )
                            val verified = runShell(connection, "getprop ${shellQuote(key)}").trim()
                            if (verified != value) {
                                return@withTcpConnection TransportResult(
                                    kind = TransportKind.Error,
                                    summary = "Device preset $profileId failed at $key.",
                                    detail = "Expected $value but Quest reported $verified."
                                )
                            }
                            applied += "$key=$verified"
                        }
                        emitOperationProgress(
                            kind = TransportOperationKind.ApplyDeviceProfile,
                            title = "Device preset verified",
                            detail = "Applied ${applied.size} property update(s) for $profileId.",
                            stepIndex = totalPhases + 1,
                            stepCount = totalPhases + 1,
                            fraction = 0.96f
                        )
                        TransportResult(
                            kind = TransportKind.Success,
                            summary = "Applied $profileId (${props.size} prop(s)).",
                            detail = applied.joinToString("; ")
                        )
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Device preset $profileId failed.",
                        detail = exception.message ?: exception.javaClass.simpleName
                    )
                }
            }
        }
    }

    override suspend fun launch(
        packageId: String,
        extras: Map<String, String>
    ): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.LaunchApp,
                        title = "Opening Quest launch session",
                        detail = "Connecting to ${endpoint.normalized} for $packageId.",
                        stepIndex = 1,
                        stepCount = 4,
                        fraction = 0.12f,
                        packageId = packageId
                    )
                    withTcpConnection(endpoint) { connection ->
                        emitOperationProgress(
                            kind = TransportOperationKind.LaunchApp,
                            title = "Resetting previous app state",
                            detail = "Force-stopping $packageId before monkey launch.",
                            stepIndex = 2,
                            stepCount = 4,
                            fraction = 0.34f,
                            packageId = packageId
                        )
                        runCatching { runShell(connection, AdbShellSupport.buildForceStopCommand(packageId, ::shellQuote)) }
                        emitOperationProgress(
                            kind = TransportOperationKind.LaunchApp,
                            title = "Sending launch command",
                            detail = if (extras.isEmpty()) {
                                "Issuing monkey launch for $packageId on Quest."
                            } else {
                                "Issuing main Activity launch for $packageId with ${extras.size} runtime extra(s)."
                            },
                            stepIndex = 3,
                            stepCount = 4,
                            fraction = 0.62f,
                            packageId = packageId
                        )
                        val launchCommand = if (extras.isEmpty()) {
                            AdbShellSupport.buildMonkeyLaunchCommand(packageId, ::shellQuote)
                        } else {
                            AdbShellSupport.buildMainLauncherLaunchCommand(packageId, ::shellQuote, extras)
                        }
                        val launchOutput = runShell(
                            connection,
                            launchCommand
                        )
                        emitOperationProgress(
                            kind = TransportOperationKind.LaunchApp,
                            title = "Checking launch response",
                            detail = "Reading the Quest shell response for $packageId.",
                            stepIndex = 4,
                            stepCount = 4,
                            fraction = 0.9f,
                            packageId = packageId
                        )
                        val launchSucceeded = if (extras.isEmpty()) {
                            launchOutput.contains("Events injected: 1")
                        } else {
                            amStartSucceeded(launchOutput)
                        }
                        if (launchSucceeded) {
                            TransportResult(
                                kind = TransportKind.Success,
                                summary = if (extras.isEmpty()) {
                                    "Launch command sent for $packageId."
                                } else {
                                    "Launch command sent for $packageId with ${extras.size} runtime extra(s)."
                                },
                                detail = condensedOutput(launchOutput),
                                packageId = packageId
                            )
                        } else {
                            TransportResult(
                                kind = TransportKind.Error,
                                summary = "Launch failed for $packageId.",
                                detail = condensedOutput(launchOutput),
                                packageId = packageId
                            )
                        }
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Launch failed for $packageId.",
                        detail = exception.message ?: exception.javaClass.simpleName,
                        packageId = packageId
                    )
                }
            }
        }
    }

    override suspend fun launchIntent(
        packageId: String,
        component: String,
        extras: Map<String, String>
    ): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.LaunchApp,
                        title = "Opening Quest launch session",
                        detail = "Connecting to ${endpoint.normalized} for $packageId.",
                        stepIndex = 1,
                        stepCount = 4,
                        fraction = 0.12f,
                        packageId = packageId
                    )
                    withTcpConnection(endpoint) { connection ->
                        emitOperationProgress(
                            kind = TransportOperationKind.LaunchApp,
                            title = "Resetting previous app state",
                            detail = "Force-stopping $packageId before explicit launch.",
                            stepIndex = 2,
                            stepCount = 4,
                            fraction = 0.34f,
                            packageId = packageId
                        )
                        runCatching { runShell(connection, AdbShellSupport.buildForceStopCommand(packageId, ::shellQuote)) }
                        emitOperationProgress(
                            kind = TransportOperationKind.LaunchApp,
                            title = "Sending explicit launch intent",
                            detail = if (extras.isEmpty()) {
                                "Issuing am start for $component."
                            } else {
                                "Issuing am start for $component with ${extras.size} runtime extra(s)."
                            },
                            stepIndex = 3,
                            stepCount = 4,
                            fraction = 0.62f,
                            packageId = packageId
                        )
                        val output = runShell(
                            connection,
                            AdbShellSupport.buildExplicitLaunchCommand(component, ::shellQuote, extras)
                        )
                        emitOperationProgress(
                            kind = TransportOperationKind.LaunchApp,
                            title = "Checking launch response",
                            detail = "Reading the Quest shell response for $component.",
                            stepIndex = 4,
                            stepCount = 4,
                            fraction = 0.9f,
                            packageId = packageId
                        )
                        if (amStartSucceeded(output)) {
                            TransportResult(
                                kind = TransportKind.Success,
                                summary = if (extras.isEmpty()) {
                                    "Explicit launch sent for $packageId."
                                } else {
                                    "Explicit launch sent for $packageId with ${extras.size} runtime extra(s)."
                                },
                                detail = condensedOutput(output),
                                packageId = packageId
                            )
                        } else {
                            TransportResult(
                                kind = TransportKind.Error,
                                summary = "Explicit launch failed for $packageId.",
                                detail = condensedOutput(output),
                                packageId = packageId
                            )
                        }
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Explicit launch failed for $packageId.",
                        detail = exception.message ?: exception.javaClass.simpleName,
                        packageId = packageId
                    )
                }
            }
        }
    }

    override suspend fun forceStop(packageId: String): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.ForceStopApp,
                        title = "Opening Quest shell session",
                        detail = "Connecting to ${endpoint.normalized} for $packageId.",
                        stepIndex = 1,
                        stepCount = 3,
                        fraction = 0.18f,
                        packageId = packageId
                    )
                    withTcpConnection(endpoint) { connection ->
                        emitOperationProgress(
                            kind = TransportOperationKind.ForceStopApp,
                            title = "Sending force-stop command",
                            detail = "Issuing am force-stop for $packageId.",
                            stepIndex = 2,
                            stepCount = 3,
                            fraction = 0.54f,
                            packageId = packageId
                        )
                        val output = runShell(connection, AdbShellSupport.buildForceStopCommand(packageId, ::shellQuote))
                        emitOperationProgress(
                            kind = TransportOperationKind.ForceStopApp,
                            title = "Reading force-stop response",
                            detail = "Waiting for the Quest shell to return.",
                            stepIndex = 3,
                            stepCount = 3,
                            fraction = 0.9f,
                            packageId = packageId
                        )
                        TransportResult(
                            kind = TransportKind.Success,
                            summary = "Force-stopped $packageId.",
                            detail = condensedOutput(output),
                            packageId = packageId
                        )
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Force-stop failed for $packageId.",
                        detail = exception.message ?: exception.javaClass.simpleName,
                        packageId = packageId
                    )
                }
            }
        }
    }

    override suspend fun openUrl(
        url: String,
        browserPackageId: String
    ): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.OpenUrl,
                        title = "Opening Quest browser session",
                        detail = "Connecting to ${endpoint.normalized}${browserPackageId.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()}.",
                        stepIndex = 1,
                        stepCount = 3,
                        fraction = 0.16f,
                        packageId = browserPackageId.takeIf { it.isNotBlank() }
                    )
                    withTcpConnection(endpoint) { connection ->
                        emitOperationProgress(
                            kind = TransportOperationKind.OpenUrl,
                            title = "Sending browser intent",
                            detail = "Issuing VIEW for $url.",
                            stepIndex = 2,
                            stepCount = 3,
                            fraction = 0.52f,
                            packageId = browserPackageId.takeIf { it.isNotBlank() }
                        )
                        val output = runShell(
                            connection,
                            AdbShellSupport.buildOpenUrlCommand(
                                url = url,
                                browserPackageId = browserPackageId.takeIf { it.isNotBlank() },
                                quote = ::shellQuote
                            )
                        )
                        emitOperationProgress(
                            kind = TransportOperationKind.OpenUrl,
                            title = "Checking browser response",
                            detail = "Waiting for the Quest shell to acknowledge the VIEW intent.",
                            stepIndex = 3,
                            stepCount = 3,
                            fraction = 0.9f,
                            packageId = browserPackageId.takeIf { it.isNotBlank() }
                        )
                        if (amStartSucceeded(output)) {
                            TransportResult(
                                kind = TransportKind.Success,
                                summary = "Opened URL on Quest.",
                                detail = condensedOutput(output),
                                packageId = browserPackageId.takeIf { it.isNotBlank() }
                            )
                        } else {
                            TransportResult(
                                kind = TransportKind.Error,
                                summary = "Browser open failed.",
                                detail = condensedOutput(output),
                                packageId = browserPackageId.takeIf { it.isNotBlank() }
                            )
                        }
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Browser open failed.",
                        detail = exception.message ?: exception.javaClass.simpleName,
                        packageId = browserPackageId.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    override suspend fun queryForegroundPackage(): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    emitOperationProgress(
                        kind = TransportOperationKind.QueryForegroundPackage,
                        title = "Opening Quest shell session",
                        detail = "Connecting to ${endpoint.normalized} for foreground app inspection.",
                        stepIndex = 1,
                        stepCount = 3,
                        fraction = 0.18f
                    )
                    withTcpConnection(endpoint) { connection ->
                        emitOperationProgress(
                            kind = TransportOperationKind.QueryForegroundPackage,
                            title = "Reading activity manager state",
                            detail = "Running dumpsys activity activities on Quest.",
                            stepIndex = 2,
                            stepCount = 3,
                            fraction = 0.54f
                        )
                        val output = runShell(connection, "dumpsys activity activities")
                        emitOperationProgress(
                            kind = TransportOperationKind.QueryForegroundPackage,
                            title = "Parsing foreground package",
                            detail = "Extracting the resumed package from the Quest activity dump.",
                            stepIndex = 3,
                            stepCount = 3,
                            fraction = 0.9f
                        )
                        val packageId = AdbShellSupport.parseForegroundPackage(output)
                        if (packageId != null) {
                            TransportResult(
                                kind = TransportKind.Success,
                                summary = "Foreground package is $packageId.",
                                detail = condensedOutput(output),
                                packageId = packageId
                            )
                        } else {
                            TransportResult(
                                kind = TransportKind.Partial,
                                summary = "Foreground package could not be parsed.",
                                detail = condensedOutput(output)
                            )
                        }
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "Foreground package query failed.",
                        detail = exception.message ?: exception.javaClass.simpleName
                    )
                }
            }
        }
    }

    override suspend fun runUtility(utility: AdbUtility): TransportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val endpoint = activeEndpoint ?: return@withLock notConnectedResult()

            trackOperation {
                try {
                    val progressKind = if (utility == AdbUtility.ListInstalledPackages) {
                        TransportOperationKind.ListInstalledPackages
                    } else {
                        TransportOperationKind.UtilityCommand
                    }
                    emitOperationProgress(
                        kind = progressKind,
                        title = "Opening Quest utility session",
                        detail = "Connecting to ${endpoint.normalized} for ${utility.name}.",
                        stepIndex = 1,
                        stepCount = 3,
                        fraction = 0.18f,
                        utility = utility
                    )
                    withTcpConnection(endpoint) { connection ->
                        when (utility) {
                            AdbUtility.Home -> {
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Sending Home keyevent",
                                    detail = "Issuing input keyevent 3 on Quest.",
                                    stepIndex = 2,
                                    stepCount = 3,
                                    fraction = 0.56f,
                                    utility = utility
                                )
                                val output = runShell(connection, "input keyevent 3")
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Reading Home command response",
                                    detail = "Waiting for the Quest shell to return.",
                                    stepIndex = 3,
                                    stepCount = 3,
                                    fraction = 0.9f,
                                    utility = utility
                                )
                                TransportResult(
                                    kind = TransportKind.Success,
                                    summary = "Home command sent.",
                                    detail = condensedOutput(output)
                                )
                            }
                            AdbUtility.Back -> {
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Sending Back keyevent",
                                    detail = "Issuing input keyevent 4 on Quest.",
                                    stepIndex = 2,
                                    stepCount = 3,
                                    fraction = 0.56f,
                                    utility = utility
                                )
                                val output = runShell(connection, "input keyevent 4")
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Reading Back command response",
                                    detail = "Waiting for the Quest shell to return.",
                                    stepIndex = 3,
                                    stepCount = 3,
                                    fraction = 0.9f,
                                    utility = utility
                                )
                                TransportResult(
                                    kind = TransportKind.Success,
                                    summary = "Back command sent.",
                                    detail = condensedOutput(output)
                                )
                            }
                            AdbUtility.Wake -> {
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Sending Wake keyevent",
                                    detail = "Issuing input keyevent 224 on Quest.",
                                    stepIndex = 2,
                                    stepCount = 3,
                                    fraction = 0.56f,
                                    utility = utility
                                )
                                val output = runShell(connection, "input keyevent 224")
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Reading Wake command response",
                                    detail = "Waiting for the Quest shell to return.",
                                    stepIndex = 3,
                                    stepCount = 3,
                                    fraction = 0.9f,
                                    utility = utility
                                )
                                TransportResult(
                                    kind = TransportKind.Success,
                                    summary = "Wake command sent.",
                                    detail = condensedOutput(output)
                                )
                            }
                            AdbUtility.Reboot -> {
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Sending reboot service request",
                                    detail = "Issuing reboot: over ADB to Quest.",
                                    stepIndex = 2,
                                    stepCount = 3,
                                    fraction = 0.56f,
                                    utility = utility
                                )
                                runService(connection, "reboot:")
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Reboot request acknowledged",
                                    detail = "Quest reboot service request was sent over ADB.",
                                    stepIndex = 3,
                                    stepCount = 3,
                                    fraction = 0.9f,
                                    utility = utility
                                )
                                TransportResult(
                                    kind = TransportKind.Success,
                                    summary = "Reboot command sent.",
                                    detail = "Quest reboot service request was sent over ADB."
                                )
                            }
                            AdbUtility.ListInstalledPackages -> {
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Reading installed package list",
                                    detail = "Running pm list packages on Quest.",
                                    stepIndex = 2,
                                    stepCount = 3,
                                    fraction = 0.56f,
                                    utility = utility
                                )
                                val output = runShell(connection, "pm list packages")
                                emitOperationProgress(
                                    kind = progressKind,
                                    title = "Parsing installed package list",
                                    detail = "Extracting package ids from Quest package manager output.",
                                    stepIndex = 3,
                                    stepCount = 3,
                                    fraction = 0.9f,
                                    utility = utility
                                )
                                val packages = AdbShellSupport.parseInstalledPackages(output)
                                TransportResult(
                                    kind = TransportKind.Success,
                                    summary = "Listed ${packages.size} installed package(s).",
                                    detail = condensedOutput(output),
                                    items = packages
                                )
                            }
                        }
                    }
                } catch (exception: Exception) {
                    TransportResult(
                        kind = TransportKind.Error,
                        summary = "${utility.name} command failed.",
                        detail = exception.message ?: exception.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun amStartSucceeded(output: String): Boolean {
        return !output.contains("Error:", ignoreCase = true) &&
            !output.contains("Exception", ignoreCase = true)
    }

    private fun attemptTcpConnect(endpoint: AdbEndpoint): TransportResult {
        return try {
            withTcpConnection(endpoint) { connection ->
                val handshake = runShell(connection, "echo connected").trim()
                diagnosticsSession.record(
                    DiagnosticsStage.ShellEchoVerify,
                    "connected" in handshake,
                    if ("connected" in handshake) "Shell echo verification succeeded." else "Shell echo returned unexpected output.",
                    payload = condensedOutput(handshake)
                )
                val capabilityProbe = runAdbCapabilityProbe(connection)
                if ("connected" in handshake) {
                    TransportResult(
                        kind = TransportKind.Success,
                        summary = "Connected to Quest at ${endpoint.normalized}.",
                        detail = listOf("Phone-side ADB transport is live.", capabilityProbe)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                        endpoint = endpoint.normalized
                    )
                } else {
                    TransportResult(
                        kind = TransportKind.Success,
                        summary = "Connected to Quest at ${endpoint.normalized}.",
                        detail = listOf(condensedOutput(handshake), capabilityProbe)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                        endpoint = endpoint.normalized
                    )
                }
            }
        } catch (exception: Exception) {
            TransportResult(
                kind = TransportKind.Error,
                summary = "Failed to connect to ${endpoint.normalized}.",
                detail = exception.message ?: exception.javaClass.simpleName,
                endpoint = endpoint.normalized
            )
        }
    }

    private fun <T> withTcpConnection(endpoint: AdbEndpoint, block: (AdbConnection) -> T): T {
        var lastException: Exception? = null
        repeat(TCP_CONNECTION_ATTEMPTS) { attempt ->
            try {
                return withTcpConnectionOnce(endpoint, block)
            } catch (exception: Exception) {
                lastException = exception
                if (!shouldRetryTcpConnection(exception) || attempt == TCP_CONNECTION_ATTEMPTS - 1) {
                    throw exception
                }
                Thread.sleep(TCP_CONNECTION_RETRY_DELAY_MS)
            }
        }
        throw lastException ?: IOException("TCP ADB connection failed.")
    }

    private fun <T> withTcpConnectionOnce(endpoint: AdbEndpoint, block: (AdbConnection) -> T): T {
        val socket = Socket()
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TCP_CONNECT_TIMEOUT_MS)
        socket.soTimeout = TCP_READ_TIMEOUT_MS
        val connection = AdbConnection.create(TcpChannel(socket), adbCrypto)
        connection.setObserver(createAdbObserver())
        return connection.use { adb ->
            adb.connect()
            block(adb)
        }
    }

    private fun shouldRetryTcpConnection(exception: Exception): Boolean {
        val message = exception.message.orEmpty()
        return message.contains("failed to connect", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("connect timed out", ignoreCase = true) ||
            message.contains("timed out waiting for ADB handshake", ignoreCase = true) ||
            message.contains("Software caused connection abort", ignoreCase = true) ||
            message.contains("Stream open actively rejected", ignoreCase = true) ||
            message.contains("Stream open timed out", ignoreCase = true) ||
            message.contains("Stream read timed out", ignoreCase = true) ||
            exception is java.net.SocketTimeoutException
    }

    private fun <T> withUsbConnection(device: UsbDevice, adbInterface: UsbInterface, block: (AdbConnection) -> T): T {
        val deviceConnection = usbManager.openDevice(device)
            ?: throw IOException("Could not open Quest USB device.")
        if (!deviceConnection.claimInterface(adbInterface, false)) {
            deviceConnection.close()
            throw IOException("Could not claim the Quest ADB USB interface.")
        }

        val connection = AdbConnection.create(UsbChannel(deviceConnection, adbInterface), adbCrypto)
        connection.setObserver(createAdbObserver())
        return connection.use { adb ->
            adb.connect()
            block(adb)
        }
    }

    private suspend fun <T> withUsbCommandRetry(
        device: UsbDevice,
        adbInterface: UsbInterface,
        block: (AdbConnection) -> T
    ): T {
        var lastError: Exception? = null
        repeat(USB_ADB_RETRY_ATTEMPTS) { attempt ->
            try {
                return withUsbConnection(device, adbInterface, block)
            } catch (exception: Exception) {
                lastError = exception
                if (!shouldRetryUsbAuthorization(exception) || attempt == USB_ADB_RETRY_ATTEMPTS - 1) {
                    throw exception
                }
                delay(USB_ADB_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("Quest USB ADB bootstrap failed.")
    }

    private fun runShell(connection: AdbConnection, command: String): String {
        return runService(connection, "shell:$command")
    }

    private fun runService(connection: AdbConnection, destination: String): String {
        val stream = connection.open(destination, STREAM_OPEN_TIMEOUT_MS)
        return readStream(stream)
    }

    private fun readStream(stream: AdbStream, timeoutMs: Long = STREAM_READ_TIMEOUT_MS): String {
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                output.write(stream.read(timeoutMs))
            }
        } catch (exception: IOException) {
            val timedOut = exception.message?.contains("timed out", ignoreCase = true) == true
            if (timedOut && output.size() == 0) {
                throw exception
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { stream.close() }
        }
        return output.toString(StandardCharsets.UTF_8.name()).replace("\u0000", "").trim()
    }

    private fun installInputStream(
        connection: AdbConnection,
        source: InputStream,
        sizeBytes: Long,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        if (sizeBytes <= 0L) {
            throw IOException("Cannot install an empty APK.")
        }

        val stream = connection.open("shell:pm install -r -d -g -S $sizeBytes", STREAM_OPEN_TIMEOUT_MS)
        try {
            val chunkBuffer = ByteArray(connection.getMaxData().coerceAtMost(SYNC_CHUNK_LIMIT_BYTES))
            var totalSent = 0L
            var lastReportedBytes = -1L
            var lastReportTimeMs = 0L
            onProgress?.invoke(0L, sizeBytes)
            source.use { input ->
                while (true) {
                    val read = input.read(chunkBuffer)
                    if (read <= 0) {
                        break
                    }
                    stream.write(chunkBuffer.copyOf(read))
                    totalSent += read
                    val now = System.currentTimeMillis()
                    val shouldReport = totalSent == sizeBytes ||
                        totalSent - lastReportedBytes >= (256 * 1024) ||
                        now - lastReportTimeMs >= 200
                    if (shouldReport) {
                        onProgress?.invoke(totalSent, sizeBytes)
                        lastReportedBytes = totalSent
                        lastReportTimeMs = now
                    }
                }
            }

            if (totalSent != sizeBytes) {
                throw IOException("APK stream sent $totalSent bytes but expected $sizeBytes.")
            }
            return readStream(stream, INSTALL_READ_TIMEOUT_MS)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun pushInputStream(
        connection: AdbConnection,
        source: InputStream,
        sizeBytes: Long,
        remotePath: String,
        fileMode: Int = 33206,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        runShell(connection, "mkdir -p ${shellQuote(remotePath.substringBeforeLast('/'))}")

        val stream = connection.open("sync:")
        try {
            val destinationSpec = "$remotePath,$fileMode".toByteArray(StandardCharsets.UTF_8)
            stream.write(concatBytes("SEND".toByteArray(StandardCharsets.US_ASCII), intToByteArray(destinationSpec.size)))
            stream.write(destinationSpec)

            val chunkBuffer = ByteArray(connection.getMaxData().coerceAtMost(SYNC_CHUNK_LIMIT_BYTES))
            var totalSent = 0L
            var lastReportedBytes = -1L
            var lastReportTimeMs = 0L
            onProgress?.invoke(0L, sizeBytes)
            source.use { input ->
                while (true) {
                    val read = input.read(chunkBuffer)
                    if (read <= 0) {
                        break
                    }
                    stream.write(concatBytes("DATA".toByteArray(StandardCharsets.US_ASCII), intToByteArray(read)))
                    stream.write(chunkBuffer.copyOf(read))
                    totalSent += read
                    val now = System.currentTimeMillis()
                    val shouldReport = totalSent == sizeBytes ||
                        totalSent - lastReportedBytes >= (256 * 1024) ||
                        now - lastReportTimeMs >= 200
                    if (shouldReport) {
                        onProgress?.invoke(totalSent, sizeBytes)
                        lastReportedBytes = totalSent
                        lastReportTimeMs = now
                    }
                }
            }

            stream.write(
                concatBytes(
                    "DONE".toByteArray(StandardCharsets.US_ASCII),
                    intToByteArray((System.currentTimeMillis() / 1000L).toInt())
                )
            )

            val response = stream.read()
            val responseId = response.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII)
            if (responseId == "FAIL") {
                val messageLength = littleEndianToInt(response, 4)
                val messageBytes = response.copyOfRange(8, (8 + messageLength).coerceAtMost(response.size))
                throw IOException("Quest sync push failed: ${messageBytes.toString(StandardCharsets.UTF_8)}")
            }
            if (responseId != "OKAY") {
                throw IOException("Quest sync push returned unexpected reply: $responseId")
            }
            if (totalSent != sizeBytes) {
                throw IOException("Quest sync push sent $totalSent bytes but expected $sizeBytes.")
            }
            stream.write(concatBytes("QUIT".toByteArray(StandardCharsets.US_ASCII), intToByteArray(0)))
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun loadOrCreateCrypto(): AdbCrypto {
        val keyDir = File(appContext.filesDir, "adb-keys").apply { mkdirs() }
        val privateKey = File(keyDir, "private_key")
        val publicKey = File(keyDir, "public_key")
        return try {
            val crypto = if (privateKey.exists() && publicKey.exists()) {
                AdbCrypto.loadAdbKeyPair(LegacyAdbBase64(), privateKey, publicKey)
            } else {
                val created = AdbCrypto.generateAdbKeyPair(LegacyAdbBase64())
                created.saveAdbKeyPair(privateKey, publicKey)
                created
            }
            crypto.setAdbKeyLabel(resolveAdbKeyLabel())
            crypto
        } catch (exception: IOException) {
            throw IllegalStateException("Failed loading ADB key pair.", exception)
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("Failed creating ADB key pair.", exception)
        } catch (exception: InvalidKeySpecException) {
            throw IllegalStateException("Stored ADB key pair is invalid.", exception)
        }
    }

    private fun resolveAdbKeyLabel(): String {
        val rawDevice = Build.DEVICE.orEmpty().ifBlank { Build.MODEL.orEmpty() }
        val normalizedDevice = rawDevice
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "android" }
        return "quest-companion@$normalizedDevice"
    }

    private fun findUsbAdbDevice(devices: List<UsbDevice>): UsbDevice? {
        return devices.firstOrNull { findAdbInterface(it) != null }
    }

    private suspend fun waitForUsbDevices(): List<UsbDevice> {
        repeat(USB_DEVICE_DISCOVERY_ATTEMPTS) { attempt ->
            val devices = usbManager.deviceList.values.toList()
            if (devices.isNotEmpty()) {
                return devices
            }
            if (attempt < USB_DEVICE_DISCOVERY_ATTEMPTS - 1) {
                delay(USB_DEVICE_DISCOVERY_POLL_MS)
            }
        }
        return emptyList()
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                return intf
            }
        }
        return null
    }

    private suspend fun ensureUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) {
            return true
        }

        val broadcastResult = AtomicReference<Boolean?>(null)
        val permissionIntent = Intent(usbPermissionAction).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            device.deviceId,
            permissionIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != usbPermissionAction) {
                    return
                }
                val grantedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                }
                if (grantedDevice?.deviceId != device.deviceId) {
                    return
                }

                broadcastResult.set(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(usbPermissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return try {
            usbManager.requestPermission(device, pendingIntent)
            val granted = withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) {
                while (true) {
                    if (usbManager.hasPermission(device)) {
                        return@withTimeoutOrNull true
                    }
                    when (broadcastResult.get()) {
                        true -> return@withTimeoutOrNull true
                        false -> return@withTimeoutOrNull false
                        null -> Unit
                    }
                    delay(USB_PERMISSION_POLL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            granted ?: usbManager.hasPermission(device)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun parseEndpoint(raw: String): AdbEndpoint? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val splitIndex = trimmed.lastIndexOf(':')
        return if (splitIndex <= 0 || splitIndex == trimmed.lastIndex) {
            AdbEndpoint(trimmed, DEFAULT_ADB_PORT)
        } else {
            val host = trimmed.substring(0, splitIndex).trim()
            val port = trimmed.substring(splitIndex + 1).trim().toIntOrNull() ?: return null
            if (host.isEmpty()) null else AdbEndpoint(host, port)
        }
    }

    private fun parseEndpointFromNetworkState(routeOutput: String, addrOutput: String): AdbEndpoint? {
        return parseEndpointFromRoute(routeOutput) ?: parseEndpointFromIpAddr(addrOutput)
    }

    private fun parseEndpointFromRoute(routeOutput: String): AdbEndpoint? {
        val regex = Regex("""src\s+((10|172\.(1[6-9]|2\d|3[0-1])|192\.168)\.\d+\.\d+\.\d+)""")
        val match = regex.find(routeOutput) ?: Regex("""src\s+(\d+\.\d+\.\d+\.\d+)""").find(routeOutput)
        return match?.groupValues?.getOrNull(1)?.let { AdbEndpoint(it, DEFAULT_ADB_PORT) }
    }

    private fun parseEndpointFromIpAddr(addrOutput: String): AdbEndpoint? {
        val match = Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)/""").find(addrOutput)
        return match?.groupValues?.getOrNull(1)?.let { AdbEndpoint(it, DEFAULT_ADB_PORT) }
    }

    private fun observeEndpointNetwork(endpoint: AdbEndpoint): EndpointNetworkObservation {
        if (parseIpv4(endpoint.host) == null) {
            return EndpointNetworkObservation(
                summary = "Quest endpoint is not an IPv4 address.",
                detail = "Same-network checks currently compare only IPv4 endpoints like 192.168.43.17:5555."
            )
        }

        val localIpv4Interfaces = localIpv4Interfaces()
        if (localIpv4Interfaces.isEmpty()) {
            return EndpointNetworkObservation(
                summary = "Phone has no active local IPv4 subnet for Quest access.",
                detail = "No non-loopback IPv4 interfaces are up on the phone right now."
            )
        }

        val matches = localIpv4Interfaces.filter { isSameSubnet(it.address, endpoint.host, it.prefixLength) }
        val interfaceList = localIpv4Interfaces.joinToString("; ") { it.label }

        return if (matches.isNotEmpty()) {
            EndpointNetworkObservation(
                summary = "Quest endpoint matches a local phone IPv4 subnet.",
                detail = "Quest ${endpoint.normalized} matches ${matches.joinToString { it.label }}. Phone interfaces: $interfaceList.",
                sameSubnet = true
            )
        } else {
            EndpointNetworkObservation(
                summary = "Quest endpoint is outside the phone's local IPv4 subnets.",
                detail = "Quest ${endpoint.normalized} does not match any phone IPv4 subnet. Phone interfaces: $interfaceList.",
                sameSubnet = false
            )
        }
    }

    private fun bootstrapPartialSummary(
        endpoint: AdbEndpoint,
        observation: EndpointNetworkObservation
    ): String {
        return when (observation.sameSubnet) {
            true -> "Enabled Wi-Fi ADB on Quest, but the phone has not connected to ${endpoint.normalized} yet."
            false -> "Enabled Wi-Fi ADB on Quest, but the phone is not on the same IPv4 network as ${endpoint.normalized}."
            null -> "Enabled Wi-Fi ADB on Quest, but the phone cannot verify network reachability to ${endpoint.normalized} yet."
        }
    }

    private fun notConnectedResult(): TransportResult {
        return TransportResult(
            kind = TransportKind.Error,
            summary = "Quest is not connected.",
            detail = "Enable Wi-Fi ADB from USB after reboot, or connect the saved hotspot endpoint first."
        )
    }

    private fun usbBootstrapErrorDetail(exception: Exception): String {
        val message = exception.message ?: exception.javaClass.simpleName
        return when {
            message.contains("timed out waiting for ADB handshake", ignoreCase = true) ->
                "The phone got USB access, but the Quest never completed USB debugging authorization after reconnect retries. If you already accepted the prompt, keep the headset awake for a few more seconds and retry once."
            message.contains("Stream open timed out", ignoreCase = true) || message.contains("Stream read timed out", ignoreCase = true) ->
                "The phone opened the Quest USB device, but the Quest never exposed an ADB shell. File transfer or charging alone is not enough; the headset still needs to authorize USB debugging for this phone app."
            else -> message
        }
    }

    private fun usbTcpipErrorDetail(exception: Exception): String {
        val message = exception.message ?: exception.javaClass.simpleName
        return when {
            message.contains("timed out waiting for ADB handshake", ignoreCase = true) ->
                "The Quest exposed USB long enough to resolve its route, but it did not finish the USB debugging handshake needed to switch into tcpip mode."
            message.contains("Stream open timed out", ignoreCase = true) || message.contains("Stream read timed out", ignoreCase = true) ->
                "The Quest opened over USB, but the tcpip service command never completed. The headset may still be in file-transfer mode instead of an authorized USB debugging session."
            else -> message
        }
    }

    private fun shouldRetryUsbAuthorization(exception: Exception): Boolean {
        val message = exception.message ?: return false
        return message.contains("timed out waiting for ADB handshake", ignoreCase = true) ||
            message.contains("Stream open timed out", ignoreCase = true) ||
            message.contains("Stream read timed out", ignoreCase = true) ||
            message.contains("actively rejected by remote peer", ignoreCase = true)
    }

    private fun classifyBootstrapException(exception: Exception): FailureClass {
        val message = exception.message ?: return FailureClass.UnexpectedError
        return when {
            message.contains("timed out waiting for ADB handshake", ignoreCase = true) -> FailureClass.AdbAuthTimeout
            message.contains("Stream open timed out", ignoreCase = true) -> FailureClass.ShellStreamTimeout
            message.contains("Stream read timed out", ignoreCase = true) -> FailureClass.ShellStreamTimeout
            message.contains("actively rejected by remote peer", ignoreCase = true) -> FailureClass.ShellStreamRejected
            message.contains("Connection refused", ignoreCase = true) -> FailureClass.TcpConnectRefused
            message.contains("connect timed out", ignoreCase = true) -> FailureClass.TcpConnectTimeout
            else -> FailureClass.UnexpectedError
        }
    }

    private fun finishDiagnostics(
        failure: FailureClass = FailureClass.None,
        preferForExport: Boolean
    ) {
        diagnosticsSession.finish(failure)
        val snapshot = diagnosticsSession.snapshot()
        lastCompletedDiagnostics = snapshot
        if (preferForExport || lastAttemptDiagnostics == null) {
            lastAttemptDiagnostics = snapshot
        }
    }

    private fun runAdbCapabilityProbe(connection: AdbConnection): String {
        val probeValue = "rustyxrcompanion_${System.currentTimeMillis()}"
        val probePath = "/data/local/tmp/quest_companion_probe.txt"
        return try {
            runShell(connection, "rm -f ${shellQuote(probePath)}")
            runShell(connection, "printf %s ${shellQuote(probeValue)} > ${shellQuote(probePath)}")
            val verifiedValue = runShell(connection, "cat ${shellQuote(probePath)}").trim()
            if (verifiedValue != probeValue) {
                diagnosticsSession.record(
                    DiagnosticsStage.AdbCapabilityProbe,
                    false,
                    "ADB capability probe file verification failed.",
                    "Expected $probeValue but Quest reported $verifiedValue from $probePath."
                )
                return "ADB capability probe could not verify temp-file write access in $probePath."
            }

            runShell(connection, "rm -f ${shellQuote(probePath)}")
            val existsAfterDelete = runShell(connection, "if [ -e ${shellQuote(probePath)} ]; then echo exists; fi").trim()
            if (existsAfterDelete.isNotEmpty()) {
                diagnosticsSession.record(
                    DiagnosticsStage.AdbCapabilityProbe,
                    false,
                    "ADB capability probe cleanup failed.",
                    "Probe file still exists at $probePath after delete."
                )
                return "ADB capability probe verified write access but could not remove $probePath."
            }

            diagnosticsSession.record(
                DiagnosticsStage.AdbCapabilityProbe,
                true,
                "ADB capability probe succeeded and was reverted.",
                "Wrote, verified, and removed $probePath over USB ADB."
            )
            "ADB capability probe verified reversible temp-file write access."
        } catch (exception: Exception) {
            runCatching { runShell(connection, "rm -f ${shellQuote(probePath)}") }
            diagnosticsSession.record(
                DiagnosticsStage.AdbCapabilityProbe,
                false,
                "ADB capability probe failed.",
                exception.message ?: exception.javaClass.simpleName
            )
            "ADB capability probe failed: ${exception.message ?: exception.javaClass.simpleName}."
        }
    }

    private fun extractBatterySummary(batteryDump: String): String? {
        val level = Regex("""(?m)^\s*level:\s*(\d+)\s*$""").find(batteryDump)?.groupValues?.getOrNull(1)
        val status = Regex("""(?m)^\s*status:\s*([^\r\n]+)\s*$""").find(batteryDump)?.groupValues?.getOrNull(1)?.trim()
        return when {
            level != null && status != null -> "$level% ($status)"
            level != null -> "$level%"
            status != null -> status
            else -> null
        }
    }

    private fun readQuestModel(connection: AdbConnection): String {
        val candidates = listOf(
            "ro.product.system.model",
            "ro.product.vendor.model",
            "ro.product.model",
            "ro.product.name",
            "ro.build.product"
        )
        for (property in candidates) {
            val value = runCatching { runShell(connection, "getprop ${shellQuote(property)}").trim() }.getOrDefault("")
            if (value.isNotBlank()) {
                return value
            }
        }
        return "unknown"
    }

    private fun condensedOutput(output: String): String {
        val normalized = output.replace("\r", "").trim()
        return if (normalized.length <= 320) normalized else normalized.take(317) + "..."
    }

    private fun buildUsbInventoryDetail(devices: List<UsbDevice>): String {
        return devices.joinToString(" | ") { device ->
            val label = buildList {
                add("device ${device.deviceName}")
                add("vid=0x${device.vendorId.toString(16)}")
                add("pid=0x${device.productId.toString(16)}")
                add("permission=${usbManager.hasPermission(device)}")
                add("adbInterface=${findAdbInterface(device) != null}")
                device.manufacturerName?.takeIf { it.isNotBlank() }?.let { add("maker=$it") }
                device.productName?.takeIf { it.isNotBlank() }?.let { add("product=$it") }
                add("interfaces=${describeUsbInterfaces(device)}")
            }
            label.joinToString(" ")
        }
    }

    private fun describeUsbInterfaces(device: UsbDevice): String {
        if (device.interfaceCount <= 0) {
            return "none"
        }

        return buildList {
            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)
                add(
                    "#$index(class=${intf.interfaceClass},sub=${intf.interfaceSubclass},proto=${intf.interfaceProtocol})"
                )
            }
        }.joinToString(",")
    }

    private fun findStagedApk(apkFile: String): File? {
        return stagingCandidates(apkFile).firstOrNull(File::exists)
    }

    private fun stagingCandidates(apkFile: String): List<File> {
        val internal = File(appContext.filesDir, "apks/$apkFile")
        val externalRoot = appContext.getExternalFilesDir(null)
        val external = externalRoot?.let { File(it, "apks/$apkFile") }
        return listOfNotNull(external, internal)
    }

    private fun describeStagingPaths(apkFile: String): String {
        return stagingCandidates(apkFile).joinToString(" or ") { it.absolutePath }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun littleEndianToInt(buffer: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun concatBytes(vararg arrays: ByteArray): ByteArray {
        val totalLength = arrays.sumOf { it.size }
        val result = ByteArray(totalLength)
        var currentIndex = 0
        arrays.forEach { bytes ->
            System.arraycopy(bytes, 0, result, currentIndex, bytes.size)
            currentIndex += bytes.size
        }
        return result
    }

    private fun localIpv4Interfaces(): List<LocalIpv4Interface> {
        return runCatching {
            val networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            networkInterfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                        val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                        if (address.isLoopbackAddress || address.isLinkLocalAddress) {
                            return@mapNotNull null
                        }
                        val prefixLength = interfaceAddress.networkPrefixLength.toInt()
                        if (prefixLength !in 1..32) {
                            return@mapNotNull null
                        }
                        LocalIpv4Interface(
                            name = networkInterface.name,
                            address = address.hostAddress ?: return@mapNotNull null,
                            prefixLength = prefixLength
                        )
                    }
                }
        }.getOrDefault(emptyList())
    }

    private fun isSameSubnet(localAddress: String, remoteAddress: String, prefixLength: Int): Boolean {
        val local = parseIpv4(localAddress) ?: return false
        val remote = parseIpv4(remoteAddress) ?: return false
        val hostBits = 32 - prefixLength
        val mask = if (prefixLength == 0) {
            0
        } else {
            (-1 shl hostBits)
        }
        return (local and mask) == (remote and mask)
    }

    private fun parseIpv4(address: String): Int? {
        val parts = address.split('.')
        if (parts.size != 4) {
            return null
        }

        var result = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) {
                return null
            }
            result = (result shl 8) or octet
        }
        return result
    }

    private data class LocalIpv4Interface(
        val name: String,
        val address: String,
        val prefixLength: Int
    ) {
        val label: String
            get() = "$name $address/$prefixLength"
    }

    private data class EndpointNetworkObservation(
        val summary: String,
        val detail: String,
        val sameSubnet: Boolean? = null
    )

    private data class AdbEndpoint(
        val host: String,
        val port: Int
    ) {
        val normalized: String
            get() = "$host:$port"
    }

    private companion object {
        const val DIAGNOSTIC_PROBE_PROP = "debug.rustyxrcompanion.phone_diag_probe"
        const val DEFAULT_ADB_PORT = 5555
        const val TCP_CONNECTION_ATTEMPTS = 3
        const val TCP_CONNECTION_RETRY_DELAY_MS = 1_200L
        const val TCP_CONNECT_TIMEOUT_MS = 3000
        const val TCP_READ_TIMEOUT_MS = 5000
        const val STREAM_OPEN_TIMEOUT_MS = 10_000L
        const val STREAM_READ_TIMEOUT_MS = 10_000L
        const val INSTALL_READ_TIMEOUT_MS = 120_000L
        const val USB_PERMISSION_TIMEOUT_MS = 12_000L
        const val USB_PERMISSION_POLL_MS = 200L
        const val USB_DEVICE_DISCOVERY_ATTEMPTS = 10
        const val USB_DEVICE_DISCOVERY_POLL_MS = 250L
        const val USB_ADB_RETRY_ATTEMPTS = 3
        const val USB_ADB_RETRY_DELAY_MS = 1_500L
        const val SYNC_CHUNK_LIMIT_BYTES = 32 * 1024
    }

    private fun transferFraction(sentBytes: Long, totalBytes: Long): Float {
        if (totalBytes <= 0L) {
            return 0f
        }
        return (sentBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

