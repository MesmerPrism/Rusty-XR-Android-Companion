package io.github.mesmerprism.rustyxr.companion.android.transport

import java.util.Locale

internal object AdbShellSupport {
    fun buildForceStopCommand(
        packageId: String,
        quote: (String) -> String
    ): String {
        return "am force-stop ${quote(packageId)}"
    }

    fun buildExplicitLaunchCommand(
        component: String,
        quote: (String) -> String,
        extras: Map<String, String> = emptyMap()
    ): String {
        return "am start -W -n ${quote(component)}${buildAmStartExtras(extras, quote)}"
    }

    fun buildMainLauncherLaunchCommand(
        packageId: String,
        quote: (String) -> String,
        extras: Map<String, String> = emptyMap()
    ): String {
        return "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${quote(packageId)}${buildAmStartExtras(extras, quote)}"
    }

    fun buildMonkeyLaunchCommand(
        packageId: String,
        quote: (String) -> String
    ): String {
        return "monkey -p ${quote(packageId)} -c android.intent.category.LAUNCHER 1"
    }

    fun buildOpenUrlCommand(
        url: String,
        browserPackageId: String?,
        quote: (String) -> String
    ): String {
        val packageArg = browserPackageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { " -p ${quote(it)}" }
            .orEmpty()
        return "am start -W -a android.intent.action.VIEW -d ${quote(url)}$packageArg"
    }

    fun parseForegroundPackage(output: String): String? {
        val patterns = listOf(
            Regex("""(?:topResumedActivity|mResumedActivity|ResumedActivity)[^A-Za-z0-9_\.]+([A-Za-z0-9_\.]+)/(?:[A-Za-z0-9_\.$]+)"""),
            Regex("""ActivityRecord\{[^}]*\s([A-Za-z0-9_\.]+)/(?:[A-Za-z0-9_\.$]+)"""),
            Regex("""TaskRecord\{[^}]*A=([A-Za-z0-9_\.]+)\s""")
        )
        return patterns.asSequence()
            .mapNotNull { pattern -> pattern.find(output)?.groupValues?.getOrNull(1) }
            .firstOrNull()
    }

    fun parseInstalledPackages(output: String): List<String> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun buildAmStartExtras(
        extras: Map<String, String>,
        quote: (String) -> String
    ): String {
        if (extras.isEmpty()) {
            return ""
        }

        val parts = extras.entries
            .asSequence()
            .sortedBy { it.key }
            .mapNotNull { (key, value) -> buildAmStartExtra(key, value, quote) }
            .toList()

        return if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
    }

    private fun buildAmStartExtra(
        key: String,
        value: String,
        quote: (String) -> String
    ): String? {
        val cleanedKey = key.trim()
        if (cleanedKey.isEmpty()) {
            return null
        }

        return when {
            value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> {
                "--ez ${quote(cleanedKey)} ${value.lowercase(Locale.US)}"
            }
            value.toIntOrNull() != null -> "--ei ${quote(cleanedKey)} ${value.toInt()}"
            value.toLongOrNull() != null -> "--el ${quote(cleanedKey)} ${value.toLong()}"
            value.toFloatOrNull() != null -> "--ef ${quote(cleanedKey)} ${value.toFloat()}"
            else -> "--es ${quote(cleanedKey)} ${quote(value)}"
        }
    }
}
