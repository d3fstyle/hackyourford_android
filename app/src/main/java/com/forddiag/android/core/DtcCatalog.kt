package com.forddiag.android.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class DtcInfo(
    val name: String,
    val description: String,
)

object DtcCatalog {
    private const val ASSET_PATH = "dtc/obd_generic.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = AtomicBoolean(false)
    @Volatile
    private var entries: Map<String, DtcInfo> = emptyMap()

    fun ensureLoaded(context: Context) {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            runCatching {
                context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                    loadFromJson(reader.readText())
                }
            }
            loaded.set(true)
        }
    }

    /** Replace catalog contents (used by tests and by [ensureLoaded]). */
    fun loadFromJson(text: String) {
        entries = json.decodeFromString<Map<String, DtcInfo>>(text)
        loaded.set(true)
    }

    fun clear() {
        entries = emptyMap()
        loaded.set(false)
    }

    fun lookup(code: String): DtcInfo? = entries[code.uppercase()]

    fun formatDisplay(dtc: Dtc): String {
        val info = lookup(dtc.code)
        val name = info?.name ?: "Unknown"
        val status = formatDtcStatus(dtc.status)
        return if (info != null) {
            "$name (${dtc.code}): $status — ${info.description}"
        } else {
            "$name (${dtc.code}): $status"
        }
    }
}
