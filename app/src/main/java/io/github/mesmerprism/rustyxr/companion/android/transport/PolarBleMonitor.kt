package io.github.mesmerprism.rustyxr.companion.android.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class PolarSensorReading(
    val status: String,
    val detail: String,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val rssi: Int? = null,
    val batteryPercent: Int? = null,
    val heartRateServiceVisible: Boolean = false,
    val polarPmdServiceVisible: Boolean = false
)

interface PolarSensorMonitor {
    fun monitor(): Flow<PolarSensorReading>
}

class AndroidPolarBleMonitor(
    appContext: Context
) : PolarSensorMonitor {
    private val applicationContext = appContext.applicationContext

    @SuppressLint("MissingPermission")
    override fun monitor(): Flow<PolarSensorReading> = callbackFlow {
        val missingPermissions = missingBluetoothPermissions()
        if (missingPermissions.isNotEmpty()) {
            trySend(
                PolarSensorReading(
                    status = "Polar monitor needs Bluetooth permission.",
                    detail = "Grant ${missingPermissions.joinToString(", ")} so the phone can scan for nearby Polar sensors."
                )
            )
            close()
            return@callbackFlow
        }

        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            trySend(
                PolarSensorReading(
                    status = "BLE is not available on this phone.",
                    detail = "Polar H10 availability checks require Android Bluetooth Low Energy support."
                )
            )
            close()
            return@callbackFlow
        }

        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            trySend(
                PolarSensorReading(
                    status = "Bluetooth adapter unavailable.",
                    detail = "Android did not expose a Bluetooth adapter to the app."
                )
            )
            close()
            return@callbackFlow
        }

        if (!adapter.isEnabled) {
            trySend(
                PolarSensorReading(
                    status = "Bluetooth is disabled.",
                    detail = "Enable phone Bluetooth, then start the Polar monitor again."
                )
            )
            close()
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            trySend(
                PolarSensorReading(
                    status = "BLE scanner unavailable.",
                    detail = "Android did not expose a BLE scanner. Toggle Bluetooth and try again."
                )
            )
            close()
            return@callbackFlow
        }

        var activeGatt: BluetoothGatt? = null
        var selectedAddress: String? = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    trySend(
                        PolarSensorReading(
                            status = "Polar connection failed.",
                            detail = "Bluetooth GATT status $status while connecting to ${gatt.device.safeName()}."
                        )
                    )
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        trySend(
                            PolarSensorReading(
                                status = "Polar sensor connected.",
                                detail = "Connected to ${gatt.device.safeName()}. Discovering battery and data services...",
                                deviceName = gatt.device.safeName(),
                                deviceAddress = gatt.device.safeAddress()
                            )
                        )
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        trySend(
                            PolarSensorReading(
                                status = "Polar sensor disconnected.",
                                detail = "The BLE link closed. Start the monitor again when the sensor is nearby.",
                                deviceName = gatt.device.safeName(),
                                deviceAddress = gatt.device.safeAddress()
                            )
                        )
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    trySend(
                        PolarSensorReading(
                            status = "Polar service discovery failed.",
                            detail = "Bluetooth GATT status $status while reading ${gatt.device.safeName()} services.",
                            deviceName = gatt.device.safeName(),
                            deviceAddress = gatt.device.safeAddress()
                        )
                    )
                    return
                }

                val batteryCharacteristic = gatt.batteryLevelCharacteristic()
                val hasHeartRate = gatt.getService(HEART_RATE_SERVICE_UUID) != null
                val hasPmd = gatt.getService(POLAR_PMD_SERVICE_UUID) != null
                if (batteryCharacteristic == null) {
                    trySend(
                        PolarSensorReading(
                            status = "Polar sensor available.",
                            detail = "Found ${gatt.device.safeName()}, but the standard BLE battery characteristic was not advertised.",
                            deviceName = gatt.device.safeName(),
                            deviceAddress = gatt.device.safeAddress(),
                            heartRateServiceVisible = hasHeartRate,
                            polarPmdServiceVisible = hasPmd
                        )
                    )
                    return
                }

                trySend(
                    PolarSensorReading(
                        status = "Reading Polar battery...",
                        detail = "Found ${gatt.device.safeName()}. Reading the standard BLE Battery Level characteristic.",
                        deviceName = gatt.device.safeName(),
                        deviceAddress = gatt.device.safeAddress(),
                        heartRateServiceVisible = hasHeartRate,
                        polarPmdServiceVisible = hasPmd
                    )
                )
                gatt.readCharacteristic(batteryCharacteristic)
            }

            @Deprecated("Deprecated in Android 13; retained for API 29-32 callbacks.")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handleCharacteristicRead(gatt, characteristic, characteristic.value, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleCharacteristicRead(gatt, characteristic, value, status)
            }

            private fun handleCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (characteristic.uuid != BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                    return
                }

                val hasHeartRate = gatt.getService(HEART_RATE_SERVICE_UUID) != null
                val hasPmd = gatt.getService(POLAR_PMD_SERVICE_UUID) != null
                if (status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) {
                    trySend(
                        PolarSensorReading(
                            status = "Polar battery read failed.",
                            detail = "Bluetooth GATT status $status while reading battery from ${gatt.device.safeName()}.",
                            deviceName = gatt.device.safeName(),
                            deviceAddress = gatt.device.safeAddress(),
                            heartRateServiceVisible = hasHeartRate,
                            polarPmdServiceVisible = hasPmd
                        )
                    )
                    return
                }

                val batteryPercent = value.first().toInt() and 0xFF
                trySend(
                    PolarSensorReading(
                        status = "Polar sensor available.",
                        detail = "Battery $batteryPercent%. HR service=${hasHeartRate.yesNo()}, PMD service=${hasPmd.yesNo()}.",
                        deviceName = gatt.device.safeName(),
                        deviceAddress = gatt.device.safeAddress(),
                        batteryPercent = batteryPercent,
                        heartRateServiceVisible = hasHeartRate,
                        polarPmdServiceVisible = hasPmd
                    )
                )
            }
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val advertisedName = result.scanRecord?.deviceName ?: device.actualName()
                val matchesPolar = advertisedName?.contains("Polar", ignoreCase = true) == true ||
                    result.advertisesPolarService()
                if (!matchesPolar) {
                    return
                }

                val displayName = advertisedName ?: "Polar-compatible BLE sensor"
                val address = device.safeAddress()
                if (selectedAddress == null) {
                    selectedAddress = address
                    trySend(
                        PolarSensorReading(
                            status = "Polar sensor found.",
                            detail = "Found $displayName at $address with RSSI ${result.rssi}. Connecting to read availability and battery...",
                            deviceName = displayName,
                            deviceAddress = address,
                            rssi = result.rssi,
                            heartRateServiceVisible = result.advertisesService(HEART_RATE_SERVICE_UUID),
                            polarPmdServiceVisible = result.advertisesService(POLAR_PMD_SERVICE_UUID)
                        )
                    )
                    scanner.stopScan(this)
                    activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(applicationContext, false, gattCallback)
                    }
                } else if (selectedAddress == address) {
                    trySend(
                        PolarSensorReading(
                            status = "Polar sensor visible.",
                            detail = "$displayName is still advertising with RSSI ${result.rssi}.",
                            deviceName = displayName,
                            deviceAddress = address,
                            rssi = result.rssi,
                            heartRateServiceVisible = result.advertisesService(HEART_RATE_SERVICE_UUID),
                            polarPmdServiceVisible = result.advertisesService(POLAR_PMD_SERVICE_UUID)
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(
                    PolarSensorReading(
                        status = "Polar scan failed.",
                        detail = "Android BLE scanner returned error $errorCode."
                    )
                )
            }
        }

        trySend(
            PolarSensorReading(
                status = "Scanning for Polar sensors...",
                detail = "Looking for BLE advertisements with Polar names, Heart Rate service, or Polar PMD service."
            )
        )
        scanner.startScan(
            emptyList(),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            scanCallback
        )

        awaitClose {
            runCatching { scanner.stopScan(scanCallback) }
            activeGatt?.let { gatt ->
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
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
            )
                .filter { applicationContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        }
    }

    private companion object {
        private val HEART_RATE_SERVICE_UUID: UUID = AndroidPolarServiceUuids.HeartRate
        private val BATTERY_SERVICE_UUID: UUID = AndroidPolarServiceUuids.Battery
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = AndroidPolarServiceUuids.BatteryLevel
        private val POLAR_PMD_SERVICE_UUID: UUID = AndroidPolarServiceUuids.PolarPmd
    }
}

private fun BluetoothGatt.batteryLevelCharacteristic(): BluetoothGattCharacteristic? {
    return getService(AndroidPolarServiceUuids.Battery)
        ?.getCharacteristic(AndroidPolarServiceUuids.BatteryLevel)
}

private fun ScanResult.advertisesPolarService(): Boolean {
    return advertisesService(AndroidPolarServiceUuids.HeartRate) ||
        advertisesService(AndroidPolarServiceUuids.PolarPmd)
}

private fun ScanResult.advertisesService(uuid: UUID): Boolean {
    return scanRecord?.serviceUuids?.contains(ParcelUuid(uuid)) == true
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.actualName(): String? {
    return name
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeName(): String {
    return name ?: "Polar sensor"
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeAddress(): String {
    return address ?: "unknown"
}

private fun Boolean.yesNo(): String = if (this) "yes" else "no"

private object AndroidPolarServiceUuids {
    val HeartRate: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val Battery: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BatteryLevel: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val PolarPmd: UUID = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
}
