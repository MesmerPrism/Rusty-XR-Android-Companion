package io.github.mesmerprism.rustyxr.companion.android.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.mesmerprism.rustyxr.companion.android.transport.AndroidPolarBleMonitor
import io.github.mesmerprism.rustyxr.companion.android.transport.PolarSensorReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DebugPolarScanActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        Log.i(TAG, "Starting debug Polar BLE scan for ${timeoutMs}ms.")

        scope.launch {
            val monitor = AndroidPolarBleMonitor(applicationContext)
            val completed = CompletableDeferred<Boolean>()
            var latestReading: PolarSensorReading? = null
            val monitorJob = launch {
                monitor.monitor().collect { reading ->
                    latestReading = reading
                    Log.i(TAG, reading.toLogLine())
                    if (reading.status == "Polar sensor available." && reading.batteryPercent != null) {
                        completed.complete(true)
                    }
                }
                completed.complete(false)
            }

            val found = withTimeoutOrNull(timeoutMs) { completed.await() } == true
            monitorJob.cancelAndJoin()
            if (found) {
                Log.i(TAG, "Debug Polar BLE scan completed with battery read: ${latestReading.toLogLine()}")
            } else {
                Log.i(TAG, "Debug Polar BLE scan timed out or completed without battery read. Last=${latestReading.toLogLine()}")
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun PolarSensorReading?.toLogLine(): String {
        if (this == null) {
            return "none"
        }
        return buildList {
            add("status=$status")
            add("detail=$detail")
            deviceName?.let { add("name=$it") }
            deviceAddress?.let { add("address=$it") }
            rssi?.let { add("rssi=$it") }
            batteryPercent?.let { add("battery=$it") }
            add("hrService=$heartRateServiceVisible")
            add("pmdService=$polarPmdServiceVisible")
        }.joinToString(" | ")
    }

    private companion object {
        private const val TAG = "RustyXrPolarSmoke"
        private const val EXTRA_TIMEOUT_MS = "timeout_ms"
        private const val DEFAULT_TIMEOUT_MS = 30000L
        private const val MIN_TIMEOUT_MS = 3000L
        private const val MAX_TIMEOUT_MS = 60000L
    }
}
