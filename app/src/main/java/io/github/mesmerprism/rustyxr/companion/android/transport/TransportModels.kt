package io.github.mesmerprism.rustyxr.companion.android.transport

enum class TransportKind {
    Stub,
    Success,
    Partial,
    Error
}

data class TransportResult(
    val kind: TransportKind,
    val summary: String,
    val detail: String = "",
    val endpoint: String? = null,
    val packageId: String? = null,
    val items: List<String> = emptyList(),
    val networkSummary: String = "",
    val networkDetail: String = ""
)

