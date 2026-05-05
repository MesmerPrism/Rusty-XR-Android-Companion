package io.github.mesmerprism.rustyxr.companion.android.transport

import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

internal interface LslMonitorBridge {
    fun runtimeState(): NativeLoadState
    fun openStream(subscription: MonitorSubscription): NativeMonitorSession?
    fun pullSample(handle: Long): NativeMonitorSample?
    fun closeStream(handle: Long)
}

private object DefaultLslMonitorBridge : LslMonitorBridge {
    override fun runtimeState(): NativeLoadState = LslNativeBridge.runtimeState()

    override fun openStream(subscription: MonitorSubscription): NativeMonitorSession? {
        return LslNativeBridge.openStream(subscription)
    }

    override fun pullSample(handle: Long): NativeMonitorSample? = LslNativeBridge.pullSample(handle)

    override fun closeStream(handle: Long) {
        LslNativeBridge.closeStream(handle)
    }
}

internal class BundledLslMonitorTransport(
    private val bridge: LslMonitorBridge = DefaultLslMonitorBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val idleEmitIntervalMs: Long = IDLE_EMIT_INTERVAL_MS,
    private val valueEmitIntervalMs: Long = VALUE_EMIT_INTERVAL_MS,
    private val staleReconnectMs: Long = STALE_RECONNECT_MS
) : MonitorTransport {
    override fun monitor(subscription: MonitorSubscription): Flow<MonitorReading> = flow {
        val runtimeState = bridge.runtimeState()
        if (!runtimeState.available) {
            emit(
                subscription.reading(
                    status = "LSL unavailable.",
                    detail = runtimeState.detail
                )
            )
            return@flow
        }

        emit(
            subscription.reading(
                status = "Resolving LSL stream...",
                detail = "Searching for `${subscription.streamName}` / `${subscription.streamType}` over the bundled liblsl runtime."
            )
        )

        while (currentCoroutineContext().isActive) {
            val session = try {
                bridge.openStream(subscription)
            } catch (throwable: IllegalArgumentException) {
                emit(
                    subscription.reading(
                        status = "LSL channel unavailable.",
                        detail = throwable.message ?: "The requested stream contract is invalid."
                    )
                )
                delay(retryDelayMs)
                continue
            } catch (throwable: Throwable) {
                emit(
                    subscription.reading(
                        status = "LSL monitor error.",
                        detail = throwable.message ?: "The native liblsl bridge failed while opening the stream."
                    )
                )
                delay(retryDelayMs)
                continue
            }

            if (session == null) {
                emit(
                    subscription.reading(
                        status = "LSL stream not found.",
                        detail = "No visible stream matched `${subscription.streamName}` / `${subscription.streamType}`. The monitor will keep retrying."
                    )
                )
                delay(retryDelayMs)
                continue
            }

            emit(
                subscription.reading(
                    status = "LSL stream connected.",
                    detail = "Connected to `${session.resolvedName}` / `${session.resolvedType}` with ${session.channelCount} channel(s).",
                    sampleRateHz = session.sampleRateHz
                )
            )

            val sessionOpenedAtMs = nowMs()
            var lastIdleEmissionMs = sessionOpenedAtMs
            var lastValueEmissionMs = sessionOpenedAtMs - valueEmitIntervalMs
            var lastSampleReceivedAtMs = sessionOpenedAtMs
            var shouldReconnect = false
            var waitingStateEmitted = false

            try {
                while (currentCoroutineContext().isActive) {
                    val sample = bridge.pullSample(session.handle)
                    val currentTimeMs = nowMs()
                    if (sample == null) {
                        if (!waitingStateEmitted && currentTimeMs - lastIdleEmissionMs >= idleEmitIntervalMs) {
                            emit(
                                subscription.reading(
                                    status = "Waiting for LSL samples...",
                                    detail = "The stream is connected, but no new sample arrived during the last polling window. The monitor will re-resolve if this stays idle.",
                                    sampleRateHz = session.sampleRateHz
                                )
                            )
                            lastIdleEmissionMs = currentTimeMs
                            waitingStateEmitted = true
                        }
                        if (currentTimeMs - lastSampleReceivedAtMs >= staleReconnectMs) {
                            emit(
                                subscription.reading(
                                    status = "LSL stream idle. Re-resolving...",
                                    detail = "No sample arrived for ${(staleReconnectMs / 1000f).formatSeconds()}s, so the app is closing the current inlet and resolving the stream again.",
                                    sampleRateHz = session.sampleRateHz
                                )
                            )
                            shouldReconnect = true
                            break
                        }
                        continue
                    }

                    lastSampleReceivedAtMs = currentTimeMs
                    waitingStateEmitted = false
                    if (currentTimeMs - lastValueEmissionMs >= valueEmitIntervalMs) {
                        emit(
                            subscription.reading(
                                status = "Streaming LSL sample.",
                                detail = "Receiving `${session.resolvedName}` / `${session.resolvedType}` from the bundled liblsl inlet.",
                                value = sample.value,
                                sampleRateHz = session.sampleRateHz
                            )
                        )
                        lastValueEmissionMs = currentTimeMs
                        lastIdleEmissionMs = currentTimeMs
                    }
                }
            } catch (throwable: Throwable) {
                emit(
                    subscription.reading(
                        status = "LSL stream lost.",
                        detail = throwable.message ?: "The native liblsl inlet dropped during sample pull. Re-resolving now.",
                        sampleRateHz = session.sampleRateHz
                    )
                )
            } finally {
                bridge.closeStream(session.handle)
            }

            if (shouldReconnect) {
                emit(
                    subscription.reading(
                        status = "Resolving LSL stream...",
                        detail = "The previous inlet was idle, so the app is searching the network again for `${subscription.streamName}` / `${subscription.streamType}`."
                    )
                )
            }
            delay(retryDelayMs)
        }
    }.flowOn(dispatcher)

    private fun MonitorSubscription.reading(
        status: String,
        detail: String,
        value: Float? = null,
        sampleRateHz: Float = 0f
    ): MonitorReading {
        return MonitorReading(
            status = status,
            detail = detail,
            streamName = streamName,
            streamType = streamType,
            channelIndex = channelIndex,
            value = value,
            sampleRateHz = sampleRateHz
        )
    }

    private companion object {
        private const val RETRY_DELAY_MS = 1500L
        private const val IDLE_EMIT_INTERVAL_MS = 1500L
        private const val VALUE_EMIT_INTERVAL_MS = 50L
        private const val STALE_RECONNECT_MS = 5000L
    }
}

private fun Float.formatSeconds(): String {
    return if (this % 1f == 0f) {
        toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", this)
    }
}
