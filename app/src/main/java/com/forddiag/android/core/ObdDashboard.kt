package com.forddiag.android.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

enum class ObdDashboardScope { GLOBAL, PROFILE }

@Serializable
data class ObdDashboardSignalKey(
    val source: String,
    val id: Int,
    val moduleId: String? = null,
)

@Serializable
data class ObdDashboard(
    val id: String,
    val name: String,
    val signals: List<ObdDashboardSignalKey>,
    val updatedAtMs: Long,
)

data class ObdFavoritePointer(
    val scope: ObdDashboardScope,
    val dashboardId: String,
    val profileId: String? = null,
) {
    fun isApplicable(selectedProfileId: String?): Boolean = when (scope) {
        ObdDashboardScope.GLOBAL -> true
        ObdDashboardScope.PROFILE ->
            !profileId.isNullOrBlank() && profileId == selectedProfileId
    }
}

object ObdDashboardIds {
    private val idRegex = Regex("^[a-zA-Z0-9_\\-]+$")

    fun isValidId(id: String): Boolean = id.isNotBlank() && id.matches(idRegex)

    /** Slug from name + short suffix; always a valid id. */
    fun newId(name: String): String {
        val slug = name.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "dashboard" }
            .take(32)
        val suffix = UUID.randomUUID().toString().take(8)
        val candidate = "${slug}_$suffix"
        require(isValidId(candidate))
        return candidate
    }
}

class ObdDashboardStore(private val rootDir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun globalDir(): File = File(rootDir, "global").also { it.mkdirs() }

    private fun profileDir(profileId: String): File =
        File(File(rootDir, "by_profile"), profileId).also { it.mkdirs() }

    private fun fileFor(scope: ObdDashboardScope, profileId: String?, id: String): File =
        when (scope) {
            ObdDashboardScope.GLOBAL -> File(globalDir(), "$id.json")
            ObdDashboardScope.PROFILE -> {
                require(!profileId.isNullOrBlank()) { "profileId required for PROFILE scope" }
                File(profileDir(profileId), "$id.json")
            }
        }

    fun listGlobal(): List<ObdDashboard> = listDir(globalDir())

    fun listForProfile(profileId: String): List<ObdDashboard> {
        if (profileId.isBlank()) return emptyList()
        return listDir(profileDir(profileId))
    }

    private fun listDir(dir: File): List<ObdDashboard> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString(ObdDashboard.serializer(), file.readText())
                }.getOrNull()
            }
            .orEmpty()
    }

    fun load(scope: ObdDashboardScope, profileId: String?, id: String): ObdDashboard {
        val file = fileFor(scope, profileId, id)
        require(file.isFile) { "dashboard not found: $id" }
        return json.decodeFromString(ObdDashboard.serializer(), file.readText())
    }

    fun save(scope: ObdDashboardScope, profileId: String?, dashboard: ObdDashboard) {
        require(ObdDashboardIds.isValidId(dashboard.id)) { "invalid dashboard id" }
        require(dashboard.name.isNotBlank()) { "name required" }
        val file = fileFor(scope, profileId, dashboard.id)
        file.writeText(json.encodeToString(ObdDashboard.serializer(), dashboard))
    }

    fun delete(scope: ObdDashboardScope, profileId: String?, id: String): Boolean {
        val file = fileFor(scope, profileId, id)
        if (!file.isFile) return false
        return file.delete()
    }

    fun copyGlobalToProfile(dashboardId: String, profileId: String): ObdDashboard {
        val src = load(ObdDashboardScope.GLOBAL, null, dashboardId)
        val copy = src.copy(
            id = ObdDashboardIds.newId(src.name),
            updatedAtMs = System.currentTimeMillis(),
        )
        save(ObdDashboardScope.PROFILE, profileId, copy)
        return copy
    }
}

object ObdDashboardRepository {
    private fun store(context: Context): ObdDashboardStore =
        ObdDashboardStore(File(context.filesDir, "obd_dashboards"))

    fun listGlobal(context: Context): List<ObdDashboard> = store(context).listGlobal()

    fun listForProfile(context: Context, profileId: String): List<ObdDashboard> =
        store(context).listForProfile(profileId)

    fun load(
        context: Context,
        scope: ObdDashboardScope,
        profileId: String?,
        id: String,
    ): ObdDashboard = store(context).load(scope, profileId, id)

    fun save(
        context: Context,
        scope: ObdDashboardScope,
        profileId: String?,
        dashboard: ObdDashboard,
    ) {
        store(context).save(scope, profileId, dashboard)
    }

    fun delete(
        context: Context,
        scope: ObdDashboardScope,
        profileId: String?,
        id: String,
    ): Boolean = store(context).delete(scope, profileId, id)

    fun copyGlobalToProfile(
        context: Context,
        dashboardId: String,
        profileId: String,
    ): ObdDashboard = store(context).copyGlobalToProfile(dashboardId, profileId)
}
