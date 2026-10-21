package com.forddiag.android.core

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DidDef(
    val did: Int,
    val label: String,
    @SerialName("as_built") val asBuilt: Boolean = false,
)

@Serializable
data class ModuleDef(
    val id: String,
    val name: String,
    @SerialName("tx_id") val txId: Int,
    @SerialName("rx_id") val rxId: Int? = null,
    val bus: String,
    val dids: List<DidDef> = emptyList(),
) {
    fun canBus(): CanBus = when (bus.lowercase()) {
        "ms" -> CanBus.MS
        else -> CanBus.HS
    }
}

@Serializable
data class VehicleProfile(
    val id: String,
    val name: String,
    val years: String,
    val notes: String? = null,
    val modules: List<ModuleDef>,
) {
    fun module(id: String): ModuleDef? = modules.find { it.id == id }
}

object ProfileRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val assetFiles = listOf(
        "profiles/focus_2017_eu.json",
        "profiles/tourneo_connect_2017.json",
    )

    private val builtInIds: Set<String> = setOf(
        "focus_2017_eu",
        "tourneo_connect_2017",
    )

    fun isBuiltIn(id: String): Boolean = id in builtInIds

    fun isOnDisk(context: Context, id: String): Boolean = userFile(context, id).isFile

    /** True when a user copy exists (overrides built-in or is user-only). */
    fun canDelete(context: Context, id: String): Boolean = isOnDisk(context, id)

    private fun userDir(context: Context): File =
        File(context.filesDir, "profiles").also { it.mkdirs() }

    private fun userFile(context: Context, id: String): File =
        File(userDir(context), "$id.json")

    fun loadAll(context: Context): List<VehicleProfile> {
        val byId = linkedMapOf<String, VehicleProfile>()
        for (path in assetFiles) {
            runCatching {
                context.assets.open(path).bufferedReader().use { reader ->
                    json.decodeFromString<VehicleProfile>(reader.readText())
                }
            }.getOrNull()?.let { byId[it.id] = it }
        }
        userDir(context).listFiles { f -> f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching {
                    json.decodeFromString<VehicleProfile>(file.readText())
                }.getOrNull()?.let { byId[it.id] = it }
            }
        return byId.values.toList()
    }

    fun loadById(context: Context, id: String): VehicleProfile =
        loadAll(context).find { it.id == id }
            ?: throw FordException.Profile("unknown profile id: $id")

    fun save(context: Context, profile: VehicleProfile) {
        require(profile.id.isNotBlank()) { "profile id required" }
        require(profile.modules.isNotEmpty()) { "profile must have modules" }
        val safeId = profile.id.trim()
        require(safeId.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
            "profile id must be alphanumeric/underscore/dash"
        }
        val toWrite = profile.copy(id = safeId)
        userFile(context, safeId).writeText(json.encodeToString(VehicleProfile.serializer(), toWrite))
    }

    fun delete(context: Context, id: String): Boolean {
        val file = userFile(context, id)
        if (!file.isFile) return false
        return file.delete()
    }
}
