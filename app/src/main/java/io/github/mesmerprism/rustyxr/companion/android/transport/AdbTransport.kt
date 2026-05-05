package io.github.mesmerprism.rustyxr.companion.android.transport

import kotlinx.coroutines.flow.StateFlow

interface AdbTransport {
    val operationProgress: StateFlow<TransportOperationProgress?>

    suspend fun inspectUsb(): TransportResult
    suspend fun probeUsbAdb(): TransportResult
    suspend fun enableWifiFromUsb(): TransportResult
    suspend fun connect(endpoint: String): TransportResult
    suspend fun install(apkFile: String, packageId: String): TransportResult
    suspend fun pushHotload(packageId: String, fileName: String): TransportResult
    suspend fun applyDeviceProfile(profileId: String, props: Map<String, String>): TransportResult
    suspend fun launch(packageId: String, extras: Map<String, String> = emptyMap()): TransportResult
    suspend fun launchIntent(packageId: String, component: String, extras: Map<String, String> = emptyMap()): TransportResult
    suspend fun forceStop(packageId: String): TransportResult
    suspend fun openUrl(url: String, browserPackageId: String = ""): TransportResult
    suspend fun queryForegroundPackage(): TransportResult
    suspend fun runUtility(utility: AdbUtility): TransportResult
}

