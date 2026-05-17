package io.github.mesmerprism.rustyxr.companion.android.transport

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class Q2QRelayRequest(
    val mode: String,
    val relayHost: String,
    val relayPort: Int,
    val token: String,
    val tls: Boolean,
    val insecureTls: Boolean,
    val serverName: String,
    val sendSessionId: String,
    val receiveSessionId: String,
    val eyes: List<String>,
    val durationMs: Int,
    val connectTimeoutMs: Int,
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val frameRateHz: Int,
    val label: String
)

class Q2QRelayTransport {
    suspend fun run(request: Q2QRelayRequest): JSONObject = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val tasks = buildLaneTasks(request)
        if (tasks.isEmpty()) {
            error("q2q-relay mode `${request.mode}` did not produce any lanes.")
        }

        val executor = Executors.newFixedThreadPool(tasks.size)
        val results = JSONArray()
        try {
            val futures = tasks.map { executor.submit(it) }
            val laneTimeoutMs = request.durationMs.toLong() + (request.connectTimeoutMs * 2L) + 10_000L
            futures.forEach { future ->
                val lane = runCatching {
                    future.get(laneTimeoutMs, TimeUnit.MILLISECONDS)
                }.getOrElse { throwable ->
                    future.cancel(true)
                    Q2QLaneResult.failed(
                        role = "unknown",
                        eye = "unknown",
                        sessionId = "",
                        error = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                    )
                }
                results.put(lane.toJson())
            }
        } finally {
            executor.shutdownNow()
        }

        JSONObject().apply {
            put("schemaVersion", "rusty.xr.android-companion.q2q-relay.v1")
            put("overall", if (allLanesPassed(results)) "passed" else "failed")
            put("mode", request.mode)
            put("relayHost", request.relayHost)
            put("relayPort", request.relayPort)
            put("tls", request.tls)
            put("sendSessionId", request.sendSessionId)
            put("receiveSessionId", request.receiveSessionId)
            put("eyes", JSONArray(request.eyes))
            put("durationMs", request.durationMs)
            put("width", request.width)
            put("height", request.height)
            put("bitrateBps", request.bitrateBps)
            put("frameRateHz", request.frameRateHz)
            put("startedUnixMs", startedAt)
            put("endedUnixMs", System.currentTimeMillis())
            put("lanes", results)
        }
    }

    private fun buildLaneTasks(request: Q2QRelayRequest): List<Callable<Q2QLaneResult>> {
        val mode = request.mode.lowercase(Locale.US)
        val tasks = ArrayList<Callable<Q2QLaneResult>>()
        val send = mode == "sender" || mode == "send" || mode == "duplex" || mode == "two-way"
        val receive = mode == "receiver" || mode == "receive" || mode == "duplex" || mode == "two-way"
        if (send) {
            request.eyes.forEach { eye ->
                tasks.add(Callable { runSyntheticSenderLane(request, request.sendSessionId, eye) })
            }
        }
        if (receive) {
            request.eyes.forEach { eye ->
                tasks.add(Callable { runReceiverLane(request, request.receiveSessionId, eye) })
            }
        }
        return tasks
    }

    private fun runSyntheticSenderLane(
        request: Q2QRelayRequest,
        sessionId: String,
        eye: String
    ): Q2QLaneResult {
        val startedElapsedNs = SystemClock.elapsedRealtimeNanos()
        var socket: Socket? = null
        var encoder: MediaCodec? = null
        var surface: Surface? = null
        var bytesWritten = 0L
        var packetCount = 0
        var videoPacketCount = 0
        var codecConfigPacketCount = 0
        return try {
            val connection = connectRelay(request, "sender", sessionId, eye)
            socket = connection.socket
            val output = socket.getOutputStream()
            bytesWritten += writeStreamHeader(output, request, eye)

            encoder = MediaCodec.createEncoderByType(MimeH264)
            configureEncoder(encoder, Size(request.width, request.height), request.bitrateBps, request.frameRateHz)
            surface = encoder.createInputSurface()
            encoder.start()
            requestSyncFrame(encoder)

            val deadlineNs = SystemClock.elapsedRealtimeNanos() + request.durationMs.toLong() * 1_000_000L
            var frameIndex = 0
            while (SystemClock.elapsedRealtimeNanos() < deadlineNs && !Thread.currentThread().isInterrupted) {
                val frameStartNs = SystemClock.elapsedRealtimeNanos()
                drawSyntheticFrame(surface, frameIndex, request.width, request.height, eye)
                val stats = drainEncoder(encoder, output, endOfStream = false)
                bytesWritten += stats.bytes
                packetCount += stats.packets
                videoPacketCount += stats.videoPackets
                codecConfigPacketCount += stats.codecConfigPackets
                sleepUntilFrameCadence(frameStartNs, request.frameRateHz)
                frameIndex++
            }
            encoder.signalEndOfInputStream()
            val tail = drainEncoder(encoder, output, endOfStream = true)
            bytesWritten += tail.bytes
            packetCount += tail.packets
            videoPacketCount += tail.videoPackets
            codecConfigPacketCount += tail.codecConfigPackets
            output.flush()
            runCatching { socket.shutdownOutput() }
            Q2QLaneResult(
                role = "sender",
                eye = eye,
                sessionId = sessionId,
                ok = bytesWritten > 0L && videoPacketCount > 0,
                state = "closed",
                relayAck = connection.ack,
                bytes = bytesWritten,
                packetCount = packetCount,
                videoPacketCount = videoPacketCount,
                codecConfigPacketCount = codecConfigPacketCount,
                declaredPacketCount = 0,
                width = request.width,
                height = request.height,
                durationMs = elapsedMs(startedElapsedNs),
                error = ""
            )
        } catch (throwable: Throwable) {
            Q2QLaneResult.failed(
                role = "sender",
                eye = eye,
                sessionId = sessionId,
                error = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                durationMs = elapsedMs(startedElapsedNs),
                bytes = bytesWritten,
                packetCount = packetCount,
                videoPacketCount = videoPacketCount,
                codecConfigPacketCount = codecConfigPacketCount
            )
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            closeQuietly(socket)
        }
    }

    private fun runReceiverLane(
        request: Q2QRelayRequest,
        sessionId: String,
        eye: String
    ): Q2QLaneResult {
        val startedElapsedNs = SystemClock.elapsedRealtimeNanos()
        var socket: Socket? = null
        var bytesRead = 0L
        var packetCount = 0
        var videoPacketCount = 0
        var codecConfigPacketCount = 0
        var declaredPacketCount = 0
        var width = 0
        var height = 0
        return try {
            val connection = connectRelay(request, "receiver", sessionId, eye)
            socket = connection.socket
            socket.soTimeout = request.durationMs + request.connectTimeoutMs + 5000
            val input = DataInputStream(socket.getInputStream())
            val header = readStreamHeader(input)
            bytesRead += header.wireBytes
            declaredPacketCount = header.packetCount
            width = header.width
            height = header.height

            val deadlineNs = SystemClock.elapsedRealtimeNanos() +
                (request.durationMs.toLong() + request.connectTimeoutMs.toLong() + 5000L) * 1_000_000L
            while (!Thread.currentThread().isInterrupted &&
                (declaredPacketCount == 0 || packetCount < declaredPacketCount) &&
                SystemClock.elapsedRealtimeNanos() < deadlineNs) {
                val packet = try {
                    readPacket(input, header.schemaVersion)
                } catch (timeout: SocketTimeoutException) {
                    break
                } catch (eof: EOFException) {
                    break
                }
                bytesRead += packet.wireBytes
                packetCount++
                if ((packet.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codecConfigPacketCount++
                } else {
                    videoPacketCount++
                }
            }
            Q2QLaneResult(
                role = "receiver",
                eye = eye,
                sessionId = sessionId,
                ok = bytesRead > 0L && packetCount > 0,
                state = "closed",
                relayAck = connection.ack,
                bytes = bytesRead,
                packetCount = packetCount,
                videoPacketCount = videoPacketCount,
                codecConfigPacketCount = codecConfigPacketCount,
                declaredPacketCount = declaredPacketCount,
                width = width,
                height = height,
                durationMs = elapsedMs(startedElapsedNs),
                error = ""
            )
        } catch (throwable: Throwable) {
            Q2QLaneResult.failed(
                role = "receiver",
                eye = eye,
                sessionId = sessionId,
                error = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                durationMs = elapsedMs(startedElapsedNs),
                bytes = bytesRead,
                packetCount = packetCount,
                videoPacketCount = videoPacketCount,
                codecConfigPacketCount = codecConfigPacketCount,
                declaredPacketCount = declaredPacketCount,
                width = width,
                height = height
            )
        } finally {
            closeQuietly(socket)
        }
    }

    private fun connectRelay(
        request: Q2QRelayRequest,
        role: String,
        sessionId: String,
        eye: String
    ): RelayConnection {
        val base = Socket()
        base.tcpNoDelay = true
        base.connect(InetSocketAddress(request.relayHost, request.relayPort), request.connectTimeoutMs)
        val socket = if (request.tls) {
            val factory = sslSocketFactory(request.insecureTls)
            val serverName = request.serverName.ifBlank { request.relayHost }
            factory.createSocket(base, serverName, request.relayPort, true) as Socket
        } else {
            base
        }
        socket.soTimeout = request.connectTimeoutMs
        val hello = JSONObject().apply {
            put("schema", RelayHelloSchema)
            put("role", role)
            put("session_id", sessionId)
            put("eye", eye)
            put("token", request.token)
            put("label", request.label.ifBlank { "android-phone-$role-$eye" })
            put("client_unix_ns", System.currentTimeMillis() * 1_000_000L)
        }
        socket.getOutputStream().write((hello.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        val ack = JSONObject(readLineLimited(socket.getInputStream(), MaxHelloBytes))
        if (ack.optString("schema") != RelayAckSchema || !ack.optBoolean("ok", false)) {
            closeQuietly(socket)
            error("Relay rejected $role $sessionId/$eye: ${ack.optString("message")}")
        }
        socket.soTimeout = 0
        return RelayConnection(socket, ack)
    }

    private fun sslSocketFactory(insecureTls: Boolean): SSLSocketFactory {
        if (!insecureTls) {
            return SSLSocketFactory.getDefault() as SSLSocketFactory
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(InsecureTrustManager), null)
        return context.socketFactory
    }

    private fun writeStreamHeader(output: OutputStream, request: Q2QRelayRequest, eye: String): Long {
        val metadata = JSONObject().apply {
            put("source", "android_phone_synthetic_mediacodec_surface")
            put("sourceMode", "synthetic_surface")
            put("sourceDeviceClass", "android-phone")
            put("eye", eye)
            put("projectionMetadataReady", false)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        output.write(StreamMagic.toByteArray(StandardCharsets.US_ASCII))
        writeU32(output, StreamSchemaVersion)
        writeU32(output, CodecH264)
        writeU32(output, request.width)
        writeU32(output, request.height)
        writeU32(output, 0)
        writeU32(output, metadata.size)
        output.write(metadata)
        output.flush()
        return 32L + metadata.size
    }

    private fun configureEncoder(encoder: MediaCodec, size: Size, bitrateBps: Int, frameRateHz: Int) {
        val first = MediaFormat.createVideoFormat(MimeH264, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRateHz)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        runCatching {
            encoder.configure(first, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.getOrElse {
            runCatching { encoder.reset() }
            val fallback = MediaFormat.createVideoFormat(MimeH264, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRateHz)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun requestSyncFrame(encoder: MediaCodec) {
        runCatching {
            encoder.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun drainEncoder(encoder: MediaCodec, output: OutputStream, endOfStream: Boolean): PacketStats {
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
        val stats = PacketStats()
        while (!Thread.currentThread().isInterrupted) {
            val status = encoder.dequeueOutputBuffer(info, EncoderDrainTimeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || emptyPolls++ > 50) {
                    break
                }
                continue
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || status < 0) {
                continue
            }
            val outputBuffer = encoder.getOutputBuffer(status)
            if (outputBuffer != null && info.size > 0) {
                val payload = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)
                outputBuffer.get(payload)
                writeU64(output, info.presentationTimeUs)
                writeU32(output, info.flags)
                writeU32(output, payload.size)
                writeU64(output, SystemClock.elapsedRealtimeNanos())
                writeU64(output, System.currentTimeMillis() * 1_000_000L)
                output.write(payload)
                output.flush()
                stats.bytes += 32L + payload.size
                stats.packets++
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    stats.codecConfigPackets++
                } else {
                    stats.videoPackets++
                }
            }
            val reachedEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            encoder.releaseOutputBuffer(status, false)
            if (reachedEos) {
                break
            }
        }
        return stats
    }

    private fun drawSyntheticFrame(surface: Surface, frameIndex: Int, width: Int, height: Int, eye: String) {
        val canvas: Canvas = surface.lockCanvas(null)
        try {
            val paint = Paint().apply {
                isAntiAlias = false
                style = Paint.Style.FILL
            }
            canvas.drawColor(Color.rgb(8, 8, 8))
            val barHeight = (height / 10).coerceAtLeast(24)
            val colors = intArrayOf(
                Color.WHITE,
                Color.YELLOW,
                Color.CYAN,
                Color.GREEN,
                Color.MAGENTA,
                Color.RED,
                Color.BLUE,
                Color.BLACK
            )
            val barWidth = (width / colors.size).coerceAtLeast(1)
            colors.forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect(
                    Rect(index * barWidth, 0, if (index == colors.lastIndex) width else (index + 1) * barWidth, barHeight),
                    paint
                )
            }

            val cell = (minOf(width, height) / 12).coerceAtLeast(16)
            val top = barHeight + 16
            var y = top
            while (y < height) {
                var x = 0
                while (x < width) {
                    val high = (((x / cell) + ((y - top) / cell)) and 1) == 0
                    paint.color = if (high) Color.rgb(224, 224, 224) else Color.rgb(32, 32, 32)
                    canvas.drawRect(Rect(x, y, minOf(width, x + cell), minOf(height, y + cell)), paint)
                    x += cell
                }
                y += cell
            }

            val marker = (minOf(width, height) / 8).coerceAtLeast(48)
            paint.color = if (eye == "right") Color.rgb(40, 90, 240) else Color.rgb(0, 180, 90)
            canvas.drawRect(Rect(0, 0, marker, marker), paint)
            paint.color = Color.BLACK
            paint.textSize = marker * 0.32f
            canvas.drawText(eye.uppercase(Locale.US), 8f, marker * 0.58f, paint)

            val motionWidth = (width / 8).coerceAtLeast(32)
            val motionLeft = if (width <= motionWidth) 0 else (frameIndex * 11) % (width - motionWidth)
            paint.color = Color.rgb(255, 255, 255)
            canvas.drawRect(Rect(motionLeft, height - marker, motionLeft + motionWidth, height), paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun readStreamHeader(input: DataInputStream): StreamHeader {
        val magic = ByteArray(8)
        input.readFully(magic)
        val magicText = String(magic, StandardCharsets.US_ASCII)
        if (magicText != StreamMagic) {
            error("Unexpected stream magic `$magicText`.")
        }
        val schemaVersion = input.readInt()
        val codecId = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        val packetCount = input.readInt()
        val metadataBytes = input.readInt()
        if (schemaVersion !in 1..3) {
            error("Unsupported stream schema version $schemaVersion.")
        }
        if (codecId != CodecH264) {
            error("Unsupported codec id $codecId.")
        }
        if (metadataBytes < 0 || metadataBytes > MaxHeaderMetadataBytes) {
            error("Invalid header metadata byte count $metadataBytes.")
        }
        skipFully(input, metadataBytes)
        return StreamHeader(schemaVersion, width, height, packetCount, 32L + metadataBytes)
    }

    private fun readPacket(input: DataInputStream, schemaVersion: Int): ReceivedPacket {
        val ptsUs = input.readLong()
        val flags = input.readInt()
        val sizeBytes = input.readInt()
        if (sizeBytes <= 0 || sizeBytes > MaxPacketBytes) {
            error("Invalid packet payload size $sizeBytes at pts $ptsUs.")
        }
        var wireBytes = 16L + sizeBytes
        if (schemaVersion >= 2) {
            input.readLong()
            input.readLong()
            wireBytes += 16L
        }
        skipFully(input, sizeBytes)
        return ReceivedPacket(flags, wireBytes)
    }

    private fun skipFully(input: InputStream, byteCount: Int) {
        val buffer = ByteArray(64 * 1024)
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) {
                throw EOFException("EOF while skipping $remaining stream bytes.")
            }
            remaining -= read
        }
    }

    private fun readLineLimited(input: InputStream, limit: Int): String {
        val line = ByteArrayOutputStream()
        while (line.size() < limit) {
            val next = input.read()
            if (next < 0) {
                break
            }
            if (next == '\n'.code) {
                return line.toString("UTF-8").trim()
            }
            if (next != '\r'.code) {
                line.write(next)
            }
        }
        error("Relay ack line missing or too large.")
    }

    private fun writeU32(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun writeU64(output: OutputStream, value: Long) {
        output.write(((value ushr 56) and 0xff).toInt())
        output.write(((value ushr 48) and 0xff).toInt())
        output.write(((value ushr 40) and 0xff).toInt())
        output.write(((value ushr 32) and 0xff).toInt())
        output.write(((value ushr 24) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write((value and 0xff).toInt())
    }

    private fun sleepUntilFrameCadence(frameStartNs: Long, frameRateHz: Int) {
        val frameIntervalNs = 1_000_000_000L / frameRateHz.coerceAtLeast(1)
        val remainingNs = frameStartNs + frameIntervalNs - SystemClock.elapsedRealtimeNanos()
        if (remainingNs > 0L) {
            Thread.sleep((remainingNs / 1_000_000L).coerceIn(1L, 50L))
        }
    }

    private fun elapsedMs(startedElapsedNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedElapsedNs) / 1_000_000L

    private fun allLanesPassed(results: JSONArray): Boolean {
        if (results.length() == 0) {
            return false
        }
        for (index in 0 until results.length()) {
            if (!results.getJSONObject(index).optBoolean("ok", false)) {
                return false
            }
        }
        return true
    }

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }

    private data class RelayConnection(val socket: Socket, val ack: JSONObject)

    private data class PacketStats(
        var bytes: Long = 0L,
        var packets: Int = 0,
        var videoPackets: Int = 0,
        var codecConfigPackets: Int = 0
    )

    private data class StreamHeader(
        val schemaVersion: Int,
        val width: Int,
        val height: Int,
        val packetCount: Int,
        val wireBytes: Long
    )

    private data class ReceivedPacket(val flags: Int, val wireBytes: Long)

    private companion object {
        private const val RelayHelloSchema = "rusty.xr.q2q.relay.hello.v1"
        private const val RelayAckSchema = "rusty.xr.q2q.relay.ack.v1"
        private const val StreamMagic = "RXYRVID1"
        private const val StreamSchemaVersion = 3
        private const val CodecH264 = 1
        private const val MimeH264 = "video/avc"
        private const val EncoderDrainTimeoutUs = 10_000L
        private const val MaxHelloBytes = 16 * 1024
        private const val MaxPacketBytes = 1024 * 1024
        private const val MaxHeaderMetadataBytes = 256 * 1024
    }
}

private object InsecureTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
    }

    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
    }

    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
        emptyArray()
}

private data class Q2QLaneResult(
    val role: String,
    val eye: String,
    val sessionId: String,
    val ok: Boolean,
    val state: String,
    val relayAck: JSONObject,
    val bytes: Long,
    val packetCount: Int,
    val videoPacketCount: Int,
    val codecConfigPacketCount: Int,
    val declaredPacketCount: Int,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val error: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", "rusty.xr.android-companion.q2q-relay.lane.v1")
        put("role", role)
        put("eye", eye)
        put("sessionId", sessionId)
        put("ok", ok)
        put("state", state)
        put("relayAck", relayAck)
        put("bytes", bytes)
        put("packetCount", packetCount)
        put("videoPacketCount", videoPacketCount)
        put("codecConfigPacketCount", codecConfigPacketCount)
        put("declaredPacketCount", declaredPacketCount)
        put("width", width)
        put("height", height)
        put("durationMs", durationMs)
        if (error.isNotBlank()) {
            put("error", error)
        }
    }

    companion object {
        fun failed(
            role: String,
            eye: String,
            sessionId: String,
            error: String,
            durationMs: Long = 0L,
            bytes: Long = 0L,
            packetCount: Int = 0,
            videoPacketCount: Int = 0,
            codecConfigPacketCount: Int = 0,
            declaredPacketCount: Int = 0,
            width: Int = 0,
            height: Int = 0
        ): Q2QLaneResult = Q2QLaneResult(
            role = role,
            eye = eye,
            sessionId = sessionId,
            ok = false,
            state = "failed",
            relayAck = JSONObject(),
            bytes = bytes,
            packetCount = packetCount,
            videoPacketCount = videoPacketCount,
            codecConfigPacketCount = codecConfigPacketCount,
            declaredPacketCount = declaredPacketCount,
            width = width,
            height = height,
            durationMs = durationMs,
            error = error
        )
    }
}
