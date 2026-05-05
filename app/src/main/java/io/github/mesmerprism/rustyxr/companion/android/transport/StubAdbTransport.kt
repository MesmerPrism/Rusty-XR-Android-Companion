package io.github.mesmerprism.rustyxr.companion.android.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StubAdbTransport : AdbTransport {
    private val _operationProgress = MutableStateFlow<TransportOperationProgress?>(null)

    override val operationProgress: StateFlow<TransportOperationProgress?> = _operationProgress.asStateFlow()

    override suspend fun inspectUsb(): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "USB diagnostics are not available in the stub transport.",
            detail = "Real Android USB host inspection only exists in the live transport build."
        )
    }

    override suspend fun probeUsbAdb(): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "USB ADB probe is not available in the stub transport.",
            detail = "Real Android USB host shell access only exists in the live transport build."
        )
    }

    override suspend fun enableWifiFromUsb(): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "USB Wi-Fi ADB bootstrap prepared.",
            detail = "Quest USB host support is not wired in this stub transport."
        )
    }

    override suspend fun connect(endpoint: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Saved endpoint $endpoint.",
            detail = "Live Android-side ADB pairing and connect logic is the next transport milestone."
        )
    }

    override suspend fun install(apkFile: String, packageId: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Install prepared for $packageId ($apkFile).",
            detail = "The APK catalog is wired in, but the in-app ADB install path is still stubbed."
        )
    }

    override suspend fun pushHotload(packageId: String, fileName: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Runtime preset $fileName selected for $packageId.",
            detail = "Real adb push to runtime_overrides.csv still needs the Android-side transport bridge."
        )
    }

    override suspend fun applyDeviceProfile(
        profileId: String,
        props: Map<String, String>
    ): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Device preset $profileId prepared (${props.size} props).",
            detail = "Real setprop execution is not wired yet."
        )
    }

    override suspend fun launch(packageId: String, extras: Map<String, String>): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Launch prepared for $packageId.",
            detail = if (extras.isEmpty()) {
                "Real adb shell launch remains to be implemented."
            } else {
                "Would pass ${extras.size} runtime launch extra(s) when the live transport is active."
            }
        )
    }

    override suspend fun launchIntent(
        packageId: String,
        component: String,
        extras: Map<String, String>
    ): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Explicit launch prepared for $packageId.",
            detail = if (extras.isEmpty()) {
                "Would launch component $component when the live transport is active."
            } else {
                "Would launch component $component with ${extras.size} runtime extra(s) when the live transport is active."
            }
        )
    }

    override suspend fun forceStop(packageId: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Force-stop prepared for $packageId.",
            detail = "Real adb shell force-stop remains to be implemented."
        )
    }

    override suspend fun openUrl(url: String, browserPackageId: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Browser open prepared.",
            detail = "Would open $url${browserPackageId.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()} when the live transport is active."
        )
    }

    override suspend fun queryForegroundPackage(): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Foreground package query prepared.",
            detail = "The stub transport cannot inspect the active package.",
            packageId = null
        )
    }

    override suspend fun runUtility(utility: AdbUtility): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Utility prepared: ${utility.name}.",
            detail = "The live transport will execute the selected utility command over ADB."
        )
    }
}

