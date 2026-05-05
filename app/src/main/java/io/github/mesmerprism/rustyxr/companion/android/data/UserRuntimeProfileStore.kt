package io.github.mesmerprism.rustyxr.companion.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

const val UserRuntimeProfileSchemaVersion = "rusty.xr.android-companion.user-runtime-profiles.v1"

class UserRuntimeProfileStore(
    private val profileFile: File
) {
    constructor(context: Context) : this(
        File(context.filesDir, "catalogs/user-runtime-profiles.json")
    )

    fun loadProfiles(): List<RuntimeProfile> {
        if (!profileFile.exists()) {
            return emptyList()
        }

        return parseProfiles(profileFile.readText())
    }

    fun upsert(profile: RuntimeProfile): RuntimeProfile {
        val profiles = loadProfiles().filterNot { it.id.equals(profile.id, ignoreCase = true) } + profile
        writeProfiles(profiles)
        return profile
    }

    fun delete(profileId: String): Boolean {
        val profiles = loadProfiles()
        val remaining = profiles.filterNot { it.id.equals(profileId, ignoreCase = true) }
        if (remaining.size == profiles.size) {
            return false
        }

        writeProfiles(remaining)
        return true
    }

    fun writeProfiles(profiles: List<RuntimeProfile>) {
        profileFile.parentFile?.mkdirs()
        profileFile.writeText(serializeProfiles(profiles))
    }

    fun resolveUniqueProfileId(label: String, existingIds: Set<String>): String {
        val base = buildProfileId(label)
        if (!existingIds.contains(base.lowercase(Locale.ROOT))) {
            return base
        }

        var suffix = 2
        while (existingIds.contains("$base-$suffix".lowercase(Locale.ROOT))) {
            suffix += 1
        }
        return "$base-$suffix"
    }

    companion object {
        fun parseProfiles(jsonText: String): List<RuntimeProfile> {
            val json = JSONObject(jsonText)
            val profiles = json.optJSONArray("profiles") ?: JSONArray()
            return buildList(profiles.length()) {
                for (index in 0 until profiles.length()) {
                    val item = profiles.optJSONObject(index) ?: continue
                    val id = item.cleanString("id")
                    if (id.isBlank()) {
                        continue
                    }

                    add(
                        RuntimeProfile(
                            id = id,
                            label = item.cleanString("label").ifBlank { id },
                            description = item.cleanString("description"),
                            packageIds = item.optJSONArray("packageIds").toStringList(),
                            values = item.optJSONObject("values").toStringMap()
                        )
                    )
                }
            }
        }

        fun serializeProfiles(profiles: List<RuntimeProfile>): String {
            val root = JSONObject()
            root.put("schemaVersion", UserRuntimeProfileSchemaVersion)
            root.put(
                "profiles",
                JSONArray(
                    profiles
                        .sortedBy { it.label.lowercase(Locale.ROOT) }
                        .map(::profileToJson)
                )
            )
            return root.toString(2)
        }

        private fun profileToJson(profile: RuntimeProfile): JSONObject {
            val values = JSONObject()
            profile.values.toSortedMap().forEach { (key, value) ->
                values.put(key, value)
            }

            return JSONObject()
                .put("id", profile.id)
                .put("label", profile.label)
                .put("description", profile.description)
                .put("packageIds", JSONArray(profile.packageIds))
                .put("values", values)
        }

        private fun buildProfileId(label: String): String {
            val slug = label
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "runtime-profile" }
            return "user.$slug"
        }
    }
}
