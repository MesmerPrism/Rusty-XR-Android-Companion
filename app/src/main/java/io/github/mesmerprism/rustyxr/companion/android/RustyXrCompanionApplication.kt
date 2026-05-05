package io.github.mesmerprism.rustyxr.companion.android

import android.app.Application
import android.net.wifi.WifiManager
import android.util.Log
import io.github.mesmerprism.rustyxr.companion.android.data.RustyXrCatalogLoader
import io.github.mesmerprism.rustyxr.companion.android.data.UserRuntimeProfileStore
import io.github.mesmerprism.rustyxr.companion.android.diagnostics.DiagnosticsBundleExporter
import io.github.mesmerprism.rustyxr.companion.android.session.QuestSessionController
import io.github.mesmerprism.rustyxr.companion.android.session.SessionManifestWriter
import io.github.mesmerprism.rustyxr.companion.android.transport.AndroidPolarBleMonitor
import io.github.mesmerprism.rustyxr.companion.android.transport.BundledLslMonitorTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.LiveAdbTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.OscUdpTransport

class RustyXrCompanionApplication : Application() {
    lateinit var sessionController: QuestSessionController
        private set
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWifiMulticastLock()

        sessionController = QuestSessionController(
            appContext = applicationContext,
            catalogLoader = RustyXrCatalogLoader(applicationContext),
            userRuntimeProfileStore = UserRuntimeProfileStore(applicationContext),
            manifestWriter = SessionManifestWriter(applicationContext),
            diagnosticsBundleExporter = DiagnosticsBundleExporter(applicationContext),
            adbTransport = LiveAdbTransport(applicationContext),
            monitorTransport = BundledLslMonitorTransport(),
            oscTransport = OscUdpTransport(),
            polarMonitor = AndroidPolarBleMonitor(applicationContext)
        )
    }

    private fun acquireWifiMulticastLock() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
        runCatching {
            wifiManager.createMulticastLock("rustyxrcompanion:lsl").apply {
                setReferenceCounted(false)
                acquire()
                multicastLock = this
            }
        }.onFailure { throwable ->
            Log.w(
                "RustyXrCompanion",
                "Failed to acquire Wi-Fi multicast lock for LSL discovery.",
                throwable
            )
        }
    }
}

