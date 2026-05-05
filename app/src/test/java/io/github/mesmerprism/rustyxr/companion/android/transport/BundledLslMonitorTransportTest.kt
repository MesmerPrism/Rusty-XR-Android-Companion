package io.github.mesmerprism.rustyxr.companion.android.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLslMonitorTransportTest {
    @Test
    fun `monitor reports unavailable runtime`() = runTest {
        val bridge = FakeLslMonitorBridge(
            runtimeState = NativeLoadState(
                available = false,
                detail = "liblsl missing"
            )
        )
        val transport = BundledLslMonitorTransport(bridge)

        val readings = transport.monitor(subscription()).toList()

        assertEquals(1, readings.size)
        assertEquals("LSL unavailable.", readings.single().status)
        assertEquals("liblsl missing", readings.single().detail)
    }

    @Test
    fun `monitor reports connected stream and sample`() = runTest {
        val bridge = FakeLslMonitorBridge(
            session = NativeMonitorSession(
                handle = 7L,
                resolvedName = "quest_monitor",
                resolvedType = "quest.telemetry",
                channelCount = 4,
                sampleRateHz = 120f
            ),
            samples = ArrayDeque(
                listOf(
                    NativeMonitorSample(timestampSeconds = 1.0, value = 0.42f),
                    NativeMonitorSample(timestampSeconds = 1.1, value = 0.43f)
                )
            )
        )
        val transport = BundledLslMonitorTransport(bridge)

        val readings = transport.monitor(subscription()).take(3).toList()

        assertEquals(
            listOf(
                "Resolving LSL stream...",
                "LSL stream connected.",
                "Streaming LSL sample."
            ),
            readings.map { it.status }
        )
        assertEquals(0.42f, readings.last().value)
        assertEquals(1, bridge.closedHandles.size)
        assertEquals(7L, bridge.closedHandles.single().toLong())
    }

    @Test
    fun `monitor reports invalid channel contract`() = runTest {
        val bridge = FakeLslMonitorBridge(
            openFailure = IllegalArgumentException("Channel out of range")
        )
        val transport = BundledLslMonitorTransport(bridge)

        val readings = transport.monitor(subscription()).take(2).toList()

        assertEquals("Resolving LSL stream...", readings.first().status)
        assertEquals("LSL channel unavailable.", readings.last().status)
        assertTrue(readings.last().detail.contains("Channel out of range"))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `monitor re-resolves after stale idle period`() = runTest {
        var fakeNowMs = 0L
        val bridge = FakeLslMonitorBridge(
            session = NativeMonitorSession(
                handle = 11L,
                resolvedName = "quest_monitor",
                resolvedType = "quest.telemetry",
                channelCount = 1,
                sampleRateHz = 20f
            ),
            onPull = { fakeNowMs += 1000L }
        )
        val transport = BundledLslMonitorTransport(
            bridge = bridge,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            nowMs = { fakeNowMs },
            retryDelayMs = 1L,
            idleEmitIntervalMs = 1500L,
            valueEmitIntervalMs = 50L,
            staleReconnectMs = 5000L
        )

        val readings = transport.monitor(subscription()).take(5).toList()

        assertEquals(
            listOf(
                "Resolving LSL stream...",
                "LSL stream connected.",
                "Waiting for LSL samples...",
                "LSL stream idle. Re-resolving...",
                "Resolving LSL stream..."
            ),
            readings.map { it.status }
        )
        assertTrue(readings[3].detail.contains("closing the current inlet"))
        assertEquals(1, bridge.closedHandles.size)
        assertEquals(11L, bridge.closedHandles.single())
    }

    private fun subscription(): MonitorSubscription {
        return MonitorSubscription(
            streamName = "quest_monitor",
            streamType = "quest.telemetry",
            channelIndex = 0
        )
    }
}

private class FakeLslMonitorBridge(
    private val runtimeState: NativeLoadState = NativeLoadState(
        available = true,
        detail = "liblsl ready"
    ),
    private val session: NativeMonitorSession? = null,
    private val samples: ArrayDeque<NativeMonitorSample?> = ArrayDeque(),
    private val openFailure: Throwable? = null,
    private val onPull: (() -> Unit)? = null
) : LslMonitorBridge {
    val closedHandles = mutableListOf<Long>()

    override fun runtimeState(): NativeLoadState = runtimeState

    override fun openStream(subscription: MonitorSubscription): NativeMonitorSession? {
        openFailure?.let { throw it }
        return session
    }

    override fun pullSample(handle: Long): NativeMonitorSample? {
        onPull?.invoke()
        return samples.removeFirstOrNull()
    }

    override fun closeStream(handle: Long) {
        closedHandles += handle
    }
}
