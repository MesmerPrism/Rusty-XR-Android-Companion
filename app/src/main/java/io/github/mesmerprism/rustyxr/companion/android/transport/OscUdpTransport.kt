package io.github.mesmerprism.rustyxr.companion.android.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class OscUdpTransport(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    suspend fun send(
        host: String,
        port: Int,
        message: OscMessage
    ): OscSendReceipt = withContext(dispatcher) {
        require(host.isNotBlank()) { "OSC host is required." }
        require(port in 1..65535) { "OSC port must be between 1 and 65535." }
        val packetBytes = OscCodec.encode(message)
        val address = InetAddress.getByName(host.trim())
        DatagramSocket().use { socket ->
            val packet = DatagramPacket(packetBytes, packetBytes.size, address, port)
            socket.send(packet)
        }
        OscSendReceipt(
            host = host.trim(),
            port = port,
            sizeBytes = packetBytes.size,
            message = message
        )
    }

    fun listen(port: Int): Flow<OscInboundPacket> = flow {
        require(port in 1..65535) { "OSC listen port must be between 1 and 65535." }
        DatagramSocket(port).use { socket ->
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (currentCoroutineContext().isActive) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val packetBytes = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                val sourceHost = datagram.address.hostAddress ?: datagram.address.hostName
                val decoded = runCatching { OscCodec.decode(packetBytes) }
                emit(
                    decoded.fold(
                        onSuccess = { message ->
                            OscInboundPacket(
                                status = "OSC packet received.",
                                detail = message.summary(),
                                sourceHost = sourceHost,
                                sourcePort = datagram.port,
                                sizeBytes = datagram.length,
                                receivedAtMillis = nowMs(),
                                message = message
                            )
                        },
                        onFailure = { throwable ->
                            OscInboundPacket(
                                status = "OSC parse failed.",
                                detail = throwable.message ?: "Incoming UDP payload was not a supported OSC message.",
                                sourceHost = sourceHost,
                                sourcePort = datagram.port,
                                sizeBytes = datagram.length,
                                receivedAtMillis = nowMs()
                            )
                        }
                    )
                )
            }
        }
    }.flowOn(dispatcher)

    private companion object {
        private const val MAX_PACKET_BYTES = 65507
        private const val RECEIVE_TIMEOUT_MS = 500
    }
}
