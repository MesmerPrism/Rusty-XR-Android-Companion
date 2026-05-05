package io.github.mesmerprism.rustyxr.companion.android.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.mesmerprism.rustyxr.companion.android.transport.AdbUtility
import io.github.mesmerprism.rustyxr.companion.android.transport.LiveAdbTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.OscArgument
import io.github.mesmerprism.rustyxr.companion.android.transport.OscMessage
import io.github.mesmerprism.rustyxr.companion.android.transport.OscUdpTransport
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportKind
import io.github.mesmerprism.rustyxr.companion.android.transport.TransportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DebugQuestTransportSuiteActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ENDPOINT
        val oscPort = intent.getIntExtra(EXTRA_OSC_PORT, DEFAULT_OSC_PORT)
            .coerceIn(1, 65535)
        Log.i(TAG, "Starting debug Quest transport suite. endpoint=$endpoint oscPort=$oscPort")

        scope.launch {
            val report = runSuite(endpoint = endpoint, oscPort = oscPort)
            val reportFile = writeReport(report)
            Log.i(TAG, "Debug Quest transport suite completed. overall=${report.optString("overall")} report=${reportFile.absolutePath}")
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSuite(
        endpoint: String,
        oscPort: Int
    ): JSONObject {
        val steps = JSONArray()
        val transport = LiveAdbTransport(applicationContext)
        val startedAt = Instant.now()

        suspend fun runTransportStep(
            name: String,
            critical: Boolean = true,
            block: suspend () -> TransportResult
        ): TransportResult {
            val stepStartedAt = Instant.now()
            Log.i(TAG, "STEP start name=$name")
            val result = runCatching { block() }.getOrElse { throwable ->
                TransportResult(
                    kind = TransportKind.Error,
                    summary = "$name threw ${throwable.javaClass.simpleName}.",
                    detail = throwable.message ?: ""
                )
            }
            val step = JSONObject().apply {
                put("name", name)
                put("critical", critical)
                put("kind", result.kind.name)
                put("summary", result.summary)
                put("detail", result.detail)
                put("endpoint", result.endpoint ?: JSONObject.NULL)
                put("packageId", result.packageId ?: JSONObject.NULL)
                put("itemCount", result.items.size)
                put("itemsPreview", JSONArray(result.items.take(12)))
                put("startedAtUtc", stepStartedAt.toString())
                put("endedAtUtc", Instant.now().toString())
            }
            steps.put(step)
            Log.i(
                TAG,
                "STEP end name=$name kind=${result.kind.name} summary=${result.summary} endpoint=${result.endpoint.orEmpty()} package=${result.packageId.orEmpty()} items=${result.items.size}"
            )
            return result
        }

        val connect = runTransportStep("connect") {
            transport.connect(endpoint)
        }

        if (connect.kind == TransportKind.Success) {
            runTransportStep("wake") {
                transport.runUtility(AdbUtility.Wake)
            }
            runTransportStep("listInstalledPackages") {
                transport.runUtility(AdbUtility.ListInstalledPackages)
            }
            runTransportStep("queryForegroundPackage", critical = false) {
                transport.queryForegroundPackage()
            }
            runTransportStep("home") {
                transport.runUtility(AdbUtility.Home)
            }
            runTransportStep("back") {
                transport.runUtility(AdbUtility.Back)
            }
            runOscStep(endpoint = endpoint, oscPort = oscPort, steps = steps)
        } else {
            Log.i(TAG, "Skipping follow-up transport steps because connect failed.")
        }

        val overall = determineOverall(steps)
        return JSONObject().apply {
            put("schemaVersion", "rusty.xr.android-companion.debug-quest-suite.v1")
            put("overall", overall)
            put("endpoint", endpoint)
            put("oscPort", oscPort)
            put("startedAtUtc", startedAt.toString())
            put("endedAtUtc", Instant.now().toString())
            put("steps", steps)
        }
    }

    private suspend fun runOscStep(
        endpoint: String,
        oscPort: Int,
        steps: JSONArray
    ) {
        val host = endpoint.substringBefore(":")
        val stepStartedAt = Instant.now()
        Log.i(TAG, "STEP start name=oscUdpSend host=$host port=$oscPort")
        val result = runCatching {
            OscUdpTransport().send(
                host = host,
                port = oscPort,
                message = OscMessage(
                    address = "/rusty-xr/probe",
                    arguments = listOf(OscArgument.StringValue("phone-suite"))
                )
            )
        }

        val step = JSONObject().apply {
            put("name", "oscUdpSend")
            put("critical", false)
            put("kind", if (result.isSuccess) "Success" else "Error")
            put("summary", result.getOrNull()?.summary() ?: "OSC UDP send failed.")
            put("detail", result.getOrNull()?.detail() ?: (result.exceptionOrNull()?.message ?: ""))
            put("host", host)
            put("port", oscPort)
            put("startedAtUtc", stepStartedAt.toString())
            put("endedAtUtc", Instant.now().toString())
        }
        steps.put(step)
        Log.i(TAG, "STEP end name=oscUdpSend kind=${step.optString("kind")} summary=${step.optString("summary")}")
    }

    private fun determineOverall(steps: JSONArray): String {
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            if (step.optBoolean("critical") && step.optString("kind") == "Error") {
                return "failed"
            }
        }
        return "passed"
    }

    private suspend fun writeReport(report: JSONObject): File = withContext(Dispatchers.IO) {
        val baseDir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        val outputDir = File(baseDir, "diagnostics").apply { mkdirs() }
        val stamp = FILE_STAMP.format(Instant.now())
        File(outputDir, "debug_quest_suite_$stamp.json").apply {
            writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private companion object {
        private const val TAG = "RustyXrQuestSuite"
        private const val EXTRA_ENDPOINT = "endpoint"
        private const val EXTRA_OSC_PORT = "osc_port"
        private const val DEFAULT_ENDPOINT = "192.168.43.1:5555"
        private const val DEFAULT_OSC_PORT = 9000

        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
    }
}
