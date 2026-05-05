package io.github.mesmerprism.rustyxr.companion.android.transport

data class NativeMonitorSession(
    val handle: Long,
    val resolvedName: String,
    val resolvedType: String,
    val channelCount: Int,
    val sampleRateHz: Float
)

data class NativeMonitorSample(
    val timestampSeconds: Double,
    val value: Float
)

internal data class NativeLoadState(
    val available: Boolean,
    val detail: String
)

internal class LslNativeBridge private constructor() {
    companion object {
        private val loadState: NativeLoadState by lazy(::loadRuntime)

        fun runtimeState(): NativeLoadState = loadState

        fun openStream(subscription: MonitorSubscription): NativeMonitorSession? {
            val state = loadState
            check(state.available) { state.detail }
            return nativeOpenStream(
                subscription.streamName.trim(),
                subscription.streamType.trim(),
                subscription.channelIndex,
                RESOLVE_TIMEOUT_SECONDS
            )
        }

        fun pullSample(handle: Long): NativeMonitorSample? {
            val state = loadState
            check(state.available) { state.detail }
            return nativePullSample(handle, PULL_TIMEOUT_SECONDS)
        }

        fun closeStream(handle: Long) {
            if (handle == 0L || !loadState.available) {
                return
            }
            nativeCloseStream(handle)
        }

        private fun loadRuntime(): NativeLoadState {
            val attempts = mutableListOf<String>()
            return try {
                runCatching {
                    System.loadLibrary("c++_shared")
                    attempts += "Loaded libc++_shared."
                }
                System.loadLibrary("lsl")
                attempts += "Loaded liblsl."
                System.loadLibrary("quest_lsl_bridge")
                attempts += "Loaded quest_lsl_bridge."

                val info = runCatching { nativeLibraryInfo() }
                    .getOrElse { throwable -> "liblsl info unavailable: ${throwable.message ?: throwable.javaClass.simpleName}" }

                NativeLoadState(
                    available = true,
                    detail = listOf(info, attempts.joinToString(" "))
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                )
            } catch (throwable: Throwable) {
                NativeLoadState(
                    available = false,
                    detail = buildString {
                        append("Bundled native liblsl could not be loaded. ")
                        append(throwable.message ?: throwable.javaClass.simpleName)
                        if (attempts.isNotEmpty()) {
                            append(" ")
                            append(attempts.joinToString(" "))
                        }
                    }.trim()
                )
            }
        }

        @JvmStatic
        private external fun nativeLibraryInfo(): String

        @JvmStatic
        private external fun nativeOpenStream(
            streamName: String,
            streamType: String,
            channelIndex: Int,
            resolveTimeoutSeconds: Double
        ): NativeMonitorSession?

        @JvmStatic
        private external fun nativePullSample(
            handle: Long,
            timeoutSeconds: Double
        ): NativeMonitorSample?

        @JvmStatic
        private external fun nativeCloseStream(handle: Long)

        private const val RESOLVE_TIMEOUT_SECONDS = 1.0
        private const val PULL_TIMEOUT_SECONDS = 0.5
    }
}
