package io.github.mesmerprism.rustyxr.companion.android.transport

interface LslBridge {
    suspend fun publishTwinCommand(actionId: String): TransportResult
    suspend fun publishConfig(profileId: String, packageId: String): TransportResult
}


