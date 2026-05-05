package io.github.mesmerprism.rustyxr.companion.android.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PolarPmdProtocolTest {
    @Test
    fun buildStartEcgRequestUsesExpectedSettings() {
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x00,
                0x00,
                0x01,
                0x82.toByte(),
                0x00,
                0x01,
                0x01,
                0x0e,
                0x00
            ),
            PolarPmdProtocol.buildStartEcgRequest()
        )
    }

    @Test
    fun buildStartAccRequestUsesRangeFirstOrdering() {
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x02,
                0x02,
                0x01,
                0x08,
                0x00,
                0x00,
                0x01,
                0xc8.toByte(),
                0x00,
                0x01,
                0x01,
                0x10,
                0x00
            ),
            PolarPmdProtocol.buildStartAccRequest()
        )
    }

    @Test
    fun decodeEcgFrameReadsSigned24BitSamples() {
        val frame = byteArrayOf(
            0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00,
            0x34, 0x12, 0x00,
            0x00, 0x00, 0x80.toByte()
        )

        val decoded = requireNotNull(PolarPmdProtocol.decodeEcgFrame(frame))

        assertEquals(1L, decoded.timestampNs)
        assertEquals(2, decoded.sampleCount)
        assertEquals(0x1234, decoded.firstMicroVolts)
        assertEquals(-8_388_608, decoded.minMicroVolts)
        assertEquals(0x1234, decoded.maxMicroVolts)
    }

    @Test
    fun decodeUncompressedAccFrameReadsAxes() {
        val frame = byteArrayOf(
            0x02,
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01,
            0x10, 0x00,
            0xf0.toByte(), 0xff.toByte(),
            0x20, 0x00
        )

        val decoded = requireNotNull(PolarPmdProtocol.decodeAccFrame(frame))

        assertEquals(2L, decoded.timestampNs)
        assertEquals(1, decoded.sampleCount)
        assertEquals(16, decoded.firstXmg)
        assertEquals(-16, decoded.firstYmg)
        assertEquals(32, decoded.firstZmg)
    }

    @Test
    fun parseSettingsResponseExtractsValues() {
        val response = byteArrayOf(
            0xf0.toByte(),
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x82.toByte(),
            0x00,
            0x01,
            0x01,
            0x0e,
            0x00
        )

        val settings = PolarPmdProtocol.parseSettingsResponse(response)

        assertNotNull(settings)
        assertEquals(listOf(130), settings!!.sampleRates)
        assertEquals(listOf(14), settings.resolutions)
    }
}
