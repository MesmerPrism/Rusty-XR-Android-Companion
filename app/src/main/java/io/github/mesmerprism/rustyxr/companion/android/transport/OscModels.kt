package io.github.mesmerprism.rustyxr.companion.android.transport

import java.util.Locale

data class OscMessage(
    val address: String,
    val arguments: List<OscArgument> = emptyList()
) {
    init {
        require(address.startsWith("/")) { "OSC address must start with `/`." }
        require(!address.contains(",")) { "OSC address must not contain a comma." }
    }

    fun summary(): String {
        val suffix = if (arguments.isEmpty()) {
            "no args"
        } else {
            arguments.joinToString(" ") { it.summary() }
        }
        return "$address $suffix"
    }
}

sealed class OscArgument {
    abstract val typeTag: Char
    abstract fun summary(): String

    data class Int32(val value: Int) : OscArgument() {
        override val typeTag: Char = 'i'
        override fun summary(): String = "int:$value"
    }

    data class Float32(val value: Float) : OscArgument() {
        override val typeTag: Char = 'f'
        override fun summary(): String = "float:${value.formatOscFloat()}"
    }

    data class StringValue(val value: String) : OscArgument() {
        override val typeTag: Char = 's'
        override fun summary(): String = "string:$value"
    }

    data class Blob(val value: ByteArray) : OscArgument() {
        override val typeTag: Char = 'b'
        override fun summary(): String = "blob-hex:${value.toHexString()}"

        override fun equals(other: Any?): Boolean {
            return other is Blob && value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()
    }

    data object TrueValue : OscArgument() {
        override val typeTag: Char = 'T'
        override fun summary(): String = "bool:true"
    }

    data object FalseValue : OscArgument() {
        override val typeTag: Char = 'F'
        override fun summary(): String = "bool:false"
    }

    data object Nil : OscArgument() {
        override val typeTag: Char = 'N'
        override fun summary(): String = "nil"
    }

    data object Impulse : OscArgument() {
        override val typeTag: Char = 'I'
        override fun summary(): String = "impulse"
    }
}

data class OscSendReceipt(
    val host: String,
    val port: Int,
    val sizeBytes: Int,
    val message: OscMessage
) {
    fun summary(): String = "Sent ${sizeBytes}B OSC packet to $host:$port."
    fun detail(): String = message.summary()
}

data class OscInboundPacket(
    val status: String,
    val detail: String,
    val sourceHost: String,
    val sourcePort: Int,
    val sizeBytes: Int,
    val receivedAtMillis: Long,
    val message: OscMessage? = null
) {
    fun logLine(): String {
        val payload = message?.summary() ?: detail
        return "$sourceHost:$sourcePort $payload"
    }
}

object OscArgumentDraftParser {
    fun parse(draft: String): List<OscArgument> {
        return splitDraft(draft)
            .filter { it.isNotBlank() }
            .map(::parseToken)
    }

    private fun parseToken(token: String): OscArgument {
        val trimmed = token.trim()
        val delimiterIndex = trimmed.indexOf(':')
        if (delimiterIndex < 0) {
            return when (trimmed.lowercase(Locale.US)) {
                "true" -> OscArgument.TrueValue
                "false" -> OscArgument.FalseValue
                "nil", "null" -> OscArgument.Nil
                "impulse" -> OscArgument.Impulse
                else -> throw IllegalArgumentException("OSC argument `$trimmed` needs a type prefix such as `float:0.5`.")
            }
        }

        val kind = trimmed.substring(0, delimiterIndex).trim().lowercase(Locale.US)
        val value = trimmed.substring(delimiterIndex + 1).trim()
        return when (kind) {
            "i", "int", "int32" -> OscArgument.Int32(
                value.toIntOrNull() ?: throw IllegalArgumentException("Invalid OSC int argument `$trimmed`.")
            )
            "f", "float", "float32" -> OscArgument.Float32(
                value.toFloatOrNull() ?: throw IllegalArgumentException("Invalid OSC float argument `$trimmed`.")
            )
            "s", "string", "str" -> OscArgument.StringValue(value)
            "bool", "boolean" -> when (value.lowercase(Locale.US)) {
                "true", "1", "yes", "on" -> OscArgument.TrueValue
                "false", "0", "no", "off" -> OscArgument.FalseValue
                else -> throw IllegalArgumentException("Invalid OSC bool argument `$trimmed`.")
            }
            "blob", "blob-hex", "hex" -> OscArgument.Blob(parseHex(value))
            "nil", "null" -> OscArgument.Nil
            "impulse" -> OscArgument.Impulse
            else -> throw IllegalArgumentException("Unsupported OSC argument type `$kind`.")
        }
    }

    private fun splitDraft(draft: String): List<String> {
        return draft
            .lineSequence()
            .flatMap { line -> line.split(',').asSequence() }
            .map { it.trim() }
            .toList()
    }

    private fun parseHex(value: String): ByteArray {
        val normalized = value
            .replace("0x", "", ignoreCase = true)
            .replace(Regex("[^0-9A-Fa-f]"), "")
        require(normalized.length % 2 == 0) { "OSC blob hex must have an even number of digits." }
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

private fun Float.formatOscFloat(): String {
    return if (this % 1f == 0f) {
        toInt().toString()
    } else {
        String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}
