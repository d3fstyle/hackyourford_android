package com.forddiag.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.forddiag.android.core.CanFrame
import com.forddiag.android.core.ConnectionStatus
import com.forddiag.android.core.DiscoveredEcu
import com.forddiag.android.core.DtcCatalog
import com.forddiag.android.core.LiveSample
import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ModuleDtcGroup
import com.forddiag.android.core.ModuleScanResult
import com.forddiag.android.core.PatternCandidate
import com.forddiag.android.core.formatDtcStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TabExportPayload(
    val plainText: String,
    val csv: String,
    val filenamePrefix: String,
)

enum class DiscoverExportMode { SNIFF, DID }

fun tabExportAvailable(
    tab: AppTab,
    state: UiState,
    discoverMode: DiscoverExportMode = DiscoverExportMode.SNIFF,
): Boolean = when (tab) {
    AppTab.HOME -> state.status != null
    AppTab.PROFILES -> false
    AppTab.ECUS -> state.modules.isNotEmpty() || state.discovered.isNotEmpty()
    AppTab.DTCS -> state.dtcGroups.isNotEmpty()
    AppTab.LIVE -> state.liveStream.isNotEmpty()
    AppTab.DISCOVER -> when (discoverMode) {
        DiscoverExportMode.SNIFF ->
            state.frames.isNotEmpty() || state.candidates.isNotEmpty()
        DiscoverExportMode.DID ->
            state.sweptDids.isNotEmpty() || state.didDiffs.isNotEmpty()
    }
}

fun buildTabExport(
    tab: AppTab,
    state: UiState,
    discoverMode: DiscoverExportMode = DiscoverExportMode.SNIFF,
): TabExportPayload = when (tab) {
    AppTab.HOME -> exportHome(state.status)
    AppTab.PROFILES -> error("Profiles tab has no export payload")
    AppTab.ECUS -> exportEcus(state)
    AppTab.DTCS -> exportDtcs(state.dtcGroups)
    AppTab.LIVE -> exportLive(state.liveStream)
    AppTab.DISCOVER -> exportDiscover(state, discoverMode)
}

fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun shareFile(
    context: Context,
    filename: String,
    content: String,
    mime: String,
    title: String,
) {
    val file = File(context.cacheDir, filename)
    file.writeText(content)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

fun exportCsvFilename(prefix: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "$prefix-$stamp.csv"
}

private fun csvEscape(value: String): String {
    if (value.contains(';') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
    return value
}

private fun csvRow(vararg fields: String): String =
    fields.joinToString(";") { csvEscape(it) }

private fun exportHome(status: ConnectionStatus?): TabExportPayload {
    requireNotNull(status)
    val rows = buildList {
        add("Adapter" to status.adapter.identity)
        add(
            "VIN" to (status.vin ?: status.vinError?.let { "— ($it)" } ?: "—"),
        )
        add(
            "MS-CAN" to if (status.msCanCapable) {
                "Supported · ${status.adapter.msCanHint.ifBlank { "STN/OBDLink" }}"
            } else {
                "Limited · ${status.adapter.msCanHint.ifBlank { "ELM-only" }}"
            },
        )
        status.adapter.voltage?.let { add("Voltage" to "%.1fV".format(it)) }
    }
    val plain = rows.joinToString("\n") { (label, value) -> "$label\t$value" }
    val csv = buildString {
        appendLine(csvRow("label", "value"))
        rows.forEach { (label, value) -> appendLine(csvRow(label, value)) }
    }
    return TabExportPayload(plain, csv.trimEnd(), "forddiag-home")
}

private fun formatDiscoveredLine(ecu: DiscoveredEcu): String =
    "0x%03X · %s-CAN%s".format(
        ecu.txId,
        ecu.bus.name,
        ecu.name?.let { " · $it" } ?: "",
    )

private fun formatModuleMeta(module: ModuleScanResult): String =
    "%s · 0x%03X · %s".format(module.id, module.txId, module.bus.name)

private fun formatLiveSampleLine(sample: LiveSample): String {
    val kind = if (sample.source == LiveSource.PID) "PID" else "DID"
    val id = if (sample.source == LiveSource.PID) {
        "%02X".format(sample.id)
    } else {
        "0x%04X".format(sample.id)
    }
    val value = sample.decoded ?: sample.rawHex
    val module = sample.moduleId?.let { "\t$it" } ?: ""
    return "$kind $id\t$value$module"
}

private fun formatFrameLine(frame: CanFrame): String =
    "0x%03X  %s".format(
        frame.id,
        frame.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
    )

private fun formatCandidateLine(c: PatternCandidate): String =
    "0x%03X byte[%d]%s  score=%.2f".format(
        c.canId,
        c.byteIndex,
        c.bitIndex?.let { " bit$it" } ?: "",
        c.changeScore,
    )

private fun exportEcus(state: UiState): TabExportPayload {
    val selectedId = state.selectedModuleId
    val plain = buildString {
        if (state.discovered.isNotEmpty()) {
            appendLine("DISCOVERED")
            state.discovered.forEach { ecu ->
                appendLine(formatDiscoveredLine(ecu))
                ecu.identity.firstOrNull()?.let {
                    appendLine(it.ascii ?: it.hex)
                }
            }
            appendLine()
        }
        state.modules.forEach { m ->
            appendLine(m.name)
            appendLine(formatModuleMeta(m))
            if (!m.online && m.error != null) appendLine(m.error)
            m.identity.forEach { d ->
                appendLine("0x%04X\t%s".format(d.did, d.ascii ?: d.hex))
            }
            if (m.id == selectedId && m.online) {
                state.moduleDtcs.forEach { d ->
                    appendLine("${d.code}  status=0x${d.status.toString(16)}")
                }
                state.asBuiltJson?.let { appendLine(it) }
            }
            appendLine()
        }
    }.trimEnd()

    val csv = buildString {
        appendLine(csvRow("type", "id", "name", "txId", "bus", "online", "field", "value"))
        state.discovered.forEach { ecu ->
            appendLine(
                csvRow(
                    "discovered",
                    "",
                    ecu.name.orEmpty(),
                    "0x%03X".format(ecu.txId),
                    ecu.bus.name,
                    ecu.online.toString(),
                    "line",
                    formatDiscoveredLine(ecu),
                ),
            )
            ecu.identity.firstOrNull()?.let { d ->
                appendLine(
                    csvRow(
                        "discovered",
                        "",
                        ecu.name.orEmpty(),
                        "0x%03X".format(ecu.txId),
                        ecu.bus.name,
                        ecu.online.toString(),
                        "identity",
                        d.ascii ?: d.hex,
                    ),
                )
            }
        }
        state.modules.forEach { m ->
            appendLine(
                csvRow(
                    "module",
                    m.id,
                    m.name,
                    "0x%03X".format(m.txId),
                    m.bus.name,
                    m.online.toString(),
                    "error",
                    m.error.orEmpty(),
                ),
            )
            m.identity.forEach { d ->
                appendLine(
                    csvRow(
                        "identity",
                        m.id,
                        m.name,
                        "0x%03X".format(m.txId),
                        m.bus.name,
                        m.online.toString(),
                        "0x%04X".format(d.did),
                        d.ascii ?: d.hex,
                    ),
                )
            }
            if (m.id == selectedId) {
                state.moduleDtcs.forEach { d ->
                    appendLine(
                        csvRow(
                            "dtc",
                            m.id,
                            m.name,
                            "0x%03X".format(m.txId),
                            m.bus.name,
                            m.online.toString(),
                            d.code,
                            "0x${d.status.toString(16)}",
                        ),
                    )
                }
                state.asBuiltJson?.let { json ->
                    appendLine(
                        csvRow(
                            "asbuilt",
                            m.id,
                            m.name,
                            "0x%03X".format(m.txId),
                            m.bus.name,
                            m.online.toString(),
                            "json",
                            json,
                        ),
                    )
                }
            }
        }
    }
    return TabExportPayload(plain, csv.trimEnd(), "forddiag-ecus")
}

private fun exportDtcs(groups: List<ModuleDtcGroup>): TabExportPayload {
    val plain = buildString {
        groups.forEach { group ->
            appendLine(group.moduleName)
            appendLine(
                "%s · 0x%03X · %s".format(group.moduleId, group.txId, group.bus.name),
            )
            when {
                group.error != null -> appendLine(group.error)
                group.dtcs.isEmpty() -> appendLine("No DTCs")
                else -> group.dtcs.forEach { d ->
                    appendLine(DtcCatalog.formatDisplay(d))
                }
            }
            appendLine()
        }
    }.trimEnd()

    val csv = buildString {
        appendLine(
            csvRow(
                "moduleId",
                "moduleName",
                "txId",
                "bus",
                "code",
                "name",
                "status",
                "statusFlags",
                "description",
                "display",
            ),
        )
        groups.forEach { group ->
            when {
                group.error != null -> appendLine(
                    csvRow(
                        group.moduleId,
                        group.moduleName,
                        "0x%03X".format(group.txId),
                        group.bus.name,
                        "ERROR",
                        "",
                        group.error,
                        "",
                        "",
                        "",
                    ),
                )
                group.dtcs.isEmpty() -> appendLine(
                    csvRow(
                        group.moduleId,
                        group.moduleName,
                        "0x%03X".format(group.txId),
                        group.bus.name,
                        "",
                        "",
                        "No DTCs",
                        "",
                        "",
                        "",
                    ),
                )
                else -> group.dtcs.forEach { d ->
                    val info = DtcCatalog.lookup(d.code)
                    appendLine(
                        csvRow(
                            group.moduleId,
                            group.moduleName,
                            "0x%03X".format(group.txId),
                            group.bus.name,
                            d.code,
                            info?.name ?: "Unknown",
                            "0x${d.status.toString(16)}",
                            formatDtcStatus(d.status),
                            info?.description.orEmpty(),
                            DtcCatalog.formatDisplay(d),
                        ),
                    )
                }
            }
        }
    }
    return TabExportPayload(plain, csv.trimEnd(), "forddiag-dtcs")
}

private fun exportLive(stream: List<LiveSample>): TabExportPayload {
    val plain = stream.joinToString("\n") { formatLiveSampleLine(it) }
    val csv = buildString {
        appendLine(csvRow("source", "id", "value", "moduleId"))
        stream.forEach { sample ->
            val source = if (sample.source == LiveSource.PID) "PID" else "DID"
            val id = if (sample.source == LiveSource.PID) {
                "%02X".format(sample.id)
            } else {
                "0x%04X".format(sample.id)
            }
            appendLine(
                csvRow(
                    source,
                    id,
                    sample.decoded ?: sample.rawHex,
                    sample.moduleId.orEmpty(),
                ),
            )
        }
    }
    return TabExportPayload(plain, csv.trimEnd(), "forddiag-live")
}

private fun exportDiscover(state: UiState, mode: DiscoverExportMode): TabExportPayload =
    when (mode) {
        DiscoverExportMode.SNIFF -> exportDiscoverSniff(state)
        DiscoverExportMode.DID -> exportDiscoverDid(state)
    }

private fun exportDiscoverSniff(state: UiState): TabExportPayload {
    val visibleFrames = state.frames.takeLast(40).asReversed()
    val plain = buildString {
        if (visibleFrames.isNotEmpty()) {
            appendLine("FRAMES (${state.frames.size})")
            visibleFrames.forEach { appendLine(formatFrameLine(it)) }
        }
        if (state.candidates.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("CANDIDATES")
            state.candidates.forEach { appendLine(formatCandidateLine(it)) }
        }
    }.trimEnd()

    val csv = buildString {
        if (state.frames.isNotEmpty()) {
            appendLine(csvRow("section", "canId", "data", "extra"))
            state.frames.forEach { frame ->
                appendLine(
                    csvRow(
                        "frame",
                        "0x%03X".format(frame.id),
                        frame.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                        "",
                    ),
                )
            }
        }
        if (state.candidates.isNotEmpty()) {
            if (state.frames.isEmpty()) {
                appendLine(csvRow("section", "canId", "data", "extra"))
            }
            state.candidates.forEach { c ->
                appendLine(
                    csvRow(
                        "candidate",
                        "0x%03X".format(c.canId),
                        "byte[%d]%s".format(c.byteIndex, c.bitIndex?.let { " bit$it" } ?: ""),
                        "%.2f".format(c.changeScore),
                    ),
                )
            }
        }
    }
    return TabExportPayload(plain, csv.trimEnd(), "forddiag-discover-sniff")
}

private fun exportDiscoverDid(state: UiState): TabExportPayload {
    val plain = buildString {
        if (state.sweptDids.isNotEmpty()) {
            appendLine("FOUND DIDs (${state.sweptDids.size})")
            state.sweptDids.forEach { did ->
                appendLine("0x%04X\t%s".format(did.did, did.ascii ?: did.hex))
            }
        }
        if (state.didDiffs.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("DIFF")
            state.didDiffs.forEach { (did, before, after) ->
                appendLine(
                    "0x%04X  %s → %s".format(did, before.ifEmpty { "—" }, after),
                )
            }
        }
    }.trimEnd()

    val csv = buildString {
        appendLine(csvRow("section", "did", "before", "after_or_value"))
        state.sweptDids.forEach { did ->
            appendLine(
                csvRow(
                    "did",
                    "0x%04X".format(did.did),
                    "",
                    did.ascii ?: did.hex,
                ),
            )
        }
        state.didDiffs.forEach { (did, before, after) ->
            appendLine(
                csvRow(
                    "diff",
                    "0x%04X".format(did),
                    before.ifEmpty { "—" },
                    after,
                ),
            )
        }
    }
    return TabExportPayload(plain, csv.trimEnd(), "forddiag-discover-did")
}
