package com.forddiag.android.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One Torque-style extended PID (Ford predefined set).
 * [modeAndPid] is hex like `221340` (Mode 22 + DID) or `0105` (Mode 01).
 */
@Serializable
data class FordExtendedPid(
    val name: String,
    val shortName: String,
    val modeAndPid: String,
    val equation: String,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val scale: Double = 1.0,
    val unit: String = "",
    val header: String = "Auto",
) {
    val modeAndPidNormalized: String
        get() = modeAndPid.trim().uppercase().filter { it.isDigit() || it in 'A'..'F' }

    val mode: Int
        get() {
            val hex = modeAndPidNormalized
            return when {
                hex.length >= 4 -> hex.substring(0, 2).toInt(16)
                hex.length == 2 -> 0x01
                else -> -1
            }
        }

    /** Mode 22 DID, or Mode 01 PID id. */
    val pidOrDid: Int
        get() {
            val hex = modeAndPidNormalized
            return when {
                hex.length >= 4 -> hex.substring(2).toInt(16)
                hex.length == 2 -> hex.toInt(16)
                else -> -1
            }
        }

    val isMode22: Boolean get() = mode == 0x22
    val isMode01: Boolean get() = mode == 0x01
}

/** Ford extended PID bound to a concrete profile module (or synthetic header module). */
data class ResolvedFordPid(
    val pid: FordExtendedPid,
    val moduleId: String,
    val module: ModuleDef,
)

object FordExtendedPidCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<FordExtendedPid>? = null

    @Volatile
    private var cachedMode01Scan: Set<Int>? = null

    fun load(context: Context): List<FordExtendedPid> {
        cached?.let { return it }
        val loaded = context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
            json.decodeFromString<List<FordExtendedPid>>(reader.readText())
        }
        cached = loaded
        return loaded
    }

    /**
     * Mode 01 PIDs that responded in a TorqueScan export (reference vehicle).
     * Used to mark/prioritize OBD catalog rows.
     */
    fun loadMode01ScanSupported(context: Context): Set<Int> {
        cachedMode01Scan?.let { return it }
        val loaded = context.assets.open(MODE01_SCAN_ASSET).bufferedReader().use { reader ->
            json.decodeFromString<List<Int>>(reader.readText()).toSet()
        }
        cachedMode01Scan = loaded
        return loaded
    }

    /** Clear cache (tests). */
    fun clearCache() {
        cached = null
        cachedMode01Scan = null
    }

    const val ASSET_PATH = "ford/extended_pids.json"
    const val MODE01_SCAN_ASSET = "ford/mode01_scan_supported.json"
}

/**
 * Map Torque header alias to a profile module.
 * Returns null when the header is unsupported (J1850) or a required alias module is missing.
 */
fun resolveFordHeader(header: String, profile: VehicleProfile): ModuleDef? {
    val h = header.trim()
    if (h.equals("C410F1", ignoreCase = true)) return null // J1850 PWM

    val up = h.uppercase()
    when {
        up.isEmpty() || up == "AUTO" -> return profile.module("PCM")
        up == "TCM" -> return profile.module("TCM")
        up == "ABS" -> return profile.module("ABS")
        up == "INS" || up == "IPC" -> return profile.module("IPC")
        up == "PAM" -> return profile.module("PAM")
    }

    val tx = h.toIntOrNull(16) ?: return null
    profile.modules.find { it.txId == tx }?.let { return it }
    // Synthetic module for known hex headers (e.g. TPMS 0x726)
    val id = "HDR_%03X".format(tx)
    return ModuleDef(
        id = id,
        name = "Header 0x%03X".format(tx),
        txId = tx,
        bus = "hs",
    )
}

/**
 * Resolve catalog entries that can be polled with the given [profile].
 * Skips unsupported modes/headers; Mode 01 only when header is a non-PCM alias module.
 */
fun resolveFordExtendedPids(
    catalog: List<FordExtendedPid>,
    profile: VehicleProfile,
): List<ResolvedFordPid> {
    val out = mutableListOf<ResolvedFordPid>()
    val seen = mutableSetOf<String>()
    for (pid in catalog) {
        if (pid.mode !in listOf(0x01, 0x22)) continue
        if (pid.pidOrDid < 0) continue
        val module = resolveFordHeader(pid.header, profile) ?: continue
        // Mode 01 on PCM duplicates SAE Mode 01 — skip
        if (pid.isMode01 && module.id == "PCM") continue
        // Mode 01 only when alias module exists (e.g. TCM); skip synthetic HDR_*
        if (pid.isMode01 && module.id.startsWith("HDR_")) continue
        if (pid.isMode01 && profile.module(module.id) == null) continue

        val key = "${module.id}|${pid.mode}|${pid.pidOrDid}|${pid.equation}"
        if (!seen.add(key)) continue
        out += ResolvedFordPid(pid, module.id, module)
    }
    return out
}
