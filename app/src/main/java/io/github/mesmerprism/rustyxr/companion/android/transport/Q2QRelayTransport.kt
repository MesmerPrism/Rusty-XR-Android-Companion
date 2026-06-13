package io.github.mesmerprism.rustyxr.companion.android.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import io.github.mesmerprism.rustyxr.companion.android.agent.AgentCommandProgressSink
import io.github.mesmerprism.rustyxr.companion.android.agent.NoopAgentCommandProgressSink
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
import java.util.concurrent.CountDownLatch
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
    val channel: String,
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
    val sourceMode: String,
    val cameraId: String,
    val cameraFacing: String,
    val sameCameraToEyes: Boolean,
    val streamMagic: String,
    val qualityProfile: String,
    val label: String
)

class Q2QRelayTransport(
    private val applicationContext: Context? = null,
    private val receiverDisplaySurface: Surface? = null,
    receiverDisplayEye: String = "",
    private val progressSink: AgentCommandProgressSink = NoopAgentCommandProgressSink
) {
    private val normalizedReceiverDisplayEye = receiverDisplayEye.trim().lowercase(Locale.US).ifBlank { "left" }

    suspend fun run(request: Q2QRelayRequest): JSONObject = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val tasks = buildLaneTasks(request)
        if (tasks.isEmpty()) {
            error("q2q-relay mode `${request.mode}` did not produce any lanes.")
        }
        progressSink.event(
            "q2q_run_started",
            JSONObject().apply {
                put("mode", request.mode)
                put("channel", normalizedChannel(request.channel))
                put("sourceMode", normalizeSourceMode(request.sourceMode))
                put("durationMs", request.durationMs)
                put("connectTimeoutMs", request.connectTimeoutMs)
            }
        )

        val executor = Executors.newFixedThreadPool(tasks.size)
        val results = JSONArray()
        try {
            val futures = tasks.map { executor.submit(it) }
            futures.forEach { future ->
                val lanes = runCatching {
                    if (usesUnboundedWait(request)) {
                        future.get()
                    } else {
                        val laneTimeoutMs = request.durationMs.toLong() + (request.connectTimeoutMs * 2L) + 10_000L
                        future.get(laneTimeoutMs, TimeUnit.MILLISECONDS)
                    }
                }.getOrElse { throwable ->
                    future.cancel(true)
                    listOf(
                        Q2QLaneResult.failed(
                            role = "unknown",
                            eye = "unknown",
                            sessionId = "",
                            error = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                        )
                    )
                }
                lanes.forEach { lane -> results.put(lane.toJson()) }
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
            put("channel", normalizedChannel(request.channel))
            put("tls", request.tls)
            put("sendSessionId", request.sendSessionId)
            put("receiveSessionId", request.receiveSessionId)
            put("eyes", JSONArray(request.eyes))
            put("durationMs", request.durationMs)
            put("connectTimeoutMs", request.connectTimeoutMs)
            put("width", request.width)
            put("height", request.height)
            put("bitrateBps", request.bitrateBps)
            put("frameRateHz", request.frameRateHz)
            put("sourceMode", normalizeSourceMode(request.sourceMode))
            put("cameraId", request.cameraId.ifBlank { JSONObject.NULL })
            put("cameraFacing", normalizedCameraFacing(request.cameraFacing))
            put("sameCameraToEyes", request.sameCameraToEyes)
            put("qualityProfile", normalizedQualityProfile(request.qualityProfile))
            put("startedUnixMs", startedAt)
            put("endedUnixMs", System.currentTimeMillis())
            put("lanes", results)
        }.also { report ->
            progressSink.event(
                "q2q_run_completed",
                JSONObject().apply {
                    put("overall", report.optString("overall", "unknown"))
                    put("laneCount", results.length())
                }
            )
        }
    }

    private fun buildLaneTasks(request: Q2QRelayRequest): List<Callable<List<Q2QLaneResult>>> {
        val mode = request.mode.lowercase(Locale.US)
        val tasks = ArrayList<Callable<List<Q2QLaneResult>>>()
        val send = mode == "sender" || mode == "send" || mode == "duplex" || mode == "two-way"
        val receive = mode == "receiver" || mode == "receive" || mode == "duplex" || mode == "two-way"
        if (send) {
            if (normalizeSourceMode(request.sourceMode) == SourceModeCamera2) {
                val eyes = if (request.sameCameraToEyes) request.eyes else request.eyes.distinct()
                tasks.add(Callable { runCameraSenderLanes(request, request.sendSessionId, eyes) })
            } else {
                request.eyes.forEach { eye ->
                    tasks.add(Callable { listOf(runSyntheticSenderLane(request, request.sendSessionId, eye)) })
                }
            }
        }
        if (receive) {
            request.eyes.forEach { eye ->
                tasks.add(Callable { listOf(runReceiverLane(request, request.receiveSessionId, eye)) })
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
            val streamMagic = Q2QStreamFraming.normalizeWriteMagic(request.streamMagic)
            val connection = connectRelay(request, "sender", sessionId, eye)
            socket = connection.socket
            val output = socket.getOutputStream()
            bytesWritten += writeStreamHeader(
                output = output,
                width = request.width,
                height = request.height,
                streamMagic = streamMagic,
                metadata = streamMetadata(
                    source = "android_phone_synthetic_mediacodec_surface",
                    sourceMode = "synthetic_surface",
                    eye = eye,
                    contentWidth = request.width,
                    contentHeight = request.height,
                    streamMagic = streamMagic
                )
            )

            encoder = MediaCodec.createEncoderByType(MimeH264)
            configureEncoder(encoder, Size(request.width, request.height), request.bitrateBps, request.frameRateHz)
            surface = encoder.createInputSurface()
            encoder.start()
            requestSyncFrame(encoder)

            val deadlineNs = captureDeadlineNs(request.durationMs)
            var frameIndex = 0
            var firstVideoPacketLogged = false
            while (beforeDeadline(deadlineNs) && !Thread.currentThread().isInterrupted) {
                val frameStartNs = SystemClock.elapsedRealtimeNanos()
                drawSyntheticFrame(surface, frameIndex, request.width, request.height, eye)
                val stats = drainEncoder(encoder, output, endOfStream = false)
                bytesWritten += stats.bytes
                packetCount += stats.packets
                videoPacketCount += stats.videoPackets
                codecConfigPacketCount += stats.codecConfigPackets
                if (!firstVideoPacketLogged && stats.videoPackets > 0) {
                    firstVideoPacketLogged = true
                    progressSink.event(
                        "sender_first_video_packet",
                        JSONObject().apply {
                            put("sourceMode", "synthetic_surface")
                            put("eye", eye)
                            put("width", request.width)
                            put("height", request.height)
                            put("frameRateHz", request.frameRateHz)
                        }
                    )
                }
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
                streamMagic = streamMagic,
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

    private fun runCameraSenderLanes(
        request: Q2QRelayRequest,
        sessionId: String,
        eyes: List<String>
    ): List<Q2QLaneResult> {
        val startedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val laneStates = eyes.ifEmpty { listOf("left", "right") }
            .map { eye -> CameraSenderLaneState(eye = eye, sessionId = sessionId) }
        var cameraThread: HandlerThread? = null
        var cameraDevice: CameraDevice? = null
        var cameraSession: CameraCaptureSession? = null
        var encoder: MediaCodec? = null
        var surface: Surface? = null

        return try {
            val context = applicationContext
                ?: throw IllegalStateException("Camera source requires an Android application context.")
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Android CAMERA permission is not granted.")
            }
            val manager = context.getSystemService(CameraManager::class.java)
                ?: throw IllegalStateException("CameraManager is unavailable.")
            val selection = selectCamera(
                manager = manager,
                requestedCameraId = request.cameraId,
                requestedFacing = request.cameraFacing,
                requestedWidth = request.width,
                requestedHeight = request.height,
                frameRateHz = request.frameRateHz,
                qualityProfile = request.qualityProfile
            )
            progressSink.event(
                "camera_selected",
                JSONObject().apply {
                    put("cameraId", selection.cameraId)
                    put("lensFacing", selection.lensFacing)
                    put("width", selection.size.width)
                    put("height", selection.size.height)
                    put("fpsRange", selection.fpsRange?.toString() ?: JSONObject.NULL)
                    put("qualityProfile", normalizedQualityProfile(request.qualityProfile))
                    put("selectedReason", selection.selectedReason)
                }
            )

            val streamMagic = Q2QStreamFraming.normalizeWriteMagic(request.streamMagic)
            for (lane in laneStates) {
                val connection = connectRelay(request, "sender", sessionId, lane.eye)
                lane.socket = connection.socket
                lane.relayAck = connection.ack
                lane.output = connection.socket.getOutputStream()
                lane.bytesWritten += writeStreamHeader(
                    output = lane.output!!,
                    width = selection.size.width,
                    height = selection.size.height,
                    streamMagic = streamMagic,
                    metadata = streamMetadata(
                        source = "android_phone_camera2_mediacodec_surface",
                        sourceMode = SourceModeCamera2,
                        eye = lane.eye,
                        contentWidth = selection.size.width,
                        contentHeight = selection.size.height,
                        cameraId = selection.cameraId,
                        lensFacing = selection.lensFacing,
                        selectedReason = selection.selectedReason,
                        duplicatedCameraToEyes = laneStates.size > 1,
                        streamMagic = streamMagic
                    )
                )
            }
            val activeEncoder = MediaCodec.createEncoderByType(MimeH264)
            encoder = activeEncoder
            configureEncoder(activeEncoder, selection.size, request.bitrateBps, request.frameRateHz)
            val inputSurface = activeEncoder.createInputSurface()
            surface = inputSurface
            activeEncoder.start()
            requestSyncFrame(activeEncoder)
            progressSink.event(
                "camera_encoder_started",
                JSONObject().apply {
                    put("width", selection.size.width)
                    put("height", selection.size.height)
                    put("bitrateBps", request.bitrateBps)
                    put("frameRateHz", request.frameRateHz)
                }
            )

            cameraThread = HandlerThread("RustyXrQ2QPhoneCamera").apply { start() }
            val cameraHandler = Handler(cameraThread.looper)
            cameraDevice = openCamera(manager, selection.cameraId, cameraHandler)
            cameraSession = configureCameraSession(
                cameraDevice,
                listOf(inputSurface),
                cameraHandler
            )
            val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(inputSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                selection.fpsRange?.let { range -> set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range) }
            }.build()
            cameraSession.setRepeatingRequest(captureRequest, null, cameraHandler)

            progressSink.event(
                "camera_repeating_request_started",
                JSONObject().apply {
                    put("cameraId", selection.cameraId)
                    put("sessionId", sessionId)
                    put("eyes", JSONArray(laneStates.map { lane -> lane.eye }))
                }
            )

            val deadlineNs = captureDeadlineNs(request.durationMs)
            while (beforeDeadline(deadlineNs) && !Thread.currentThread().isInterrupted) {
                drainEncoderToLanes(activeEncoder, laneStates, endOfStream = false)
                Thread.sleep(5)
            }

            runCatching { cameraSession.stopRepeating() }
            runCatching { activeEncoder.signalEndOfInputStream() }
            drainEncoderToLanes(activeEncoder, laneStates, endOfStream = true)
            laneStates.forEach { lane ->
                lane.output?.flush()
                runCatching { lane.socket?.shutdownOutput() }
            }

            laneStates.map { lane ->
                Q2QLaneResult(
                    role = "sender",
                    eye = lane.eye,
                    sessionId = sessionId,
                    ok = lane.bytesWritten > 0L && lane.videoPacketCount > 0,
                    state = "closed",
                    relayAck = lane.relayAck ?: JSONObject(),
                    bytes = lane.bytesWritten,
                    packetCount = lane.packetCount,
                    videoPacketCount = lane.videoPacketCount,
                    codecConfigPacketCount = lane.codecConfigPacketCount,
                    declaredPacketCount = 0,
                    width = selection.size.width,
                    height = selection.size.height,
                    streamMagic = streamMagic,
                    durationMs = elapsedMs(startedElapsedNs),
                    error = ""
                )
            }
        } catch (throwable: Throwable) {
            laneStates.map { lane ->
                Q2QLaneResult.failed(
                    role = "sender",
                    eye = lane.eye,
                    sessionId = sessionId,
                    error = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                    durationMs = elapsedMs(startedElapsedNs),
                    bytes = lane.bytesWritten,
                    packetCount = lane.packetCount,
                    videoPacketCount = lane.videoPacketCount,
                    codecConfigPacketCount = lane.codecConfigPacketCount
                )
            }
        } finally {
            runCatching { cameraSession?.stopRepeating() }
            closeAutoQuietly(cameraSession)
            closeAutoQuietly(cameraDevice)
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            laneStates.forEach { lane ->
                closeQuietly(lane.socket)
            }
            cameraThread?.quitSafely()
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
            socket.soTimeout = readTimeoutMs(request)
            val input = DataInputStream(socket.getInputStream())
            val header = readStreamHeader(input)
            bytesRead += header.wireBytes
            declaredPacketCount = header.packetCount
            width = header.width
            height = header.height
            progressSink.event(
                "receiver_stream_header_received",
                JSONObject().apply {
                    put("eye", eye)
                    put("sessionId", sessionId)
                    put("width", width)
                    put("height", height)
                    put("streamMagic", header.magic)
                    put("declaredPacketCount", declaredPacketCount)
                }
            )
            val displaySurface = receiverDisplaySurface
            if (displaySurface != null && eye.lowercase(Locale.US) == normalizedReceiverDisplayEye) {
                return runDisplayedReceiverLane(
                    request = request,
                    sessionId = sessionId,
                    eye = eye,
                    socket = socket,
                    connection = connection,
                    input = input,
                    header = header,
                    initialBytesRead = bytesRead,
                    startedElapsedNs = startedElapsedNs,
                    displaySurface = displaySurface
                )
            }

            val deadlineNs = receiveDeadlineNs(request)
            var firstPacketLogged = false
            while (!Thread.currentThread().isInterrupted &&
                (declaredPacketCount == 0 || packetCount < declaredPacketCount) &&
                beforeDeadline(deadlineNs)) {
                val packet = try {
                    readPacket(input, header.schemaVersion)
                } catch (timeout: SocketTimeoutException) {
                    break
                } catch (eof: EOFException) {
                    break
                }
                bytesRead += packet.wireBytes
                packetCount++
                if (!firstPacketLogged) {
                    firstPacketLogged = true
                    progressSink.event(
                        "receiver_first_packet",
                        JSONObject().apply {
                            put("eye", eye)
                            put("sessionId", sessionId)
                        }
                    )
                }
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
                streamMagic = header.magic,
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

    private fun runDisplayedReceiverLane(
        request: Q2QRelayRequest,
        sessionId: String,
        eye: String,
        socket: Socket,
        connection: RelayConnection,
        input: DataInputStream,
        header: StreamHeader,
        initialBytesRead: Long,
        startedElapsedNs: Long,
        displaySurface: Surface
    ): Q2QLaneResult {
        var decoder: MediaCodec? = null
        var bytesRead = initialBytesRead
        var packetCount = 0
        var videoPacketCount = 0
        var codecConfigPacketCount = 0
        var decodedFrameCount = 0
        return try {
            decoder = MediaCodec.createDecoderByType(MimeH264)
            decoder.configure(
                MediaFormat.createVideoFormat(MimeH264, header.width, header.height),
                displaySurface,
                null,
                0
            )
            decoder.start()
            socket.soTimeout = readTimeoutMs(request)
            val deadlineNs = receiveDeadlineNs(request)
            var firstDecodedFrameLogged = false
            while (!Thread.currentThread().isInterrupted &&
                (header.packetCount == 0 || packetCount < header.packetCount) &&
                beforeDeadline(deadlineNs)) {
                val packet = try {
                    readPacket(input, header.schemaVersion, keepPayload = true)
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
                decodedFrameCount += queueAndDrainDecoder(decoder, packet, render = true)
                if (!firstDecodedFrameLogged && decodedFrameCount > 0) {
                    firstDecodedFrameLogged = true
                    progressSink.event(
                        "receiver_first_frame_decoded",
                        JSONObject().apply {
                            put("eye", eye)
                            put("sessionId", sessionId)
                            put("width", header.width)
                            put("height", header.height)
                            put("streamMagic", header.magic)
                        }
                    )
                }
            }
            queueDecoderEndOfStream(decoder)
            decodedFrameCount += drainDecoderOutput(decoder, render = true, endOfStream = true)
            Q2QLaneResult(
                role = "receiver",
                eye = eye,
                sessionId = sessionId,
                ok = bytesRead > 0L && packetCount > 0 && decodedFrameCount > 0,
                state = "closed",
                relayAck = connection.ack,
                bytes = bytesRead,
                packetCount = packetCount,
                videoPacketCount = videoPacketCount,
                codecConfigPacketCount = codecConfigPacketCount,
                declaredPacketCount = header.packetCount,
                width = header.width,
                height = header.height,
                streamMagic = header.magic,
                durationMs = elapsedMs(startedElapsedNs),
                error = ""
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }

    private fun connectRelay(
        request: Q2QRelayRequest,
        role: String,
        sessionId: String,
        eye: String
    ): RelayConnection {
        progressSink.event(
            "relay_connect_started",
            JSONObject().apply {
                put("role", role)
                put("sessionId", sessionId)
                put("eye", eye)
                put("channel", normalizedChannel(request.channel))
                put("host", request.relayHost)
                put("port", request.relayPort)
            }
        )
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
            put("channel", normalizedChannel(request.channel))
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
        progressSink.event(
            "relay_ack_received",
            JSONObject().apply {
                put("role", role)
                put("sessionId", sessionId)
                put("eye", eye)
                put("channel", normalizedChannel(request.channel))
                put("ack", ack)
            }
        )
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

    private fun writeStreamHeader(
        output: OutputStream,
        width: Int,
        height: Int,
        streamMagic: String,
        metadata: JSONObject
    ): Long {
        val metadataBytes = metadata.toString().toByteArray(StandardCharsets.UTF_8)
        output.write(streamMagic.toByteArray(StandardCharsets.US_ASCII))
        writeU32(output, StreamSchemaVersion)
        writeU32(output, CodecH264)
        writeU32(output, width)
        writeU32(output, height)
        writeU32(output, 0)
        writeU32(output, metadataBytes.size)
        output.write(metadataBytes)
        output.flush()
        return 32L + metadataBytes.size
    }

    private fun streamMetadata(
        source: String,
        sourceMode: String,
        eye: String,
        contentWidth: Int,
        contentHeight: Int,
        cameraId: String = "",
        lensFacing: String = "",
        selectedReason: String = "",
        duplicatedCameraToEyes: Boolean = false,
        streamMagic: String = ""
    ): JSONObject = JSONObject().apply {
        put("source", source)
        put("sourceMode", sourceMode)
        put("sourceDeviceClass", "android-phone")
        put("eye", eye)
        put("projectionMetadataReady", false)
        put("contentWidth", contentWidth)
        put("contentHeight", contentHeight)
        put("contentAspectRatio", contentWidth.toDouble() / contentHeight.coerceAtLeast(1).toDouble())
        put("rasterOrientation", "top-left-origin-y-down")
        put("contentMappingIntent", "map-raster-through-camera-projection")
        if (cameraId.isNotBlank()) {
            put("cameraId", cameraId)
        }
        if (lensFacing.isNotBlank()) {
            put("lensFacing", lensFacing)
        }
        if (selectedReason.isNotBlank()) {
            put("selectedReason", selectedReason)
        }
        if (duplicatedCameraToEyes) {
            put("duplicatedCameraToEyes", true)
        }
        if (streamMagic.isNotBlank()) {
            put("streamMagic", streamMagic)
        }
    }

    private fun selectCamera(
        manager: CameraManager,
        requestedCameraId: String,
        requestedFacing: String,
        requestedWidth: Int,
        requestedHeight: Int,
        frameRateHz: Int,
        qualityProfile: String
    ): CameraSelection {
        val desiredFacing = normalizedCameraFacing(requestedFacing)
        val profile = normalizedQualityProfile(qualityProfile)
        val nativeMax = profile == QualityCameraNativeMax || requestedWidth <= 0 || requestedHeight <= 0
        val requestedAspect = if (nativeMax) 0.0 else requestedWidth.toDouble() / requestedHeight.coerceAtLeast(1).toDouble()
        val requestedArea = if (nativeMax) 0L else requestedWidth.toLong() * requestedHeight.toLong()
        val candidates = ArrayList<CameraSelection>()
        manager.cameraIdList.forEach { cameraId ->
            if (requestedCameraId.isNotBlank() && cameraId != requestedCameraId) {
                return@forEach
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val lensFacing = lensFacingLabel(characteristics.get(CameraCharacteristics.LENS_FACING))
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = outputSizesForEncoder(map)
            if (sizes.isEmpty()) {
                return@forEach
            }
            val selectedSize = if (nativeMax) {
                sizes.maxWithOrNull(
                    compareBy<Size> { size -> size.width.toLong() * size.height.toLong() }
                        .thenBy { size -> size.width }
                        .thenBy { size -> size.height }
                )
            } else {
                sizes.minWithOrNull(
                    compareBy<Size> { size ->
                        kotlin.math.abs((size.width.toDouble() / size.height.coerceAtLeast(1).toDouble()) - requestedAspect)
                    }.thenBy { size ->
                        kotlin.math.abs(size.width.toLong() * size.height.toLong() - requestedArea)
                    }
                )
            } ?: return@forEach
            val fpsRange = chooseFpsRange(characteristics, frameRateHz, profile)
            val facingPenalty = if (requestedCameraId.isNotBlank() || lensFacing == desiredFacing) 0 else 1
            val selectedArea = selectedSize.width.toLong() * selectedSize.height.toLong()
            val sizeScore = if (nativeMax) -selectedArea else kotlin.math.abs(selectedArea - requestedArea)
            val selectedReason = if (requestedCameraId.isNotBlank()) {
                "requested_camera_id"
            } else if (nativeMax && facingPenalty == 0) {
                "camera_native_max_$desiredFacing"
            } else if (nativeMax) {
                "camera_native_max_fallback_first_encoder_capable_camera"
            } else if (facingPenalty == 0) {
                "requested_facing_$desiredFacing"
            } else {
                "fallback_first_encoder_capable_camera"
            }
            candidates.add(
                CameraSelection(
                    cameraId = cameraId,
                    size = selectedSize,
                    lensFacing = lensFacing,
                    fpsRange = fpsRange,
                    selectedReason = selectedReason,
                    score = facingPenalty * 1_000_000_000_000L + sizeScore
                )
            )
        }
        return candidates.minByOrNull { it.score }
            ?: throw IllegalStateException(
                if (requestedCameraId.isNotBlank()) {
                    "Requested camera `$requestedCameraId` was not available for MediaCodec output."
                } else {
                    "No camera supports MediaCodec output surfaces."
                }
            )
    }

    private fun outputSizesForEncoder(map: StreamConfigurationMap?): List<Size> {
        if (map == null) {
            return emptyList()
        }
        val mediaCodecSizes = runCatching { map.getOutputSizes(MediaCodec::class.java)?.toList() }
            .getOrNull()
            .orEmpty()
        if (mediaCodecSizes.isNotEmpty()) {
            return mediaCodecSizes
        }
        return runCatching { map.getOutputSizes(MediaRecorder::class.java)?.toList() }
            .getOrNull()
            .orEmpty()
    }

    private fun chooseFpsRange(
        characteristics: CameraCharacteristics,
        frameRateHz: Int,
        qualityProfile: String
    ): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        if (qualityProfile == QualityCameraNativeMax) {
            return ranges.maxWithOrNull(
                compareBy<Range<Int>> { range -> range.upper }
                    .thenBy { range -> range.lower }
            )
        }
        val requested = frameRateHz.coerceAtLeast(1)
        return ranges.minWithOrNull(
            compareBy<Range<Int>> { range -> if (range.contains(requested)) 0 else 1 }
                .thenBy { range -> kotlin.math.abs(range.upper - requested) }
                .thenBy { range -> kotlin.math.abs(range.lower - requested) }
        )
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(manager: CameraManager, cameraId: String, handler: Handler): CameraDevice {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        var errorMessage = ""
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opened = camera
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    errorMessage = "Camera disconnected while opening."
                    camera.close()
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    errorMessage = "Camera open error $error."
                    camera.close()
                    latch.countDown()
                }
            },
            handler
        )
        if (!latch.await(CameraOpenTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out opening camera $cameraId.")
        }
        return opened ?: throw IllegalStateException(errorMessage.ifBlank { "Camera $cameraId did not open." })
    }

    @Suppress("DEPRECATION")
    private fun configureCameraSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler
    ): CameraCaptureSession {
        val latch = CountDownLatch(1)
        var configured: CameraCaptureSession? = null
        var errorMessage = ""
        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    configured = session
                    latch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    errorMessage = "Camera capture session configure failed."
                    session.close()
                    latch.countDown()
                }
            },
            handler
        )
        if (!latch.await(CameraSessionTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out configuring camera capture session.")
        }
        return configured ?: throw IllegalStateException(errorMessage.ifBlank { "Camera capture session was not configured." })
    }

    private fun normalizeSourceMode(value: String): String =
        when (value.trim().lowercase(Locale.US)) {
            "camera", "camera2", "phone-camera", "phone_camera", "camera2_surface" -> SourceModeCamera2
            else -> "synthetic_surface"
        }

    private fun normalizedChannel(value: String): String =
        when (value.trim().lowercase(Locale.US)) {
            "control", "agent-control", "coordination" -> "control"
            else -> "media"
        }

    private fun normalizedQualityProfile(value: String): String =
        when (value.trim().lowercase(Locale.US)) {
            "", "native", "max", "camera-native", "camera_native", "camera-native-max", "camera_native_max" ->
                QualityCameraNativeMax
            "requested", "requested-size", "requested_size" -> "requested-size"
            else -> value.trim().lowercase(Locale.US)
        }

    private fun normalizedCameraFacing(value: String): String =
        when (value.trim().lowercase(Locale.US)) {
            "front", "selfie" -> "front"
            "external" -> "external"
            else -> "back"
        }

    private fun lensFacingLabel(value: Int?): String =
        when (value) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }

    private fun usesUnboundedWait(request: Q2QRelayRequest): Boolean =
        request.durationMs == 0 || request.connectTimeoutMs == 0

    private fun captureDeadlineNs(durationMs: Int): Long =
        if (durationMs <= 0) Long.MAX_VALUE else SystemClock.elapsedRealtimeNanos() + durationMs.toLong() * 1_000_000L

    private fun receiveDeadlineNs(request: Q2QRelayRequest): Long =
        if (usesUnboundedWait(request)) {
            Long.MAX_VALUE
        } else {
            SystemClock.elapsedRealtimeNanos() +
                (request.durationMs.toLong() + request.connectTimeoutMs.toLong() + 5000L) * 1_000_000L
        }

    private fun beforeDeadline(deadlineNs: Long): Boolean =
        deadlineNs == Long.MAX_VALUE || SystemClock.elapsedRealtimeNanos() < deadlineNs

    private fun readTimeoutMs(request: Q2QRelayRequest): Int =
        if (usesUnboundedWait(request)) 0 else request.durationMs + request.connectTimeoutMs + 5000

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

    private fun drainEncoderToLanes(
        encoder: MediaCodec,
        lanes: List<CameraSenderLaneState>,
        endOfStream: Boolean
    ) {
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
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
                val sourceElapsedNs = SystemClock.elapsedRealtimeNanos()
                val sourceUnixNs = System.currentTimeMillis() * 1_000_000L
                lanes.forEach { lane ->
                    val output = lane.output ?: error("Camera relay lane ${lane.eye} has no output stream.")
                    val wireBytes = writeEncodedPacket(
                        output = output,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags,
                        payload = payload,
                        sourceElapsedNs = sourceElapsedNs,
                        sourceUnixNs = sourceUnixNs
                    )
                    lane.bytesWritten += wireBytes
                    lane.packetCount++
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        lane.codecConfigPacketCount++
                    } else {
                        lane.videoPacketCount++
                        if (!lane.firstVideoPacketLogged) {
                            lane.firstVideoPacketLogged = true
                            progressSink.event(
                                "sender_first_video_packet",
                                JSONObject().apply {
                                    put("sourceMode", SourceModeCamera2)
                                    put("eye", lane.eye)
                                    put("packetBytes", payload.size)
                                }
                            )
                        }
                    }
                }
            }
            val reachedEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            encoder.releaseOutputBuffer(status, false)
            if (reachedEos) {
                break
            }
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
                stats.bytes += writeEncodedPacket(
                    output = output,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                    payload = payload
                )
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

    private fun writeEncodedPacket(
        output: OutputStream,
        presentationTimeUs: Long,
        flags: Int,
        payload: ByteArray,
        sourceElapsedNs: Long = SystemClock.elapsedRealtimeNanos(),
        sourceUnixNs: Long = System.currentTimeMillis() * 1_000_000L
    ): Long {
        writeU64(output, presentationTimeUs)
        writeU32(output, flags)
        writeU32(output, payload.size)
        writeU64(output, sourceElapsedNs)
        writeU64(output, sourceUnixNs)
        output.write(payload)
        output.flush()
        return 32L + payload.size
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

    private fun queueAndDrainDecoder(decoder: MediaCodec, packet: ReceivedPacket, render: Boolean): Int {
        val payload = packet.payload ?: return drainDecoderOutput(decoder, render, endOfStream = false)
        val deadlineNs = SystemClock.elapsedRealtimeNanos() + 100_000_000L
        while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtimeNanos() < deadlineNs) {
            val inputIndex = decoder.dequeueInputBuffer(DecoderTimeoutUs)
            if (inputIndex < 0) {
                drainDecoderOutput(decoder, render, endOfStream = false)
                continue
            }
            val inputBuffer = decoder.getInputBuffer(inputIndex)
                ?: error("Decoder input buffer $inputIndex is unavailable.")
            if (payload.size > inputBuffer.capacity()) {
                error("Encoded packet exceeds decoder input buffer: ${payload.size} > ${inputBuffer.capacity()}.")
            }
            inputBuffer.clear()
            inputBuffer.put(payload)
            decoder.queueInputBuffer(inputIndex, 0, payload.size, packet.ptsUs, packet.flags)
            return drainDecoderOutput(decoder, render, endOfStream = false)
        }
        return drainDecoderOutput(decoder, render, endOfStream = false)
    }

    private fun queueDecoderEndOfStream(decoder: MediaCodec) {
        val deadlineNs = SystemClock.elapsedRealtimeNanos() + 100_000_000L
        while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtimeNanos() < deadlineNs) {
            val inputIndex = decoder.dequeueInputBuffer(DecoderTimeoutUs)
            if (inputIndex >= 0) {
                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    private fun drainDecoderOutput(
        decoder: MediaCodec,
        render: Boolean,
        endOfStream: Boolean
    ): Int {
        val info = MediaCodec.BufferInfo()
        var decodedFrames = 0
        var emptyPolls = 0
        while (!Thread.currentThread().isInterrupted) {
            val outputIndex = decoder.dequeueOutputBuffer(info, DecoderTimeoutUs)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || emptyPolls++ > 20) {
                    break
                }
                continue
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex < 0) {
                continue
            }
            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            val codecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val shouldRender = render && !codecConfig && !eos
            decoder.releaseOutputBuffer(outputIndex, shouldRender)
            if (shouldRender) {
                decodedFrames++
            }
            if (eos) {
                break
            }
        }
        return decodedFrames
    }

    private fun readStreamHeader(input: DataInputStream): StreamHeader {
        val magic = ByteArray(8)
        input.readFully(magic)
        val magicText = String(magic, StandardCharsets.US_ASCII)
        if (!Q2QStreamFraming.isSupportedReadMagic(magicText)) {
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
        return StreamHeader(magicText, schemaVersion, width, height, packetCount, 32L + metadataBytes)
    }

    private fun readPacket(input: DataInputStream, schemaVersion: Int, keepPayload: Boolean = false): ReceivedPacket {
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
        val payload = if (keepPayload) {
            ByteArray(sizeBytes).also { input.readFully(it) }
        } else {
            skipFully(input, sizeBytes)
            null
        }
        return ReceivedPacket(ptsUs, flags, wireBytes, payload)
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

    private fun closeAutoQuietly(closeable: AutoCloseable?) {
        runCatching { closeable?.close() }
    }

    private data class RelayConnection(val socket: Socket, val ack: JSONObject)

    private data class CameraSelection(
        val cameraId: String,
        val size: Size,
        val lensFacing: String,
        val fpsRange: Range<Int>?,
        val selectedReason: String,
        val score: Long
    )

    private data class CameraSenderLaneState(
        val eye: String,
        val sessionId: String,
        var socket: Socket? = null,
        var relayAck: JSONObject? = null,
        var output: OutputStream? = null,
        var encoder: MediaCodec? = null,
        var surface: Surface? = null,
        var bytesWritten: Long = 0L,
        var packetCount: Int = 0,
        var videoPacketCount: Int = 0,
        var codecConfigPacketCount: Int = 0,
        var firstVideoPacketLogged: Boolean = false
    ) {
        fun add(stats: PacketStats) {
            bytesWritten += stats.bytes
            packetCount += stats.packets
            videoPacketCount += stats.videoPackets
            codecConfigPacketCount += stats.codecConfigPackets
        }
    }

    private data class PacketStats(
        var bytes: Long = 0L,
        var packets: Int = 0,
        var videoPackets: Int = 0,
        var codecConfigPackets: Int = 0
    )

    private data class StreamHeader(
        val magic: String,
        val schemaVersion: Int,
        val width: Int,
        val height: Int,
        val packetCount: Int,
        val wireBytes: Long
    )

    private data class ReceivedPacket(
        val ptsUs: Long,
        val flags: Int,
        val wireBytes: Long,
        val payload: ByteArray? = null
    )

    private companion object {
        private const val RelayHelloSchema = "rusty.xr.q2q.relay.hello.v1"
        private const val RelayAckSchema = "rusty.xr.q2q.relay.ack.v1"
        private const val StreamSchemaVersion = 3
        private const val CodecH264 = 1
        private const val MimeH264 = "video/avc"
        private const val SourceModeCamera2 = "camera2_surface"
        private const val QualityCameraNativeMax = "camera-native-max"
        private const val EncoderDrainTimeoutUs = 10_000L
        private const val DecoderTimeoutUs = 10_000L
        private const val CameraOpenTimeoutMs = 5_000L
        private const val CameraSessionTimeoutMs = 5_000L
        private const val MaxHelloBytes = 16 * 1024
        private const val MaxPacketBytes = 8 * 1024 * 1024
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
    val streamMagic: String,
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
        if (streamMagic.isNotBlank()) {
            put("streamMagic", streamMagic)
        }
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
            height: Int = 0,
            streamMagic: String = ""
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
            streamMagic = streamMagic,
            durationMs = durationMs,
            error = error
        )
    }
}
