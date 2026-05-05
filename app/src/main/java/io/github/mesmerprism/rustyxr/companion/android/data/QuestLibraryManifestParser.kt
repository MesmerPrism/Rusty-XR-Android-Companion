package io.github.mesmerprism.rustyxr.companion.android.data

import org.json.JSONArray
import org.json.JSONObject

internal object RustyXrCatalogParser {
    fun parse(
        jsonText: String,
        source: CatalogSource,
        sourcePath: String? = null
    ): QuestLibraryManifest {
        val json = JSONObject(jsonText)
        val schemaVersion = json.cleanString("schemaVersion")
        if (schemaVersion == QuestAppCatalogSchemaVersion) {
            return parsePublicCatalog(json, source, sourcePath)
        }

        val apps = when {
            json.has("apps") -> parseLegacyLibraryApps(json.optJSONArray("apps") ?: JSONArray())
            json.has("apks") -> parseLegacyApps(json.optJSONArray("apks") ?: JSONArray())
            else -> emptyList()
        }

        require(apps.isNotEmpty()) {
            "Library manifest does not contain any apps."
        }

        val bundles = parseBundles(json.optJSONArray("bundles") ?: JSONArray(), apps)
        return QuestLibraryManifest(
            source = source,
            sourcePath = sourcePath,
            apps = apps,
            bundles = bundles,
            schemaVersion = schemaVersion.ifBlank { null }
        )
    }

    private fun parsePublicCatalog(
        json: JSONObject,
        source: CatalogSource,
        sourcePath: String?
    ): QuestLibraryManifest {
        val apps = parsePublicApps(json.optJSONArray("apps") ?: JSONArray())
        require(apps.isNotEmpty()) {
            "Quest app catalog does not contain any apps."
        }

        return QuestLibraryManifest(
            source = source,
            sourcePath = sourcePath,
            apps = apps,
            bundles = parseBundles(json.optJSONArray("bundles") ?: JSONArray(), apps),
            runtimeProfiles = parseRuntimeProfiles(json.optJSONArray("runtimeProfiles") ?: JSONArray()),
            deviceProfiles = parsePublicDeviceProfiles(json.optJSONArray("deviceProfiles") ?: JSONArray()),
            schemaVersion = QuestAppCatalogSchemaVersion
        )
    }

    private fun parsePublicApps(items: JSONArray): List<ApkTarget> {
        val apps = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.cleanString("id")
                val label = item.cleanString("label")
                val packageName = item.cleanString("packageName")
                if (id.isEmpty() || label.isEmpty() || packageName.isEmpty()) {
                    continue
                }

                val activityName = item.cleanString("activityName")
                add(
                    ApkTarget(
                        id = id,
                        label = label,
                        file = item.cleanString("apkFile"),
                        packageId = packageName,
                        launchComponent = resolveLaunchComponent(packageName, activityName),
                        description = item.cleanString("description")
                    )
                )
            }
        }

        requireUniqueIds(apps.map { it.id }, "Quest app catalog contains duplicate app ids.")
        return apps
    }

    private fun parseLegacyLibraryApps(items: JSONArray): List<ApkTarget> {
        val apps = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.cleanString("id")
                val label = item.cleanString("label")
                val file = item.cleanString("apkFile")
                val packageId = item.cleanString("packageId")
                if (id.isEmpty() || label.isEmpty() || file.isEmpty() || packageId.isEmpty()) {
                    continue
                }

                add(
                    ApkTarget(
                        id = id,
                        label = label,
                        file = file,
                        packageId = packageId,
                        launchComponent = item.cleanString("launchComponent"),
                        browserPackageId = item.cleanString("browserPackageId"),
                        description = item.cleanString("description"),
                        tags = item.optJSONArray("tags").toStringList()
                    )
                )
            }
        }

        requireUniqueIds(apps.map { it.id }, "Library manifest contains duplicate app ids.")
        return apps
    }

    private fun parseLegacyApps(items: JSONArray): List<ApkTarget> {
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val file = item.cleanString("file")
                val packageId = item.cleanString("packageId")
                if (file.isEmpty() || packageId.isEmpty()) {
                    continue
                }

                add(
                    ApkTarget(
                        id = packageId,
                        label = packageId,
                        file = file,
                        packageId = packageId,
                        description = "Imported from the bundled legacy APK catalog."
                    )
                )
            }
        }
    }

    private fun parseBundles(
        items: JSONArray,
        apps: List<ApkTarget>
    ): List<ApkBundle> {
        val knownAppIds = apps.map { it.id }.toSet()
        val bundles = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.cleanString("id")
                val label = item.cleanString("label")
                val appIds = item.optJSONArray("appIds").toStringList()
                if (id.isEmpty() || label.isEmpty() || appIds.isEmpty()) {
                    continue
                }

                require(appIds.all { knownAppIds.contains(it) }) {
                    "Bundle $id references unknown app ids."
                }

                add(
                    ApkBundle(
                        id = id,
                        label = label,
                        appIds = appIds,
                        description = item.cleanString("description")
                    )
                )
            }
        }

        requireUniqueIds(bundles.map { it.id }, "Library manifest contains duplicate bundle ids.")
        return bundles
    }

    private fun parseRuntimeProfiles(items: JSONArray): List<RuntimeProfile> {
        val profiles = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.cleanString("id")
                if (id.isEmpty()) {
                    continue
                }

                add(
                    RuntimeProfile(
                        id = id,
                        label = item.cleanString("label").ifBlank { id },
                        description = item.cleanString("description"),
                        values = item.optJSONObject("values").toStringMap(),
                        packageIds = item.optJSONArray("packageIds").toStringList()
                    )
                )
            }
        }

        requireUniqueIds(profiles.map { it.id }, "Quest app catalog contains duplicate runtime profile ids.")
        return profiles
    }

    private fun parsePublicDeviceProfiles(items: JSONArray): List<DeviceProfile> {
        val profiles = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.cleanString("id")
                if (id.isEmpty()) {
                    continue
                }

                add(
                    DeviceProfile(
                        id = id,
                        label = item.cleanString("label").ifBlank { id },
                        description = item.cleanString("description"),
                        props = item.optJSONArray("properties").toPropertyMap()
                    )
                )
            }
        }

        requireUniqueIds(profiles.map { it.id }, "Quest app catalog contains duplicate device profile ids.")
        return profiles
    }

    private fun resolveLaunchComponent(packageName: String, activityName: String): String {
        if (activityName.isBlank()) {
            return ""
        }

        return if (activityName.contains('/')) {
            activityName
        } else {
            "$packageName/$activityName"
        }
    }

    private fun requireUniqueIds(ids: List<String>, message: String) {
        require(ids.map { it.lowercase() }.distinct().size == ids.size) {
            message
        }
    }
}

internal object QuestLibraryManifestParser {
    fun parse(
        jsonText: String,
        source: CatalogSource,
        sourcePath: String? = null
    ): QuestLibraryManifest {
        return RustyXrCatalogParser.parse(jsonText, source, sourcePath)
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) {
        return emptyList()
    }

    return buildList(length()) {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) {
        return emptyMap()
    }

    val keys = keys()
    val map = linkedMapOf<String, String>()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = opt(key)?.toString().orEmpty()
    }

    return map
}

internal fun JSONArray?.toPropertyMap(): Map<String, String> {
    if (this == null) {
        return emptyMap()
    }

    val map = linkedMapOf<String, String>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val key = item.cleanString("key")
        if (key.isNotEmpty()) {
            map[key] = item.cleanString("value")
        }
    }

    return map
}

internal fun JSONObject.cleanString(key: String): String {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) {
        return ""
    }

    return value.toString().trim()
}
