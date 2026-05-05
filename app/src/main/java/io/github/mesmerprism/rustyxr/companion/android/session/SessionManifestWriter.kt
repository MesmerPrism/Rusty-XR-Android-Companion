package io.github.mesmerprism.rustyxr.companion.android.session

import android.content.Context
import io.github.mesmerprism.rustyxr.companion.android.data.ApkBundle
import io.github.mesmerprism.rustyxr.companion.android.data.ApkTarget
import io.github.mesmerprism.rustyxr.companion.android.data.DeviceProfile
import io.github.mesmerprism.rustyxr.companion.android.data.RuntimeProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SessionManifestWriter(
    private val context: Context
) {
    suspend fun write(
        state: QuestSessionUiState,
        selectedApk: ApkTarget?,
        selectedBundle: ApkBundle?,
        selectedHotload: RuntimeProfile?,
        selectedDeviceProfile: DeviceProfile?
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "session_logs").apply { mkdirs() }
        val stamp = FILE_STAMP.format(Instant.now())
        val outputFile = File(outputDir, "session_manifest_$stamp.json")

        val json = JSONObject().apply {
            put("generatedAtUtc", Instant.now().toString())
            put("endpointDraft", state.endpointDraft)
            put("activeEndpoint", state.activeEndpoint ?: JSONObject.NULL)
            put("serviceStatus", state.serviceStatus)
            put("catalogRefreshStatus", state.catalogRefreshStatus)
            put("catalogSourceLabel", state.catalogSourceLabel)
            put("catalogManifestPath", state.catalogManifestPath ?: JSONObject.NULL)
            put("connectionSummary", state.connectionSummary)
            put("connectionDetail", state.connectionDetail)
            put("phoneNetworkSummary", state.phoneNetworkSummary)
            put("phoneNetworkDetail", state.phoneNetworkDetail)
            put("usbSummary", state.usbSummary)
            put("usbDetail", state.usbDetail)
            put("connectionProgress", progressToJson(state.connectionProgress))
            put("installStatus", state.installStatus)
            put("installDetail", state.installDetail)
            put("installProgress", progressToJson(state.installProgress))
            put("bundleInstallStatus", state.bundleInstallStatus)
            put("bundleInstallDetail", state.bundleInstallDetail)
            put("bundleInstallProgress", progressToJson(state.bundleInstallProgress))
            put("hotloadStatus", state.hotloadStatus)
            put("hotloadDetail", state.hotloadDetail)
            put("hotloadProgress", progressToJson(state.hotloadProgress))
            put("deviceProfileStatus", state.deviceProfileStatus)
            put("deviceProfileDetail", state.deviceProfileDetail)
            put("deviceProfileProgress", progressToJson(state.deviceProfileProgress))
            put("launchStatus", state.launchStatus)
            put("launchDetail", state.launchDetail)
            put("launchProgress", progressToJson(state.launchProgress))
            put("browserStatus", state.browserStatus)
            put("browserDetail", state.browserDetail)
            put("browserProgress", progressToJson(state.browserProgress))
            put("utilityStatus", state.utilityStatus)
            put("utilityDetail", state.utilityDetail)
            put("utilityProgress", progressToJson(state.utilityProgress))
            put("foregroundStatus", state.foregroundStatus)
            put("foregroundDetail", state.foregroundDetail)
            put("foregroundProgress", progressToJson(state.foregroundProgress))
            put("activeForegroundPackageId", state.activeForegroundPackageId ?: JSONObject.NULL)
            put("installedPackagesStatus", state.installedPackagesStatus)
            put("installedPackagesDetail", state.installedPackagesDetail)
            put("installedPackagesProgress", progressToJson(state.installedPackagesProgress))
            put("installedPackageIds", JSONArray(state.installedPackageIds))
            put("lastBrowserUrl", state.lastBrowserUrl ?: JSONObject.NULL)
            put("lastActionLabel", state.lastActionLabel)
            put("lastActionDetail", state.lastActionDetail)
            put("lastActionProgress", progressToJson(state.lastActionProgress))
            put(
                "monitor",
                JSONObject().apply {
                    put("streamName", state.monitorStreamName)
                    put("streamType", state.monitorStreamType)
                    put("channelIndex", state.monitorChannelIndex)
                    put("status", state.monitorStatus)
                    put("detail", state.monitorDetail)
                    put("value", state.monitorValue?.toDouble() ?: JSONObject.NULL)
                    put("sampleRateHz", state.monitorSampleRateHz.toDouble())
                }
            )
            put("browserUrlDraft", state.browserUrlDraft)
            put(
                "selectedApk",
                selectedApk?.let {
                    JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("file", it.file)
                        put("packageId", it.packageId)
                        put("launchComponent", it.launchComponent)
                        put("browserPackageId", it.browserPackageId)
                        put("description", it.description)
                        put("tags", JSONArray(it.tags))
                    }
                } ?: JSONObject.NULL
            )
            put(
                "selectedBundle",
                selectedBundle?.let {
                    JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("description", it.description)
                        put("appIds", JSONArray(it.appIds))
                    }
                } ?: JSONObject.NULL
            )
            put(
                "selectedRuntimeProfile",
                selectedHotload?.let(::runtimeProfileToJson) ?: JSONObject.NULL
            )
            put(
                "selectedHotloadProfile",
                selectedHotload?.let(::runtimeProfileToJson) ?: JSONObject.NULL
            )
            put(
                "selectedDeviceProfile",
                selectedDeviceProfile?.let {
                    JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("description", it.description)
                        put("props", JSONObject(it.props))
                    }
                } ?: JSONObject.NULL
            )
            put("diagnosticsExportProgress", progressToJson(state.diagnosticsExportProgress))
            put(
                "recentLogs",
                JSONArray().apply {
                    state.logs.take(20).forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("timestamp", entry.timestamp)
                                put("level", entry.level.name)
                                put("message", entry.message)
                                put("detail", entry.detail)
                            }
                        )
                    }
                }
            )
        }

        outputFile.writeText(json.toString(2))
        outputFile
    }

    private fun progressToJson(progress: ActionProgressUiState): JSONObject {
        return JSONObject().apply {
            put("active", progress.active)
            put("label", progress.label)
            put("detail", progress.detail)
            put("fraction", progress.fraction?.toDouble() ?: JSONObject.NULL)
        }
    }

    private fun runtimeProfileToJson(profile: RuntimeProfile): JSONObject {
        return JSONObject().apply {
            put("id", profile.id)
            put("label", profile.label)
            put("file", profile.file)
            put("version", profile.version)
            put("channel", profile.channel)
            put("studyLock", profile.studyLock)
            put("description", profile.description)
            put("packageIds", JSONArray(profile.packageIds))
            put("values", JSONObject(profile.values))
        }
    }

    private companion object {
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
    }
}

