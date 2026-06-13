package io.github.mesmerprism.rustyxr.companion.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Q2QStreamFramingTest {
    @Test
    fun `defaults sender stream magic to legacy rusty xr framing`() {
        assertEquals(
            Q2QStreamFraming.LEGACY_RUSTY_XR_STREAM_MAGIC,
            Q2QStreamFraming.normalizeWriteMagic("")
        )
    }

    @Test
    fun `normalizes manifold sender stream magic aliases`() {
        assertEquals(
            Q2QStreamFraming.MANIFOLD_STREAM_MAGIC,
            Q2QStreamFraming.normalizeWriteMagic("manifold")
        )
        assertEquals(
            Q2QStreamFraming.MANIFOLD_STREAM_MAGIC,
            Q2QStreamFraming.normalizeWriteMagic("RMANVID1")
        )
    }

    @Test
    fun `receiver accepts manifold and legacy stream magic`() {
        assertTrue(Q2QStreamFraming.isSupportedReadMagic("RMANVID1"))
        assertTrue(Q2QStreamFraming.isSupportedReadMagic("RXYRVID1"))
        assertFalse(Q2QStreamFraming.isSupportedReadMagic("RMQVID01"))
    }

    @Test
    fun `rejects unknown sender stream magic`() {
        assertTrue(runCatching { Q2QStreamFraming.normalizeWriteMagic("RMQVID01") }.isFailure)
    }
}
