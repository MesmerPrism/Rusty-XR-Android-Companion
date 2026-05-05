package io.github.mesmerprism.rustyxr.companion.android.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OscCodecTest {
    @Test
    fun `encodes and decodes supported argument types`() {
        val message = OscMessage(
            address = "/rusty-xr/probe",
            arguments = listOf(
                OscArgument.Int32(7),
                OscArgument.Float32(0.75f),
                OscArgument.StringValue("ready"),
                OscArgument.TrueValue,
                OscArgument.FalseValue,
                OscArgument.Nil,
                OscArgument.Impulse,
                OscArgument.Blob(byteArrayOf(0x0a, 0x0b))
            )
        )

        val decoded = OscCodec.decode(OscCodec.encode(message))

        assertEquals(message.address, decoded.address)
        assertEquals(message.arguments.take(7), decoded.arguments.take(7))
        assertArrayEquals(
            byteArrayOf(0x0a, 0x0b),
            (decoded.arguments.last() as OscArgument.Blob).value
        )
    }

    @Test
    fun `parser accepts windows companion style argument drafts`() {
        val arguments = OscArgumentDraftParser.parse(
            """
            float:0.75
            int:3
            string:armed
            bool:true
            nil
            impulse
            blob-hex:0a0b
            """.trimIndent()
        )

        assertEquals(OscArgument.Float32(0.75f), arguments[0])
        assertEquals(OscArgument.Int32(3), arguments[1])
        assertEquals(OscArgument.StringValue("armed"), arguments[2])
        assertEquals(OscArgument.TrueValue, arguments[3])
        assertEquals(OscArgument.Nil, arguments[4])
        assertEquals(OscArgument.Impulse, arguments[5])
        assertArrayEquals(byteArrayOf(0x0a, 0x0b), (arguments[6] as OscArgument.Blob).value)
    }

    @Test
    fun `decode rejects unsupported packets`() {
        val failure = runCatching { OscCodec.decode(byteArrayOf(1, 2, 3)) }

        assertTrue(failure.isFailure)
    }
}
