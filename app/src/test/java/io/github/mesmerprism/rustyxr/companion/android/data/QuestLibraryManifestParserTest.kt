package io.github.mesmerprism.rustyxr.companion.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestLibraryManifestParserTest {
    @Test
    fun parsesRustyXrQuestAppCatalog() {
        val manifest = RustyXrCatalogParser.parse(
            jsonText = """
                {
                  "schemaVersion": "rusty.xr.quest-app-catalog.v1",
                  "apps": [
                    {
                      "id": "rusty-xr-quest-composite-layer",
                      "label": "Rusty XR Quest Composite Layer",
                      "packageName": "com.example.rustyxr.composite",
                      "activityName": ".CompositeLayerActivity",
                      "apkFile": "rusty-xr-quest-composite-layer-debug.apk",
                      "description": "Public sample"
                    }
                  ],
                  "deviceProfiles": [
                    {
                      "id": "perf-smoke-test",
                      "label": "Performance smoke test",
                      "properties": [
                        { "key": "debug.oculus.cpuLevel", "value": "3" },
                        { "key": "debug.oculus.gpuLevel", "value": "3" }
                      ]
                    },
                    {
                      "id": "quest-makepad-mesh-replay",
                      "label": "Quest Makepad mesh replay",
                      "properties": [
                        { "key": "debug.rustyquest.makepad.mesh.replay.enabled", "value": "true" },
                        { "key": "debug.rustyquest.makepad.render.scale", "value": "0.9" }
                      ]
                    }
                  ],
                  "runtimeProfiles": [
                    {
                      "id": "synthetic-composite-layer",
                      "label": "Synthetic layer smoke test",
                      "values": {
                        "rustyxr.example": "quest-composite-layer-apk",
                        "rustyxr.camera": "false",
                        "rustyxr.cameraWidth": "1280"
                      }
                    }
                  ]
                }
            """.trimIndent(),
            source = CatalogSource.BundledPublicCatalog,
            sourcePath = "Catalogs/example.json"
        )

        assertEquals(QuestAppCatalogSchemaVersion, manifest.schemaVersion)
        assertEquals(CatalogSource.BundledPublicCatalog, manifest.source)
        assertEquals(1, manifest.apps.size)
        assertEquals("com.example.rustyxr.composite", manifest.apps.first().packageId)
        assertEquals("com.example.rustyxr.composite/.CompositeLayerActivity", manifest.apps.first().launchComponent)
        assertEquals(2, manifest.deviceProfiles.size)
        assertEquals("3", manifest.deviceProfiles.first().props["debug.oculus.cpuLevel"])
        assertEquals(
            "true",
            manifest.deviceProfiles[1].props["debug.rustyquest.makepad.mesh.replay.enabled"]
        )
        assertEquals(1, manifest.runtimeProfiles.size)
        assertEquals("false", manifest.runtimeProfiles.first().values["rustyxr.camera"])
        assertTrue(manifest.runtimeProfiles.first().matchesTarget(manifest.apps.first()))
    }

    @Test
    fun parsesModernLibraryManifest() {
        val manifest = QuestLibraryManifestParser.parse(
            jsonText = """
                {
                  "apps": [
                    {
                      "id": "browser",
                      "label": "Quest Browser",
                      "packageId": "com.oculus.browser",
                      "apkFile": "browser.apk",
                      "browserPackageId": "com.oculus.browser",
                      "tags": ["browser"]
                    },
                    {
                      "id": "app-one",
                      "label": "App One",
                      "packageId": "com.example.one",
                      "apkFile": "one.apk",
                      "launchComponent": "com.example.one/.MainActivity",
                      "description": "Primary app"
                    }
                  ],
                  "bundles": [
                    {
                      "id": "core",
                      "label": "Core",
                      "appIds": ["browser", "app-one"]
                    }
                  ]
                }
            """.trimIndent(),
            source = CatalogSource.StagedLibrary,
            sourcePath = "library.json"
        )

        assertEquals(CatalogSource.StagedLibrary, manifest.source)
        assertEquals(2, manifest.apps.size)
        assertEquals("browser", manifest.apps.first().id)
        assertEquals("com.oculus.browser", manifest.apps.first().resolvedBrowserPackageId())
        assertEquals(1, manifest.bundles.size)
        assertEquals(listOf("browser", "app-one"), manifest.bundles.first().appIds)
    }

    @Test
    fun parsesLegacyApkMap() {
        val manifest = QuestLibraryManifestParser.parse(
            jsonText = """
                {
                  "apks": [
                    {
                      "file": "legacy.apk",
                      "packageId": "com.example.legacy"
                    }
                  ]
                }
            """.trimIndent(),
            source = CatalogSource.BundledSample
        )

        assertEquals(1, manifest.apps.size)
        assertEquals("com.example.legacy", manifest.apps.first().id)
        assertTrue(manifest.bundles.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBundleWithUnknownAppId() {
        QuestLibraryManifestParser.parse(
            jsonText = """
                {
                  "apps": [
                    {
                      "id": "app-one",
                      "label": "App One",
                      "packageId": "com.example.one",
                      "apkFile": "one.apk"
                    }
                  ],
                  "bundles": [
                    {
                      "id": "broken",
                      "label": "Broken",
                      "appIds": ["missing-app"]
                    }
                  ]
                }
            """.trimIndent(),
            source = CatalogSource.StagedLibrary
        )
    }
}
