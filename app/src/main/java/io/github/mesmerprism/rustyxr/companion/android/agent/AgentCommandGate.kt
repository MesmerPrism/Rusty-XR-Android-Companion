package io.github.mesmerprism.rustyxr.companion.android.agent

import android.content.Context
import java.time.Instant

data class AgentCommandGateSnapshot(
    val enabledUntilEpochMs: Long,
    val lastReportPath: String?
) {
    val active: Boolean = enabledUntilEpochMs > System.currentTimeMillis()
    val enabledUntilUtc: String? = enabledUntilEpochMs
        .takeIf { it > 0L }
        ?.let { Instant.ofEpochMilli(it).toString() }
}

class AgentCommandGate(
    context: Context
) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun snapshot(): AgentCommandGateSnapshot =
        AgentCommandGateSnapshot(
            enabledUntilEpochMs = prefs.getLong(KeyEnabledUntilEpochMs, 0L),
            lastReportPath = prefs.getString(KeyLastReportPath, null)
        )

    fun enableFor(durationMs: Long): AgentCommandGateSnapshot {
        val until = System.currentTimeMillis() + durationMs.coerceIn(MinEnableMs, MaxEnableMs)
        prefs.edit()
            .putLong(KeyEnabledUntilEpochMs, until)
            .apply()
        return snapshot()
    }

    fun disable(): AgentCommandGateSnapshot {
        prefs.edit()
            .putLong(KeyEnabledUntilEpochMs, 0L)
            .apply()
        return snapshot()
    }

    fun recordReportPath(path: String) {
        prefs.edit()
            .putString(KeyLastReportPath, path)
            .apply()
    }

    private companion object {
        private const val PrefsName = "rusty_xr_agent_command_gate"
        private const val KeyEnabledUntilEpochMs = "enabled_until_epoch_ms"
        private const val KeyLastReportPath = "last_report_path"
        private const val MinEnableMs = 60_000L
        private const val MaxEnableMs = 60L * 60L * 1000L
    }
}
