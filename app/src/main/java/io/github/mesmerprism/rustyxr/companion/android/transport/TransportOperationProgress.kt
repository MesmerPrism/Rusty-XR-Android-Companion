package io.github.mesmerprism.rustyxr.companion.android.transport

enum class TransportOperationKind {
    InstallApk,
    PushHotload,
    ApplyDeviceProfile,
    LaunchApp,
    ForceStopApp,
    OpenUrl,
    QueryForegroundPackage,
    ListInstalledPackages,
    UtilityCommand
}

data class TransportOperationProgress(
    val kind: TransportOperationKind,
    val title: String,
    val detail: String = "",
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val fraction: Float? = null,
    val currentBytes: Long? = null,
    val totalBytes: Long? = null,
    val packageId: String? = null,
    val utility: AdbUtility? = null
)
