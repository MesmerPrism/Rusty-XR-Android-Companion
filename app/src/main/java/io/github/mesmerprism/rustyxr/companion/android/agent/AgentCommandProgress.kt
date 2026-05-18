package io.github.mesmerprism.rustyxr.companion.android.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

interface AgentCommandProgressSink {
    fun event(name: String, details: JSONObject = JSONObject())
}

object NoopAgentCommandProgressSink : AgentCommandProgressSink {
    override fun event(name: String, details: JSONObject) {
    }
}

class AgentCommandProgressWriter private constructor(
    private val outputDir: File,
    private val command: String,
    val eventsFile: File,
    val currentFile: File
) : AgentCommandProgressSink {
    @Synchronized
    override fun event(name: String, details: JSONObject) {
        outputDir.mkdirs()
        val event = JSONObject().apply {
            put("schemaVersion", SchemaVersion)
            put("command", command)
            put("event", name)
            put("timestampUtc", Instant.now().toString())
            put("details", details)
        }
        eventsFile.appendText(event.toString() + "\n", Charsets.UTF_8)
        currentFile.writeText(event.toString(2), Charsets.UTF_8)
    }

    fun pathsJson(): JSONObject = JSONObject().apply {
        put("eventsJsonl", eventsFile.absolutePath)
        put("currentJson", currentFile.absolutePath)
    }

    companion object {
        private const val SchemaVersion = "rusty.xr.android-companion.agent-command-progress.v1"

        suspend fun create(
            filesDir: File,
            externalFilesDir: File?,
            command: String
        ): AgentCommandProgressWriter = withContext(Dispatchers.IO) {
            val outputDir = File(externalFilesDir ?: filesDir, "agent-commands").apply { mkdirs() }
            val stamp = FileStamp.format(Instant.now())
            val safeCommand = command
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
                .ifBlank { "command" }
            AgentCommandProgressWriter(
                outputDir = outputDir,
                command = safeCommand,
                eventsFile = File(outputDir, "agent_command_${stamp}_${safeCommand}.events.jsonl"),
                currentFile = File(outputDir, "current.json")
            )
        }

        private val FileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
    }
}
