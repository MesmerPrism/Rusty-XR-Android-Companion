package io.github.mesmerprism.rustyxr.companion.android.agent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.mesmerprism.rustyxr.companion.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgentCommandActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val gate = AgentCommandGate(applicationContext)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(ExtraAllowDevSession, false)) {
            gate.enableFor(DebugSessionMs)
        }

        val snapshot = gate.snapshot()
        if (!snapshot.active) {
            scope.launch {
                val command = intent.getStringExtra(AgentCommandRunner.ExtraCommand).orEmpty()
                val progress = AgentCommandProgressWriter.create(
                    filesDir = applicationContext.filesDir,
                    externalFilesDir = getExternalFilesDir(null),
                    command = command.ifBlank { "denied" }
                )
                progress.event(
                    "command_denied",
                    JSONObject().apply {
                        put("active", false)
                        put("enabledUntilUtc", snapshot.enabledUntilUtc ?: JSONObject.NULL)
                    }
                )
                val report = deniedReport(command, snapshot)
                report.put("progress", progress.pathsJson())
                val reportFile = AgentCommandReportWriter(applicationContext.filesDir, getExternalFilesDir(null))
                    .write(command.ifBlank { "denied" }, report)
                gate.recordReportPath(reportFile.absolutePath)
                Log.w(Tag, "Agent command denied. report=${reportFile.absolutePath}")
                finish()
            }
            return
        }

        if (intent.getBooleanExtra(AgentCommandRunner.ExtraDisplayReceiver, false)) {
            launchDisplayedCommand()
            return
        }

        launchCommand(receiverDisplaySurface = null)
    }

    private fun launchDisplayedCommand() {
        val root = FrameLayout(this)
        val surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0x66000000)
                textSize = 14f
                text = "Rusty XR Q2Q receiver"
                setPadding(16, 10, 16, 10)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        setContentView(root)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                launchCommand(receiverDisplaySurface = holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
        })
    }

    private fun launchCommand(receiverDisplaySurface: Surface?) {
        if (commandStarted) {
            return
        }
        commandStarted = true
        scope.launch {
            val command = intent.getStringExtra(AgentCommandRunner.ExtraCommand)
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
            val progress = AgentCommandProgressWriter.create(
                filesDir = applicationContext.filesDir,
                externalFilesDir = getExternalFilesDir(null),
                command = command.ifBlank { "missing" }
            )
            progress.event(
                "command_accepted",
                JSONObject().apply {
                    put("displayReceiver", intent.getBooleanExtra(AgentCommandRunner.ExtraDisplayReceiver, false))
                }
            )
            val report = runCatching {
                AgentCommandRunner(applicationContext, receiverDisplaySurface, progress).run(intent)
            }
                .getOrElse { throwable ->
                    JSONObject().apply {
                        put("schemaVersion", "rusty.xr.android-companion.agent-command.v1")
                        put("command", command.ifBlank { "missing" })
                        put("overall", "failed")
                        put("summary", "Agent command failed.")
                        put("detail", throwable.message ?: throwable.javaClass.simpleName)
                        put("startedAtUtc", Instant.now().toString())
                        put("endedAtUtc", Instant.now().toString())
                    }
            }
            report.put("progress", progress.pathsJson())
            progress.event(
                "command_report_ready",
                JSONObject().apply {
                    put("overall", report.optString("overall", "unknown"))
                }
            )
            val reportFile = AgentCommandReportWriter(applicationContext.filesDir, getExternalFilesDir(null))
                .write(command.ifBlank { "missing" }, report)
            AgentCommandGate(applicationContext).recordReportPath(reportFile.absolutePath)
            progress.event(
                "command_report_written",
                JSONObject().apply {
                    put("report", reportFile.absolutePath)
                }
            )
            Log.i(
                Tag,
                "Agent command completed. command=$command overall=${report.optString("overall")} report=${reportFile.absolutePath}"
            )
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun deniedReport(
        command: String,
        snapshot: AgentCommandGateSnapshot
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", "rusty.xr.android-companion.agent-command.v1")
        put("command", command.ifBlank { "missing" })
        put("overall", "failed")
        put("summary", "Agent command mode is disabled.")
        put(
            "detail",
            "Open the phone app and enable Agent Command Mode before invoking PC-driven commands."
        )
        put("active", false)
        put("enabledUntilUtc", snapshot.enabledUntilUtc ?: JSONObject.NULL)
        put("startedAtUtc", Instant.now().toString())
        put("endedAtUtc", Instant.now().toString())
    }

    companion object {
        const val ExtraAllowDevSession = "allow_dev_session"

        private const val Tag = "RustyXrAgentCommand"
        private const val DebugSessionMs = 60L * 60L * 1000L
    }
}

private class AgentCommandReportWriter(
    private val filesDir: File,
    private val externalFilesDir: File?
) {
    suspend fun write(command: String, report: JSONObject): File = withContext(Dispatchers.IO) {
        val outputDir = File(externalFilesDir ?: filesDir, "agent-commands").apply { mkdirs() }
        val stamp = FileStamp.format(Instant.now())
        val safeCommand = command
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "command" }
        val reportFile = File(outputDir, "agent_command_${stamp}_$safeCommand.json")
        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        File(outputDir, "latest.json").writeText(report.toString(2), Charsets.UTF_8)
        reportFile
    }

    private companion object {
        val FileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
    }
}
