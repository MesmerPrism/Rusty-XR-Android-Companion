package io.github.mesmerprism.rustyxr.companion.android.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

class AndroidPolarPmdSmokeMonitor(
    appContext: Context
) {
    private val applicationContext = appContext.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun run(
        timeoutMs: Long,
        deviceAddress: String? = null
    ): PolarPmdSmokeResult = withContext(Dispatchers.IO) {
        val startedAt = Instant.now().toString()
        val notes = mutableListOf<String>()
        var gatt: BluetoothGatt? = null
        var callback: PmdGattCallback? = null
        var candidate: PolarDeviceCandidate? = null
        var batteryPercent: Int? = null
        var negotiatedMtu: Int? = null
        val controlResponses = mutableListOf<PolarPmdControlSummary>()
        val settings = mutableListOf<PolarPmdSettingsSummary>()
        var latestEcg: PolarPmdEcgFrameSummary? = null
        var latestAcc: PolarPmdAccFrameSummary? = null
        var ecgFrameCount = 0
        var ecgSampleCount = 0
        var accFrameCount = 0
        var accSampleCount = 0

        fun failed(summary: String, detail: String): PolarPmdSmokeResult =
            PolarPmdSmokeResult(
                passed = false,
                summary = summary,
                detail = detail,
                startedAtUtc = startedAt,
                endedAtUtc = Instant.now().toString(),
                deviceName = candidate?.deviceName,
                deviceAddress = candidate?.deviceAddress,
                rssi = candidate?.rssi,
                batteryPercent = batteryPercent,
                negotiatedMtu = negotiatedMtu,
                heartRateServiceVisible = candidate?.heartRateServiceVisible == true,
                pmdServiceVisible = candidate?.pmdServiceVisible == true,
                ecgFrameCount = ecgFrameCount,
                ecgSampleCount = ecgSampleCount,
                latestEcgFrame = latestEcg,
                accFrameCount = accFrameCount,
                accSampleCount = accSampleCount,
                latestAccFrame = latestAcc,
                controlResponses = controlResponses.toList(),
                settings = settings.toList(),
                notes = notes.toList()
            )

        val missingPermissions = missingBluetoothPermissions()
        if (missingPermissions.isNotEmpty()) {
            return@withContext failed(
                summary = "Polar PMD smoke blocked by missing Bluetooth permission.",
                detail = "Missing ${missingPermissions.joinToString(", ")}."
            )
        }

        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return@withContext failed(
                summary = "BLE is not available on this phone.",
                detail = "Polar PMD ECG/ACC smoke requires Android Bluetooth Low Energy support."
            )
        }

        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@withContext failed(
                summary = "Bluetooth adapter unavailable.",
                detail = "Android did not expose a Bluetooth adapter to the app."
            )
        if (!adapter.isEnabled) {
            return@withContext failed(
                summary = "Bluetooth is disabled.",
                detail = "Enable phone Bluetooth before running the Polar PMD smoke command."
            )
        }

        try {
            candidate = resolveCandidate(adapter, timeoutMs, deviceAddress)
            val resolvedCandidate = candidate
                ?: return@withContext failed(
                    summary = "No Polar sensor found.",
                    detail = "BLE scan timed out before a Polar-compatible advertisement was seen."
                )

            var connectStatus: Int? = null
            for (attempt in 1..ConnectAttemptCount) {
                if (attempt > 1) {
                    delay(ConnectRetryDelayMs)
                    notes += "Retrying Polar GATT connection after previous status ${connectStatus ?: "unknown"}."
                }

                val attemptCallback = PmdGattCallback()
                val attemptGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    resolvedCandidate.device.connectGatt(
                        applicationContext,
                        false,
                        attemptCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    resolvedCandidate.device.connectGatt(applicationContext, false, attemptCallback)
                }
                if (attemptGatt == null) {
                    connectStatus = GattStartFailed
                    attemptCallback.close()
                    continue
                }

                connectStatus = withTimeoutOrNull(ConnectTimeoutMs) {
                    attemptCallback.connected.await()
                } ?: GattConnectionTimeout
                if (connectStatus == BluetoothGatt.GATT_SUCCESS) {
                    callback = attemptCallback
                    gatt = attemptGatt
                    break
                }

                runCatching { attemptGatt.disconnect() }
                runCatching { attemptGatt.close() }
                attemptCallback.close()
            }

            val resolvedCallback = callback
            val resolvedGatt = gatt
            if (resolvedCallback == null || resolvedGatt == null || connectStatus != BluetoothGatt.GATT_SUCCESS) {
                return@withContext failed(
                    summary = "Polar GATT connection failed.",
                    detail = "Bluetooth GATT status ${connectStatus ?: "unknown"} while connecting to ${resolvedCandidate.displayLabel}."
                )
            }

            negotiatedMtu = requestMtu(resolvedGatt, resolvedCallback, notes)
            val serviceStatus = discoverServices(resolvedGatt, resolvedCallback)
            if (serviceStatus != BluetoothGatt.GATT_SUCCESS) {
                return@withContext failed(
                    summary = "Polar service discovery failed.",
                    detail = "Bluetooth GATT status $serviceStatus while discovering services."
                )
            }

            val hasHeartRate = resolvedGatt.getService(PolarPmdProtocol.HeartRateService) != null
            val pmdService = resolvedGatt.getService(PolarPmdProtocol.PmdService)
            candidate = resolvedCandidate.copy(
                heartRateServiceVisible = hasHeartRate,
                pmdServiceVisible = pmdService != null
            )
            if (pmdService == null) {
                return@withContext failed(
                    summary = "Polar PMD service unavailable.",
                    detail = "The connected BLE device did not expose the Polar PMD service."
                )
            }

            batteryPercent = readBatteryPercent(resolvedGatt, resolvedCallback, notes)

            val controlPoint = pmdService.getCharacteristic(PolarPmdProtocol.PmdControlPoint)
                ?: return@withContext failed(
                    summary = "Polar PMD control point unavailable.",
                    detail = "The PMD service did not expose the control point characteristic."
                )
            val data = pmdService.getCharacteristic(PolarPmdProtocol.PmdData)
                ?: return@withContext failed(
                    summary = "Polar PMD data characteristic unavailable.",
                    detail = "The PMD service did not expose the PMD data notification characteristic."
                )

            enableCharacteristicUpdates(
                gatt = resolvedGatt,
                callback = resolvedCallback,
                characteristic = controlPoint,
                cccdValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            )
            enableCharacteristicUpdates(
                gatt = resolvedGatt,
                callback = resolvedCallback,
                characteristic = data,
                cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )

            controlResponses += sendPmdCommand(
                gatt = resolvedGatt,
                callback = resolvedCallback,
                controlPoint = controlPoint,
                command = PolarPmdProtocol.buildGetSettingsRequest(PolarPmdProtocol.MeasurementTypeEcg),
                expectedOpCode = PolarPmdProtocol.OpcodeGetSettings,
                expectedMeasurementType = PolarPmdProtocol.MeasurementTypeEcg
            ).also { response ->
                PolarPmdProtocol.parseSettingsResponse(response.rawBytes)?.let(settings::add)
            }.summary

            controlResponses += sendPmdCommand(
                gatt = resolvedGatt,
                callback = resolvedCallback,
                controlPoint = controlPoint,
                command = PolarPmdProtocol.buildGetSettingsRequest(PolarPmdProtocol.MeasurementTypeAcc),
                expectedOpCode = PolarPmdProtocol.OpcodeGetSettings,
                expectedMeasurementType = PolarPmdProtocol.MeasurementTypeAcc
            ).also { response ->
                PolarPmdProtocol.parseSettingsResponse(response.rawBytes)?.let(settings::add)
            }.summary

            controlResponses += sendPmdCommand(
                gatt = resolvedGatt,
                callback = resolvedCallback,
                controlPoint = controlPoint,
                command = PolarPmdProtocol.buildStartEcgRequest(),
                expectedOpCode = PolarPmdProtocol.OpcodeStartStream,
                expectedMeasurementType = PolarPmdProtocol.MeasurementTypeEcg
            ).summary

            controlResponses += sendPmdCommand(
                gatt = resolvedGatt,
                callback = resolvedCallback,
                controlPoint = controlPoint,
                command = PolarPmdProtocol.buildStartAccRequest(),
                expectedOpCode = PolarPmdProtocol.OpcodeStartStream,
                expectedMeasurementType = PolarPmdProtocol.MeasurementTypeAcc
            ).summary

            val streamDeadlineMs = System.currentTimeMillis() + StreamCollectTimeoutMs
            while ((ecgFrameCount == 0 || accFrameCount == 0) && System.currentTimeMillis() < streamDeadlineMs) {
                val remaining = (streamDeadlineMs - System.currentTimeMillis()).coerceAtLeast(1)
                val frame = withTimeoutOrNull(remaining) { resolvedCallback.dataNotifications.receive() } ?: break
                when (frame.firstOrNull()) {
                    PolarPmdProtocol.MeasurementTypeEcg -> {
                        val decoded = PolarPmdProtocol.decodeEcgFrame(frame)
                        if (decoded != null) {
                            latestEcg = decoded
                            ecgFrameCount += 1
                            ecgSampleCount += decoded.sampleCount
                        } else {
                            notes += "Ignored malformed ECG PMD frame length=${frame.size}."
                        }
                    }
                    PolarPmdProtocol.MeasurementTypeAcc -> {
                        val decoded = PolarPmdProtocol.decodeAccFrame(frame)
                        if (decoded != null) {
                            latestAcc = decoded
                            accFrameCount += 1
                            accSampleCount += decoded.sampleCount
                        } else {
                            notes += "Ignored malformed ACC PMD frame length=${frame.size}."
                        }
                    }
                }
            }

            stopStream(resolvedGatt, resolvedCallback, controlPoint, PolarPmdProtocol.MeasurementTypeEcg, controlResponses, notes)
            stopStream(resolvedGatt, resolvedCallback, controlPoint, PolarPmdProtocol.MeasurementTypeAcc, controlResponses, notes)

            val passed = ecgFrameCount > 0 && accFrameCount > 0
            PolarPmdSmokeResult(
                passed = passed,
                summary = if (passed) {
                    "Polar PMD ECG and ACC frames received."
                } else {
                    "Polar PMD smoke did not receive both ECG and ACC frames."
                },
                detail = "ECG frames=$ecgFrameCount samples=$ecgSampleCount; ACC frames=$accFrameCount samples=$accSampleCount.",
                startedAtUtc = startedAt,
                endedAtUtc = Instant.now().toString(),
                deviceName = resolvedCandidate.deviceName,
                deviceAddress = resolvedCandidate.deviceAddress,
                rssi = resolvedCandidate.rssi,
                batteryPercent = batteryPercent,
                negotiatedMtu = negotiatedMtu,
                heartRateServiceVisible = hasHeartRate,
                pmdServiceVisible = true,
                ecgFrameCount = ecgFrameCount,
                ecgSampleCount = ecgSampleCount,
                latestEcgFrame = latestEcg,
                accFrameCount = accFrameCount,
                accSampleCount = accSampleCount,
                latestAccFrame = latestAcc,
                controlResponses = controlResponses.toList(),
                settings = settings.toList(),
                notes = notes.toList()
            )
        } catch (throwable: Throwable) {
            failed(
                summary = "Polar PMD smoke failed.",
                detail = throwable.message ?: throwable.javaClass.simpleName
            )
        } finally {
            runCatching { gatt?.disconnect() }
            delay(100)
            runCatching { gatt?.close() }
            callback?.close()
        }
    }

    private fun missingBluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter { applicationContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).filter { applicationContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveCandidate(
        adapter: android.bluetooth.BluetoothAdapter,
        timeoutMs: Long,
        deviceAddress: String?
    ): PolarDeviceCandidate? {
        if (!deviceAddress.isNullOrBlank()) {
            return runCatching {
                val device = adapter.getRemoteDevice(deviceAddress.trim())
                PolarDeviceCandidate(
                    device = device,
                    deviceName = device.name ?: "Polar sensor",
                    deviceAddress = device.address ?: deviceAddress.trim(),
                    rssi = null,
                    heartRateServiceVisible = false,
                    pmdServiceVisible = false
                )
            }.getOrNull()
        }

        val scanner = adapter.bluetoothLeScanner ?: return null
        return withTimeoutOrNull(timeoutMs.coerceIn(MinScanTimeoutMs, MaxScanTimeoutMs)) {
            suspendCancellableCoroutine { continuation ->
                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val candidate = result.toPolarCandidate() ?: return
                        scanner.stopScan(this)
                        continuation.resume(candidate)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
                scanner.startScan(
                    emptyList(),
                    ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build(),
                    scanCallback
                )
                continuation.invokeOnCancellation {
                    runCatching { scanner.stopScan(scanCallback) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestMtu(
        gatt: BluetoothGatt,
        callback: PmdGattCallback,
        notes: MutableList<String>
    ): Int? {
        callback.mtu = CompletableDeferred()
        return if (gatt.requestMtu(PreferredMtu)) {
            withTimeoutOrNull(MtuTimeoutMs) { callback.mtu.await() }
                .also { mtu ->
                    if (mtu == null) {
                        notes += "MTU negotiation timed out; continuing with Android's current MTU."
                    }
                }
        } else {
            notes += "Android refused to start MTU negotiation."
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverServices(gatt: BluetoothGatt, callback: PmdGattCallback): Int {
        callback.servicesDiscovered = CompletableDeferred()
        if (!gatt.discoverServices()) {
            return GattStartFailed
        }
        return withTimeout(ServiceDiscoveryTimeoutMs) { callback.servicesDiscovered.await() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBatteryPercent(
        gatt: BluetoothGatt,
        callback: PmdGattCallback,
        notes: MutableList<String>
    ): Int? {
        val characteristic = gatt.getService(PolarPmdProtocol.BatteryService)
            ?.getCharacteristic(PolarPmdProtocol.BatteryLevel)
            ?: return null
        callback.readResult = CompletableDeferred()
        if (!gatt.readCharacteristic(characteristic)) {
            notes += "Battery characteristic read did not start."
            return null
        }
        val read = withTimeoutOrNull(GattOperationTimeoutMs) { callback.readResult.await() }
        if (read == null || read.status != BluetoothGatt.GATT_SUCCESS || read.value.isEmpty()) {
            notes += "Battery characteristic read failed."
            return null
        }
        return read.value.first().toInt() and 0xFF
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableCharacteristicUpdates(
        gatt: BluetoothGatt,
        callback: PmdGattCallback,
        characteristic: BluetoothGattCharacteristic,
        cccdValue: ByteArray
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            error("Android refused to enable characteristic updates for ${characteristic.uuid}.")
        }
        val descriptor = characteristic.getDescriptor(PolarPmdProtocol.CccdDescriptor)
            ?: error("CCCD descriptor missing for ${characteristic.uuid}.")
        callback.descriptorWrite = CompletableDeferred()
        if (!gatt.writeDescriptorCompat(descriptor, cccdValue)) {
            error("CCCD descriptor write did not start for ${characteristic.uuid}.")
        }
        val status = withTimeout(GattOperationTimeoutMs) { callback.descriptorWrite.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            error("CCCD descriptor write failed for ${characteristic.uuid}: GATT status $status.")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPmdCommand(
        gatt: BluetoothGatt,
        callback: PmdGattCallback,
        controlPoint: BluetoothGattCharacteristic,
        command: ByteArray,
        expectedOpCode: Byte,
        expectedMeasurementType: Byte
    ): PmdResponseWithBytes {
        callback.controlNotifications.drain()
        callback.characteristicWrite = CompletableDeferred()
        if (!gatt.writeCharacteristicCompat(controlPoint, command)) {
            error("PMD command write did not start.")
        }
        val writeStatus = withTimeout(GattOperationTimeoutMs) { callback.characteristicWrite.await() }
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
            error("PMD command write failed: GATT status $writeStatus.")
        }

        val responseBytes = awaitControlResponse(
            channel = callback.controlNotifications,
            expectedOpCode = expectedOpCode,
            expectedMeasurementType = expectedMeasurementType
        )
        val summary = PolarPmdProtocol.parseControlResponse(responseBytes)
            ?: error("PMD control response was malformed.")
        if (!summary.success) {
            error(
                "PMD control response failed for op=${summary.opCode} measurement=${summary.measurementType}: error=${summary.errorCode}."
            )
        }
        return PmdResponseWithBytes(summary, responseBytes)
    }

    private suspend fun stopStream(
        gatt: BluetoothGatt,
        callback: PmdGattCallback,
        controlPoint: BluetoothGattCharacteristic,
        measurementType: Byte,
        controlResponses: MutableList<PolarPmdControlSummary>,
        notes: MutableList<String>
    ) {
        runCatching {
            sendPmdCommand(
                gatt = gatt,
                callback = callback,
                controlPoint = controlPoint,
                command = PolarPmdProtocol.buildStopRequest(measurementType),
                expectedOpCode = PolarPmdProtocol.OpcodeStopStream,
                expectedMeasurementType = measurementType
            )
        }.onSuccess { response ->
            controlResponses += response.summary
        }.onFailure { throwable ->
            notes += "Stop command for measurement ${measurementType.toInt() and 0xFF} failed: ${throwable.message.orEmpty()}"
        }
    }

    private suspend fun awaitControlResponse(
        channel: ReceiveChannel<ByteArray>,
        expectedOpCode: Byte,
        expectedMeasurementType: Byte
    ): ByteArray {
        val expectedOp = expectedOpCode.toInt() and 0xFF
        val expectedType = expectedMeasurementType.toInt() and 0xFF
        return withTimeout(ControlResponseTimeoutMs) {
            while (true) {
                val bytes = channel.receive()
                val parsed = PolarPmdProtocol.parseControlResponse(bytes) ?: continue
                if (parsed.opCode == expectedOp && parsed.measurementType == expectedType) {
                    return@withTimeout bytes
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ByteArray(0)
        }
    }

    private companion object {
        private const val PreferredMtu = 232
        private const val MinScanTimeoutMs = 3_000L
        private const val MaxScanTimeoutMs = 90_000L
        private const val ConnectTimeoutMs = 15_000L
        private const val ConnectAttemptCount = 3
        private const val ConnectRetryDelayMs = 1_500L
        private const val ServiceDiscoveryTimeoutMs = 12_000L
        private const val MtuTimeoutMs = 5_000L
        private const val GattOperationTimeoutMs = 6_000L
        private const val ControlResponseTimeoutMs = 8_000L
        private const val StreamCollectTimeoutMs = 15_000L
        private const val GattStartFailed = -1
        private const val GattConnectionTimeout = -2
    }
}

private data class PolarDeviceCandidate(
    val device: BluetoothDevice,
    val deviceName: String?,
    val deviceAddress: String,
    val rssi: Int?,
    val heartRateServiceVisible: Boolean,
    val pmdServiceVisible: Boolean
) {
    val displayLabel: String = listOfNotNull(deviceName, deviceAddress).joinToString(" ")
}

private data class CharacteristicReadResult(
    val uuid: java.util.UUID,
    val value: ByteArray,
    val status: Int
)

private data class PmdResponseWithBytes(
    val summary: PolarPmdControlSummary,
    val rawBytes: ByteArray
)

private class PmdGattCallback : BluetoothGattCallback() {
    var connected = CompletableDeferred<Int>()
    var servicesDiscovered = CompletableDeferred<Int>()
    var mtu = CompletableDeferred<Int>()
    var descriptorWrite = CompletableDeferred<Int>()
    var characteristicWrite = CompletableDeferred<Int>()
    var readResult = CompletableDeferred<CharacteristicReadResult>()
    val controlNotifications = Channel<ByteArray>(Channel.UNLIMITED)
    val dataNotifications = Channel<ByteArray>(Channel.UNLIMITED)

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            connected.complete(status)
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        this.mtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 0)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        servicesDiscovered.complete(status)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        descriptorWrite.complete(status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        characteristicWrite.complete(status)
    }

    @Deprecated("Deprecated in Android 13; retained for API 29-32 callbacks.")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        readResult.complete(
            CharacteristicReadResult(
                uuid = characteristic.uuid,
                value = characteristic.value ?: ByteArray(0),
                status = status
            )
        )
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        readResult.complete(
            CharacteristicReadResult(
                uuid = characteristic.uuid,
                value = value,
                status = status
            )
        )
    }

    @Deprecated("Deprecated in Android 13; retained for API 29-32 callbacks.")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        handleNotification(characteristic, characteristic.value ?: ByteArray(0))
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        handleNotification(characteristic, value)
    }

    fun close() {
        controlNotifications.close()
        dataNotifications.close()
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        when (characteristic.uuid) {
            PolarPmdProtocol.PmdControlPoint -> controlNotifications.trySendBlocking(value.copyOf())
            PolarPmdProtocol.PmdData -> dataNotifications.trySendBlocking(value.copyOf())
        }
    }
}

@SuppressLint("MissingPermission")
private fun ScanResult.toPolarCandidate(): PolarDeviceCandidate? {
    val advertisedName = scanRecord?.deviceName ?: device.name
    val hasHeartRate = advertisesService(PolarPmdProtocol.HeartRateService)
    val hasPmd = advertisesService(PolarPmdProtocol.PmdService)
    val matchesPolar = advertisedName?.contains("Polar", ignoreCase = true) == true ||
        hasHeartRate ||
        hasPmd
    if (!matchesPolar) {
        return null
    }
    return PolarDeviceCandidate(
        device = device,
        deviceName = advertisedName ?: device.name ?: "Polar-compatible BLE sensor",
        deviceAddress = device.address ?: "unknown",
        rssi = rssi,
        heartRateServiceVisible = hasHeartRate,
        pmdServiceVisible = hasPmd
    )
}

private fun ScanResult.advertisesService(uuid: java.util.UUID): Boolean =
    scanRecord?.serviceUuids?.contains(ParcelUuid(uuid)) == true

private fun Channel<ByteArray>.drain() {
    while (tryReceive().isSuccess) {
        // Drain stale notifications before a command/response exchange.
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothGatt.writeCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(
            characteristic,
            value,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        characteristic.value = value
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        writeCharacteristic(characteristic)
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothGatt.writeDescriptorCompat(
    descriptor: BluetoothGattDescriptor,
    value: ByteArray
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = value
        @Suppress("DEPRECATION")
        writeDescriptor(descriptor)
    }
}
