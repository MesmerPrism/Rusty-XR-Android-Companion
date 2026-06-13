package io.github.mesmerprism.rustyxr.companion.android.transport

import java.util.Locale

internal object Q2QStreamFraming {
    const val MANIFOLD_STREAM_MAGIC = "RMANVID1"
    const val LEGACY_RUSTY_XR_STREAM_MAGIC = "RXYRVID1"
    const val DEFAULT_WRITE_STREAM_MAGIC = LEGACY_RUSTY_XR_STREAM_MAGIC

    fun normalizeWriteMagic(value: String): String {
        val token = value.trim()
        if (token.isEmpty()) {
            return DEFAULT_WRITE_STREAM_MAGIC
        }
        return when (token.uppercase(Locale.US)) {
            MANIFOLD_STREAM_MAGIC, "MANIFOLD", "RUSTY-MANIFOLD", "RUSTY_MANIFOLD" ->
                MANIFOLD_STREAM_MAGIC
            LEGACY_RUSTY_XR_STREAM_MAGIC, "LEGACY", "RUSTY-XR", "RUSTY_XR" ->
                LEGACY_RUSTY_XR_STREAM_MAGIC
            else -> throw IllegalArgumentException(
                "Unsupported q2q stream magic `$value`; expected manifold/RMANVID1 or legacy/RXYRVID1."
            )
        }
    }

    fun isSupportedReadMagic(value: String): Boolean =
        value == MANIFOLD_STREAM_MAGIC || value == LEGACY_RUSTY_XR_STREAM_MAGIC
}
