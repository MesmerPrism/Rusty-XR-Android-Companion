package io.github.mesmerprism.rustyxr.companion.android.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object OscCodec {
    fun encode(message: OscMessage): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.appendOscString(message.address)
        bytes.appendOscString("," + message.arguments.joinToString("") { it.typeTag.toString() })
        message.arguments.forEach { argument ->
            when (argument) {
                is OscArgument.Int32 -> bytes.appendInt(argument.value)
                is OscArgument.Float32 -> bytes.appendFloat(argument.value)
                is OscArgument.StringValue -> bytes.appendOscString(argument.value)
                is OscArgument.Blob -> bytes.appendBlob(argument.value)
                OscArgument.TrueValue,
                OscArgument.FalseValue,
                OscArgument.Nil,
                OscArgument.Impulse -> Unit
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size): OscMessage {
        require(length in 0..bytes.size) { "OSC decode length is outside the packet buffer." }
        val reader = Reader(bytes, length)
        val address = reader.readOscString()
        require(address.startsWith("/")) { "OSC packet address must start with `/`." }
        val typeTags = reader.readOscString()
        require(typeTags.startsWith(",")) { "OSC packet is missing the type-tag comma." }
        val arguments = typeTags.drop(1).map { tag ->
            when (tag) {
                'i' -> OscArgument.Int32(reader.readInt())
                'f' -> OscArgument.Float32(reader.readFloat())
                's' -> OscArgument.StringValue(reader.readOscString())
                'b' -> OscArgument.Blob(reader.readBlob())
                'T' -> OscArgument.TrueValue
                'F' -> OscArgument.FalseValue
                'N' -> OscArgument.Nil
                'I' -> OscArgument.Impulse
                else -> throw IllegalArgumentException("Unsupported OSC type tag `$tag`.")
            }
        }
        return OscMessage(address = address, arguments = arguments)
    }

    private class Reader(
        private val bytes: ByteArray,
        private val length: Int
    ) {
        private var offset: Int = 0

        fun readOscString(): String {
            val start = offset
            while (offset < length && bytes[offset] != 0.toByte()) {
                offset++
            }
            require(offset < length) { "OSC string is not null-terminated." }
            val value = String(bytes, start, offset - start, StandardCharsets.UTF_8)
            offset++
            skipPadding()
            return value
        }

        fun readInt(): Int {
            require(offset + 4 <= length) { "OSC int32 argument is truncated." }
            val value = ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            offset += 4
            return value
        }

        fun readFloat(): Float {
            require(offset + 4 <= length) { "OSC float32 argument is truncated." }
            val value = ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .float
            offset += 4
            return value
        }

        fun readBlob(): ByteArray {
            val size = readInt()
            require(size >= 0) { "OSC blob length is negative." }
            require(offset + size <= length) { "OSC blob argument is truncated." }
            val value = bytes.copyOfRange(offset, offset + size)
            offset += size
            skipPadding()
            return value
        }

        private fun skipPadding() {
            while (offset % 4 != 0) {
                require(offset < length) { "OSC padding exceeded packet length." }
                offset++
            }
        }
    }
}

private fun MutableList<Byte>.appendOscString(value: String) {
    addAll(value.toByteArray(StandardCharsets.UTF_8).asIterable())
    add(0)
    appendPadding()
}

private fun MutableList<Byte>.appendInt(value: Int) {
    addAll(
        ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array()
            .asIterable()
    )
}

private fun MutableList<Byte>.appendFloat(value: Float) {
    addAll(
        ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putFloat(value)
            .array()
            .asIterable()
    )
}

private fun MutableList<Byte>.appendBlob(value: ByteArray) {
    appendInt(value.size)
    addAll(value.asIterable())
    appendPadding()
}

private fun MutableList<Byte>.appendPadding() {
    while (size % 4 != 0) {
        add(0)
    }
}
