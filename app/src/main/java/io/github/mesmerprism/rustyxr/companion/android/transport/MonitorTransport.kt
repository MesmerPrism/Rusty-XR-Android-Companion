package io.github.mesmerprism.rustyxr.companion.android.transport

import kotlinx.coroutines.flow.Flow

data class MonitorSubscription(
    val streamName: String,
    val streamType: String,
    val channelIndex: Int
)

data class MonitorReading(
    val status: String,
    val detail: String,
    val streamName: String,
    val streamType: String,
    val channelIndex: Int,
    val value: Float? = null,
    val sampleRateHz: Float = 0f
)

interface MonitorTransport {
    fun monitor(subscription: MonitorSubscription): Flow<MonitorReading>
}
