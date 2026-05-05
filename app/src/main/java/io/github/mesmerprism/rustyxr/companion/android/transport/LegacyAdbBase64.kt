package io.github.mesmerprism.rustyxr.companion.android.transport

import android.util.Base64
import com.cgutman.adblib.AdbBase64

class LegacyAdbBase64 : AdbBase64 {
    override fun encodeToString(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
}

