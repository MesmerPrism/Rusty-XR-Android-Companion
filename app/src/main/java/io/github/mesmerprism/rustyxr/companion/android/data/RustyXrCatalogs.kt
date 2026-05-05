package io.github.mesmerprism.rustyxr.companion.android.data

const val QuestAppCatalogSchemaVersion = "rusty.xr.quest-app-catalog.v1"

enum class CatalogSource(val label: String) {
    StagedPublicCatalog("staged public catalog"),
    StagedLibrary("staged library"),
    BundledPublicCatalog("bundled public catalog"),
    BundledSample("bundled sample")
}

data class ApkTarget(
    val id: String,
    val label: String,
    val file: String,
    val packageId: String,
    val launchComponent: String = "",
    val browserPackageId: String = "",
    val description: String = "",
    val tags: List<String> = emptyList()
) {
    fun matchesPackage(packageId: String?): Boolean {
        return packageId != null && this.packageId.equals(packageId, ignoreCase = true)
    }

    fun resolvedBrowserPackageId(): String? {
        return browserPackageId.takeIf { it.isNotBlank() }
            ?: packageId.takeIf { tags.any { tag -> tag.equals("browser", ignoreCase = true) } }
    }
}

data class ApkBundle(
    val id: String,
    val label: String,
    val appIds: List<String>,
    val description: String = ""
)

data class RuntimeProfile(
    val id: String,
    val label: String,
    val file: String = "",
    val version: String = "",
    val channel: String = "",
    val studyLock: Boolean = false,
    val description: String = "",
    val packageIds: List<String> = emptyList(),
    val values: Map<String, String> = emptyMap()
) {
    val hasHotloadFile: Boolean
        get() = file.isNotBlank()

    val hasLaunchExtras: Boolean
        get() = values.isNotEmpty()

    fun matchesPackage(packageId: String?): Boolean {
        if (packageIds.isEmpty() || packageIds.any { it == "*" }) {
            return true
        }

        return packageId != null && packageIds.any { it.equals(packageId, ignoreCase = true) }
    }

    fun matchesTarget(target: ApkTarget?): Boolean {
        if (target == null) {
            return matchesPackage(null)
        }
        if (packageIds.isNotEmpty()) {
            return matchesPackage(target.packageId)
        }

        val example = values["rustyxr.example"]?.takeIf { it.isNotBlank() } ?: return true
        return normalizeRuntimeToken(target.id) == normalizeRuntimeToken(example) ||
            normalizeRuntimeToken(target.packageId) == normalizeRuntimeToken(example)
    }

    private fun normalizeRuntimeToken(value: String): String {
        return value
            .lowercase()
            .removePrefix("com.example.")
            .removePrefix("rustyxr.")
            .removePrefix("rusty-xr-")
            .removeSuffix("-apk")
            .replace('_', '-')
            .replace('.', '-')
    }
}

@Deprecated("Use RuntimeProfile for public Rusty XR catalog profiles.")
typealias HotloadProfile = RuntimeProfile

data class DeviceProfile(
    val id: String,
    val label: String,
    val description: String,
    val props: Map<String, String>
)

data class QuestLibraryManifest(
    val source: CatalogSource,
    val sourcePath: String?,
    val apps: List<ApkTarget>,
    val bundles: List<ApkBundle>,
    val runtimeProfiles: List<RuntimeProfile> = emptyList(),
    val deviceProfiles: List<DeviceProfile> = emptyList(),
    val schemaVersion: String? = null
)

data class RustyXrCatalogs(
    val source: CatalogSource,
    val sourcePath: String?,
    val apkTargets: List<ApkTarget>,
    val apkBundles: List<ApkBundle>,
    val runtimeProfiles: List<RuntimeProfile>,
    val deviceProfiles: List<DeviceProfile>
) {
    val hotloadProfiles: List<RuntimeProfile>
        get() = runtimeProfiles
}
