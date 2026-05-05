package io.github.mesmerprism.rustyxr.companion.android.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class PolarPmdControlSummary(
    val frameId: Int,
    val opCode: Int,
    val measurementType: Int,
    val errorCode: Int,
    val payloadHex: String
) {
    val success: Boolean = errorCode == 0
}

data class PolarPmdSettingsSummary(
    val measurementType: Int,
    val sampleRates: List<Int>,
    val resolutions: List<Int>,
    val ranges: List<Int>
) {
    val hasAny: Boolean =
        sampleRates.isNotEmpty() || resolutions.isNotEmpty() || ranges.isNotEmpty()
}

data class PolarPmdEcgFrameSummary(
    val timestampNs: Long,
    val sampleCount: Int,
    val minMicroVolts: Int,
    val maxMicroVolts: Int,
    val firstMicroVolts: Int
)

data class PolarPmdAccFrameSummary(
    val timestampNs: Long,
    val sampleCount: Int,
    val firstXmg: Int,
    val firstYmg: Int,
    val firstZmg: Int,
    val compressed: Boolean
)

data class PolarPmdSmokeResult(
    val passed: Boolean,
    val summary: String,
    val detail: String,
    val startedAtUtc: String,
    val endedAtUtc: String,
    val deviceName: String?,
    val deviceAddress: String?,
    val rssi: Int?,
    val batteryPercent: Int?,
    val negotiatedMtu: Int?,
    val heartRateServiceVisible: Boolean,
    val pmdServiceVisible: Boolean,
    val ecgFrameCount: Int,
    val ecgSampleCount: Int,
    val latestEcgFrame: PolarPmdEcgFrameSummary?,
    val accFrameCount: Int,
    val accSampleCount: Int,
    val latestAccFrame: PolarPmdAccFrameSummary?,
    val controlResponses: List<PolarPmdControlSummary>,
    val settings: List<PolarPmdSettingsSummary>,
    val notes: List<String>
)

internal object PolarPmdProtocol {
    val HeartRateService: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val BatteryService: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BatteryLevel: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val PmdService: UUID = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
    val PmdControlPoint: UUID = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
    val PmdData: UUID = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")
    val CccdDescriptor: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MeasurementTypeEcg: Byte = 0x00
    const val MeasurementTypeAcc: Byte = 0x02

    const val OpcodeGetSettings: Byte = 0x01
    const val OpcodeStartStream: Byte = 0x02
    const val OpcodeStopStream: Byte = 0x03

    private const val ResponseFrameId = 0xF0
    private const val SettingTypeSampleRate = 0x00
    private const val SettingTypeResolution = 0x01
    private const val SettingTypeRange = 0x02

    fun buildGetSettingsRequest(measurementType: Byte): ByteArray =
        byteArrayOf(OpcodeGetSettings, measurementType)

    fun buildStartEcgRequest(sampleRate: Int = 130, resolution: Int = 14): ByteArray {
        return buildStartRequest(
            measurementType = MeasurementTypeEcg,
            sampleRate = sampleRate,
            resolution = resolution,
            rangeG = null
        )
    }

    fun buildStartAccRequest(sampleRate: Int = 200, resolution: Int = 16, rangeG: Int = 8): ByteArray {
        return byteArrayOf(
            OpcodeStartStream,
            MeasurementTypeAcc,
            SettingTypeRange.toByte(),
            1.toByte(),
            (rangeG and 0xFF).toByte(),
            ((rangeG shr 8) and 0xFF).toByte(),
            SettingTypeSampleRate.toByte(),
            1.toByte(),
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            SettingTypeResolution.toByte(),
            1.toByte(),
            (resolution and 0xFF).toByte(),
            ((resolution shr 8) and 0xFF).toByte()
        )
    }

    fun buildStopRequest(measurementType: Byte): ByteArray =
        byteArrayOf(OpcodeStopStream, measurementType)

    fun parseControlResponse(bytes: ByteArray): PolarPmdControlSummary? {
        if (bytes.size < 4) {
            return null
        }
        return PolarPmdControlSummary(
            frameId = bytes[0].toUnsignedInt(),
            opCode = bytes[1].toUnsignedInt(),
            measurementType = bytes[2].toUnsignedInt(),
            errorCode = bytes[3].toUnsignedInt(),
            payloadHex = bytes.toHexString()
        )
    }

    fun parseSettingsResponse(bytes: ByteArray): PolarPmdSettingsSummary? {
        if (bytes.size < 5) {
            return null
        }
        if (bytes[0].toUnsignedInt() != ResponseFrameId) {
            return null
        }
        if (bytes[1] != OpcodeGetSettings || bytes[3].toUnsignedInt() != 0) {
            return null
        }

        val measurementType = bytes[2].toUnsignedInt()
        return parseSettingsPayload(bytes, measurementType, 4)
            ?: parseSettingsPayload(bytes, measurementType, 5)
    }

    fun decodeEcgFrame(bytes: ByteArray): PolarPmdEcgFrameSummary? {
        if (bytes.size < PmdHeaderSize || bytes[0] != MeasurementTypeEcg) {
            return null
        }
        val payloadLength = bytes.size - PmdHeaderSize
        if (payloadLength <= 0 || payloadLength % EcgBytesPerSample != 0) {
            return null
        }

        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var first: Int? = null
        var count = 0
        var offset = PmdHeaderSize
        while (offset < bytes.size) {
            var raw = bytes[offset].toUnsignedInt() or
                (bytes[offset + 1].toUnsignedInt() shl 8) or
                (bytes[offset + 2].toUnsignedInt() shl 16)
            if ((raw and 0x0080_0000) != 0) {
                raw = raw or -0x0100_0000
            }
            if (first == null) {
                first = raw
            }
            min = minOf(min, raw)
            max = maxOf(max, raw)
            count += 1
            offset += EcgBytesPerSample
        }

        return PolarPmdEcgFrameSummary(
            timestampNs = readTimestampNs(bytes),
            sampleCount = count,
            minMicroVolts = min,
            maxMicroVolts = max,
            firstMicroVolts = first ?: 0
        )
    }

    fun decodeAccFrame(bytes: ByteArray): PolarPmdAccFrameSummary? {
        if (bytes.size < PmdHeaderSize || bytes[0] != MeasurementTypeAcc) {
            return null
        }
        val frameType = bytes[9].toUnsignedInt()
        val compressed = (frameType and 0x80) != 0
        val frameTypeBase = frameType and 0x7F
        val samples = if (!compressed && frameTypeBase == 0x01) {
            decodeUncompressedAcc(bytes)
        } else {
            decodeCompressedAcc(bytes)
        }
        val first = samples.firstOrNull() ?: return null
        return PolarPmdAccFrameSummary(
            timestampNs = readTimestampNs(bytes),
            sampleCount = samples.size,
            firstXmg = first.x,
            firstYmg = first.y,
            firstZmg = first.z,
            compressed = compressed || frameTypeBase != 0x01
        )
    }

    private fun buildStartRequest(
        measurementType: Byte,
        sampleRate: Int,
        resolution: Int,
        rangeG: Int?
    ): ByteArray {
        val bytes = mutableListOf(
            OpcodeStartStream,
            measurementType,
            SettingTypeSampleRate.toByte(),
            1.toByte(),
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            SettingTypeResolution.toByte(),
            1.toByte(),
            (resolution and 0xFF).toByte(),
            ((resolution shr 8) and 0xFF).toByte()
        )
        if (rangeG != null) {
            bytes += SettingTypeRange.toByte()
            bytes += 1.toByte()
            bytes += (rangeG and 0xFF).toByte()
            bytes += ((rangeG shr 8) and 0xFF).toByte()
        }
        return bytes.toByteArray()
    }

    private fun parseSettingsPayload(
        bytes: ByteArray,
        measurementType: Int,
        offset: Int
    ): PolarPmdSettingsSummary? {
        if (bytes.size <= offset) {
            return null
        }

        val sampleRates = mutableListOf<Int>()
        val resolutions = mutableListOf<Int>()
        val ranges = mutableListOf<Int>()
        var index = offset
        while (index + 1 < bytes.size) {
            val settingType = bytes[index++].toUnsignedInt()
            val count = bytes[index++].toUnsignedInt()
            if (index + (count * 2) > bytes.size) {
                break
            }
            repeat(count) {
                val value = bytes[index].toUnsignedInt() or (bytes[index + 1].toUnsignedInt() shl 8)
                index += 2
                when (settingType) {
                    SettingTypeSampleRate -> sampleRates += value
                    SettingTypeResolution -> resolutions += value
                    SettingTypeRange -> ranges += value
                }
            }
        }

        return PolarPmdSettingsSummary(
            measurementType = measurementType,
            sampleRates = sampleRates,
            resolutions = resolutions,
            ranges = ranges
        ).takeIf { it.hasAny }
    }

    private fun readTimestampNs(bytes: ByteArray): Long {
        return ByteBuffer.wrap(bytes, 1, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
    }

    private fun decodeUncompressedAcc(bytes: ByteArray): List<AccSample> {
        val payloadLength = bytes.size - PmdHeaderSize
        if (payloadLength <= 0 || payloadLength % AccBytesPerUncompressedSample != 0) {
            return emptyList()
        }
        val samples = mutableListOf<AccSample>()
        var offset = PmdHeaderSize
        while (offset < bytes.size) {
            samples += AccSample(
                x = bytes.readInt16Le(offset),
                y = bytes.readInt16Le(offset + 2),
                z = bytes.readInt16Le(offset + 4)
            )
            offset += AccBytesPerUncompressedSample
        }
        return samples
    }

    private fun decodeCompressedAcc(bytes: ByteArray): List<AccSample> {
        if (bytes.size < 16) {
            return emptyList()
        }
        val samples = mutableListOf<AccSample>()
        var previousX = bytes.readInt16Le(10)
        var previousY = bytes.readInt16Le(12)
        var previousZ = bytes.readInt16Le(14)
        samples += AccSample(previousX, previousY, previousZ)

        val deltaBitWidth = 16
        val bitsPerSample = deltaBitWidth * 3
        val totalBits = (bytes.size - 16) * 8
        val deltaSampleCount = totalBits / bitsPerSample
        var bitOffset = 0
        repeat(deltaSampleCount) {
            val dx = readSignedBits(bytes, 16, bitOffset, deltaBitWidth)
            bitOffset += deltaBitWidth
            val dy = readSignedBits(bytes, 16, bitOffset, deltaBitWidth)
            bitOffset += deltaBitWidth
            val dz = readSignedBits(bytes, 16, bitOffset, deltaBitWidth)
            bitOffset += deltaBitWidth
            previousX = (previousX + dx).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            previousY = (previousY + dy).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            previousZ = (previousZ + dz).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples += AccSample(previousX, previousY, previousZ)
        }
        return samples
    }

    private fun readSignedBits(bytes: ByteArray, startByteOffset: Int, bitOffset: Int, bitWidth: Int): Int {
        var bytePos = startByteOffset + (bitOffset / 8)
        var bitInByte = bitOffset % 8
        var value = 0L
        var bitsRead = 0

        while (bitsRead < bitWidth && bytePos < bytes.size) {
            val bitsAvailable = 8 - bitInByte
            val bitsToRead = minOf(bitsAvailable, bitWidth - bitsRead)
            val mask = (1 shl bitsToRead) - 1
            val bits = (bytes[bytePos].toUnsignedInt() shr bitInByte) and mask
            value = value or (bits.toLong() shl bitsRead)
            bitsRead += bitsToRead
            bytePos += 1
            bitInByte = 0
        }

        if (bitWidth < 32 && (value and (1L shl (bitWidth - 1))) != 0L) {
            value = value or (-1L shl bitWidth)
        }
        return value.toInt()
    }

    private data class AccSample(val x: Int, val y: Int, val z: Int)

    private const val PmdHeaderSize = 10
    private const val EcgBytesPerSample = 3
    private const val AccBytesPerUncompressedSample = 6
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

private fun ByteArray.readInt16Le(offset: Int): Int {
    val raw = this[offset].toUnsignedInt() or (this[offset + 1].toUnsignedInt() shl 8)
    return raw.toShort().toInt()
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toUnsignedInt()) }
