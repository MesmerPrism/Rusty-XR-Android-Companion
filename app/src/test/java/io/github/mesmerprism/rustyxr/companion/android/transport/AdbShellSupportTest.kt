package io.github.mesmerprism.rustyxr.companion.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbShellSupportTest {
    @Test
    fun parsesForegroundPackageFromResumedActivity() {
        val output = """
            Stack #1:
              mResumedActivity: ActivityRecord{6f4c2a u0 com.oculus.browser/.BrowserActivity t58}
        """.trimIndent()

        assertEquals("com.oculus.browser", AdbShellSupport.parseForegroundPackage(output))
    }

    @Test
    fun parsesForegroundPackageFromTopResumedActivity() {
        val output = """
            topResumedActivity=ActivityRecord{77da2d4 u0 com.example.quest/.MainActivity t91}
        """.trimIndent()

        assertEquals("com.example.quest", AdbShellSupport.parseForegroundPackage(output))
    }

    @Test
    fun returnsNullWhenForegroundPackageCannotBeParsed() {
        assertNull(AdbShellSupport.parseForegroundPackage("no activity records here"))
    }

    @Test
    fun buildsExplicitLaunchCommand() {
        val command = AdbShellSupport.buildExplicitLaunchCommand("com.example/.Main", ::quote)
        assertEquals("am start -W -n [com.example/.Main]", command)
    }

    @Test
    fun buildsExplicitLaunchCommandWithRuntimeExtras() {
        val command = AdbShellSupport.buildExplicitLaunchCommand(
            component = "com.example/.Main",
            quote = ::quote,
            extras = mapOf(
                "rustyxr.source" to "headset-camera",
                "rustyxr.camera" to "false",
                "rustyxr.cameraWidth" to "1280",
                "rustyxr.renderScale" to "0.75"
            )
        )

        assertEquals(
            "am start -W -n [com.example/.Main] --ez [rustyxr.camera] false --ei [rustyxr.cameraWidth] 1280 --ef [rustyxr.renderScale] 0.75 --es [rustyxr.source] [headset-camera]",
            command
        )
    }

    @Test
    fun buildsMainLauncherLaunchCommandWithRuntimeExtras() {
        val command = AdbShellSupport.buildMainLauncherLaunchCommand(
            packageId = "com.example",
            quote = ::quote,
            extras = mapOf("rustyxr.example" to "quest-minimal-apk")
        )

        assertEquals(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p [com.example] --es [rustyxr.example] [quest-minimal-apk]",
            command
        )
    }

    @Test
    fun buildsOpenUrlCommandWithBrowserPackage() {
        val command = AdbShellSupport.buildOpenUrlCommand(
            url = "https://example.com/path?q=1",
            browserPackageId = "com.oculus.browser",
            quote = ::quote
        )

        assertEquals(
            "am start -W -a android.intent.action.VIEW -d [https://example.com/path?q=1] -p [com.oculus.browser]",
            command
        )
    }

    @Test
    fun parsesInstalledPackages() {
        val packages = AdbShellSupport.parseInstalledPackages(
            """
                package:com.oculus.browser
                package:com.example.one
                package:com.example.one
            """.trimIndent()
        )

        assertEquals(listOf("com.oculus.browser", "com.example.one"), packages)
    }

    private fun quote(value: String): String = "[$value]"
}
