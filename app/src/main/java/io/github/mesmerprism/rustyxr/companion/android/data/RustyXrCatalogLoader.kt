package io.github.mesmerprism.rustyxr.companion.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RustyXrCatalogLoader(
    private val context: Context
) {
    suspend fun load(): RustyXrCatalogs = withContext(Dispatchers.IO) {
        val libraryManifest = loadLibraryManifest()
        val runtimeProfiles = libraryManifest.runtimeProfiles.ifEmpty {
            parseHotloadProfiles(
                manifestJson = readAsset("HotloadProfiles/profiles.json"),
                availableFiles = context.assets.list("HotloadProfiles").orEmpty().toList()
            )
        }
        val deviceProfiles = libraryManifest.deviceProfiles.ifEmpty {
            parseDeviceProfiles(readAsset("DeviceProfiles/profiles.json"))
        }

        RustyXrCatalogs(
            source = libraryManifest.source,
            sourcePath = libraryManifest.sourcePath,
            apkTargets = libraryManifest.apps,
            apkBundles = libraryManifest.bundles,
            runtimeProfiles = runtimeProfiles,
            deviceProfiles = deviceProfiles
        )
    }

    private fun loadLibraryManifest(): QuestLibraryManifest {
        for (candidate in stagedCatalogCandidates()) {
            if (candidate.file.exists()) {
                return RustyXrCatalogParser.parse(
                    jsonText = candidate.file.readText(),
                    source = candidate.source,
                    sourcePath = candidate.file.absolutePath
                )
            }
        }

        val bundledPath = when {
            assetExists("Catalogs/rusty-xr-android-companion.example.catalog.json") -> {
                "Catalogs/rusty-xr-android-companion.example.catalog.json"
            }
            assetExists("APKs/library.json") -> "APKs/library.json"
            else -> "APKs/apk_map.json"
        }
        val source = if (bundledPath.startsWith("Catalogs/")) {
            CatalogSource.BundledPublicCatalog
        } else {
            CatalogSource.BundledSample
        }
        return RustyXrCatalogParser.parse(
            jsonText = readAsset(bundledPath),
            source = source,
            sourcePath = bundledPath
        )
    }

    private fun stagedCatalogCandidates(): List<CatalogCandidate> {
        val internal = context.filesDir
        val external = context.getExternalFilesDir(null)
        return buildList {
            listOfNotNull(external, internal).forEach { root ->
                add(CatalogCandidate(File(root, "catalogs/rusty-xr-android-companion.catalog.json"), CatalogSource.StagedPublicCatalog))
                add(CatalogCandidate(File(root, "catalogs/catalog.json"), CatalogSource.StagedPublicCatalog))
                add(CatalogCandidate(File(root, "apks/library.json"), CatalogSource.StagedLibrary))
            }
        }
    }

    private fun assetExists(path: String): Boolean {
        return runCatching {
            context.assets.open(path).close()
            true
        }.getOrDefault(false)
    }

    private fun readAsset(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private fun parseHotloadProfiles(
        manifestJson: String,
        availableFiles: List<String>
    ): List<RuntimeProfile> {
        val json = JSONObject(manifestJson)
        val defaultPackageIds = json.optJSONArray("defaultPackageIds").toStringList()
        val profiles = json.optJSONArray("profiles") ?: JSONArray()
        val declaredProfiles = buildList(profiles.length()) {
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                val file = item.optString("file").trim()
                if (file.isEmpty()) {
                    continue
                }

                val packageIds = item.optJSONArray("packageIds").toStringList().ifEmpty { defaultPackageIds }
                add(
                    RuntimeProfile(
                        id = item.optString("id", file.substringBeforeLast('.')).trim(),
                        label = item.optString("label", file).trim(),
                        file = file,
                        version = item.optString("version").trim(),
                        channel = item.optString("channel").trim(),
                        studyLock = item.optBoolean("studyLock", false),
                        description = item.optString("description").trim(),
                        packageIds = packageIds
                    )
                )
            }
        }

        val declaredFiles = declaredProfiles.map { it.file.lowercase() }.toSet()
        val adhocProfiles = availableFiles
            .filter { it.endsWith(".csv", ignoreCase = true) }
            .filterNot { declaredFiles.contains(it.lowercase()) }
            .sorted()
            .map { csvFile ->
                RuntimeProfile(
                    id = csvFile.substringBeforeLast('.'),
                    label = csvFile,
                    file = csvFile,
                    version = "",
                    channel = "adhoc",
                    studyLock = false,
                    description = "",
                    packageIds = defaultPackageIds
                )
            }

        return declaredProfiles + adhocProfiles
    }

    private fun parseDeviceProfiles(jsonText: String): List<DeviceProfile> {
        val json = JSONObject(jsonText)
        val profiles = json.optJSONArray("profiles") ?: JSONArray()

        return buildList(profiles.length()) {
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isEmpty()) {
                    continue
                }

                add(
                    DeviceProfile(
                        id = id,
                        label = item.optString("label", id).trim(),
                        description = item.optString("description").trim(),
                        props = item.optJSONObject("props").toStringMap()
                    )
                )
            }
        }
    }

    private data class CatalogCandidate(
        val file: File,
        val source: CatalogSource
    )
}
