package io.github.mesmerprism.rustyxr.companion.android.transport

class StubLslBridge : LslBridge {
    override suspend fun publishTwinCommand(actionId: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Integration hook queued: $actionId.",
            detail = "This standalone repo ships a preview bridge only. Wire your own telemetry or command backend here."
        )
    }

    override suspend fun publishConfig(profileId: String, packageId: String): TransportResult {
        return TransportResult(
            kind = TransportKind.Stub,
            summary = "Preset broadcast prepared for $profileId -> $packageId.",
            detail = "This standalone repo does not bundle a live preset broadcast backend."
        )
    }
}


