package com.forddiag.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ObdDashboardScope
import com.forddiag.android.ui.theme.Accent
import com.forddiag.android.ui.theme.Bg
import com.forddiag.android.ui.theme.BgDeep
import com.forddiag.android.ui.theme.BgElevated
import com.forddiag.android.ui.theme.Line
import com.forddiag.android.ui.theme.TextMuted
import com.forddiag.android.ui.theme.TextPrimary
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class ObdDashboardDialog { None, Save, Rename }

@Composable
fun ObdScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onToggleRecord: (Boolean) -> Unit,
    onToggleSignal: (ObdSignalId) -> Unit,
    onStartGauges: () -> Unit,
    onStartChart: (ObdSignalId) -> Unit,
    onCloseFullscreen: () -> Unit,
    onConsumePendingCsv: () -> Unit,
    onApplyDashboard: (String, ObdDashboardScope) -> Unit,
    onSaveDashboard: (String, ObdDashboardScope) -> Unit,
    onUpdateActiveDashboard: () -> Unit,
    onRenameActiveDashboard: (String) -> Unit,
    onDeleteActiveDashboard: () -> Unit,
    onCopyGlobalDashboardToProfile: () -> Unit,
    onSetFavoriteDashboard: (String, ObdDashboardScope) -> Unit,
    onClearFavoriteDashboard: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(state.obdPendingCsv) {
        val csv = state.obdPendingCsv ?: return@LaunchedEffect
        shareFile(
            context,
            exportCsvFilename("forddiag-obd-live"),
            csv,
            "text/csv",
            "Share OBD CSV",
        )
        onConsumePendingCsv()
    }

    when (state.obdMode) {
        ObdUiMode.LIST -> ObdListScreen(
            state = state,
            onRefresh = onRefresh,
            onToggleRecord = onToggleRecord,
            onToggleSignal = onToggleSignal,
            onStartGauges = onStartGauges,
            onStartChart = onStartChart,
            onApplyDashboard = onApplyDashboard,
            onSaveDashboard = onSaveDashboard,
            onUpdateActiveDashboard = onUpdateActiveDashboard,
            onRenameActiveDashboard = onRenameActiveDashboard,
            onDeleteActiveDashboard = onDeleteActiveDashboard,
            onCopyGlobalDashboardToProfile = onCopyGlobalDashboardToProfile,
            onSetFavoriteDashboard = onSetFavoriteDashboard,
            onClearFavoriteDashboard = onClearFavoriteDashboard,
        )
        ObdUiMode.GAUGES -> ObdGaugesFullscreen(
            signals = state.obdSignals.filter { it.selected },
            recording = state.obdRecordEnabled,
            onClose = onCloseFullscreen,
        )
        ObdUiMode.CHART -> {
            val key = state.obdChartKey
            val row = state.obdSignals.find { it.key == key }
            ObdChartFullscreen(
                label = row?.label ?: "Signal",
                unit = row?.unit,
                points = state.obdChartPoints,
                onClose = onCloseFullscreen,
            )
        }
    }
}

@Composable
private fun ObdExportButton(state: UiState) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val payload = buildTabExport(AppTab.OBD, state)
            shareFile(
                context,
                exportCsvFilename(payload.filenamePrefix),
                payload.csv,
                "text/csv",
                "Export CSV",
            )
        },
        enabled = tabExportAvailable(AppTab.OBD, state),
    ) {
        Text("Export", maxLines = 1)
    }
}

@Composable
private fun ObdListScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onToggleRecord: (Boolean) -> Unit,
    onToggleSignal: (ObdSignalId) -> Unit,
    onStartGauges: () -> Unit,
    onStartChart: (ObdSignalId) -> Unit,
    onApplyDashboard: (String, ObdDashboardScope) -> Unit,
    onSaveDashboard: (String, ObdDashboardScope) -> Unit,
    onUpdateActiveDashboard: () -> Unit,
    onRenameActiveDashboard: (String) -> Unit,
    onDeleteActiveDashboard: () -> Unit,
    onCopyGlobalDashboardToProfile: () -> Unit,
    onSetFavoriteDashboard: (String, ObdDashboardScope) -> Unit,
    onClearFavoriteDashboard: () -> Unit,
) {
    val selectedCount = state.obdSignals.count { it.selected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("OBD", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Mode 01 PIDs + UDS DIDs · live gauges & charts",
                color = TextMuted,
                fontSize = 13.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObdExportButton(state = state)
            OutlinedButton(onClick = onRefresh, enabled = state.busy == null) {
                Text("Refresh", maxLines = 1)
            }
            Button(
                onClick = onStartGauges,
                enabled = state.busy == null && selectedCount > 0 && !state.obdPolling,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
            ) {
                Text("Start ($selectedCount)", maxLines = 1)
            }
        }

        ObdDashboardBar(
            state = state,
            onApplyDashboard = onApplyDashboard,
            onSaveDashboard = onSaveDashboard,
            onUpdateActiveDashboard = onUpdateActiveDashboard,
            onRenameActiveDashboard = onRenameActiveDashboard,
            onDeleteActiveDashboard = onDeleteActiveDashboard,
            onCopyGlobalDashboardToProfile = onCopyGlobalDashboardToProfile,
            onSetFavoriteDashboard = onSetFavoriteDashboard,
            onClearFavoriteDashboard = onClearFavoriteDashboard,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Grabar CSV", color = TextPrimary, fontSize = 14.sp)
                Text(
                    "Al cerrar gauges se comparte el log",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = state.obdRecordEnabled,
                onCheckedChange = onToggleRecord,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BgDeep,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = BgElevated,
                ),
            )
        }

        when {
            !state.obdCatalogLoaded && state.busy != null -> {
                Text("Loading signals…", color = TextMuted)
            }
            state.obdSignals.isEmpty() -> {
                Text(
                    "No signals yet. Connect, then tap Refresh. " +
                        "PIDs come from the vehicle; DIDs from the profile, pins and sweeps.",
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.obdSignals, key = { signalKey(it.key) }) { row ->
                        ObdSignalListRow(
                            row = row,
                            onToggle = { onToggleSignal(row.key) },
                            onChart = { onStartChart(row.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObdSignalListRow(
    row: ObdSignalRow,
    onToggle: () -> Unit,
    onChart: () -> Unit,
) {
    val chartEnabled = row.chartable || row.key.source == LiveSource.PID || row.lastValue != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (row.selected) BgElevated else Bg, RoundedCornerShape(6.dp))
            .border(1.dp, if (row.selected) Accent.copy(alpha = 0.5f) else Line, RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.confirmed) {
                    Text(
                        " scan",
                        color = Accent,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            val meta = buildString {
                append(
                    when (row.key.source) {
                        LiveSource.PID -> "PID %02X".format(row.key.id)
                        LiveSource.DID -> "DID 0x%04X".format(row.key.id)
                    },
                )
                row.unit?.let { append(" · $it") }
                row.lastText?.let { append(" · $it") }
            }
            Text(meta, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = onChart,
            enabled = chartEnabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ShowChart,
                contentDescription = "Realtime chart",
                tint = if (chartEnabled) Accent else TextMuted.copy(alpha = 0.4f),
            )
        }
    }
}

private fun signalKey(id: ObdSignalId): String =
    "${id.source}:${id.moduleId ?: ""}:${id.id}"

@Composable
private fun ObdDashboardBar(
    state: UiState,
    onApplyDashboard: (String, ObdDashboardScope) -> Unit,
    onSaveDashboard: (String, ObdDashboardScope) -> Unit,
    onUpdateActiveDashboard: () -> Unit,
    onRenameActiveDashboard: (String) -> Unit,
    onDeleteActiveDashboard: () -> Unit,
    onCopyGlobalDashboardToProfile: () -> Unit,
    onSetFavoriteDashboard: (String, ObdDashboardScope) -> Unit,
    onClearFavoriteDashboard: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(ObdDashboardDialog.None) }
    var nameDraft by remember { mutableStateOf("") }
    var saveScope by remember { mutableStateOf(ObdDashboardScope.PROFILE) }

    val activeName = remember(
        state.obdActiveDashboardId,
        state.obdActiveDashboardScope,
        state.obdDashboardsProfile,
        state.obdDashboardsGlobal,
    ) {
        val id = state.obdActiveDashboardId ?: return@remember null
        val scope = state.obdActiveDashboardScope ?: return@remember null
        val list = when (scope) {
            ObdDashboardScope.PROFILE -> state.obdDashboardsProfile
            ObdDashboardScope.GLOBAL -> state.obdDashboardsGlobal
        }
        list.find { it.id == id }?.name
    }

    val hasActive = state.obdActiveDashboardId != null && state.obdActiveDashboardScope != null
    val favoriteMatchesActive = state.obdFavorite != null &&
        state.obdFavorite.dashboardId == state.obdActiveDashboardId &&
        state.obdFavorite.scope == state.obdActiveDashboardScope
    val canSaveProfile = !state.selectedProfileId.isNullOrBlank()
    val canCopyToProfile = hasActive &&
        state.obdActiveDashboardScope == ObdDashboardScope.GLOBAL &&
        canSaveProfile

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { pickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    activeName ?: "Dashboards",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                if (state.obdDashboardsProfile.isEmpty() && state.obdDashboardsGlobal.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No dashboards yet — save a selection") },
                        onClick = { pickerOpen = false },
                        enabled = false,
                    )
                } else {
                    if (state.obdDashboardsProfile.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text("This profile", color = TextMuted, fontSize = 12.sp)
                            },
                            onClick = {},
                            enabled = false,
                        )
                        state.obdDashboardsProfile.forEach { dash ->
                            DropdownMenuItem(
                                text = {
                                    Text("${dash.name} (${dash.signalCount})")
                                },
                                onClick = {
                                    pickerOpen = false
                                    onApplyDashboard(dash.id, ObdDashboardScope.PROFILE)
                                },
                            )
                        }
                    }
                    if (state.obdDashboardsGlobal.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text("Global", color = TextMuted, fontSize = 12.sp)
                            },
                            onClick = {},
                            enabled = false,
                        )
                        state.obdDashboardsGlobal.forEach { dash ->
                            DropdownMenuItem(
                                text = {
                                    Text("${dash.name} (${dash.signalCount})")
                                },
                                onClick = {
                                    pickerOpen = false
                                    onApplyDashboard(dash.id, ObdDashboardScope.GLOBAL)
                                },
                            )
                        }
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { actionsOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Dashboard actions",
                    tint = TextMuted,
                )
            }
            DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Save selection as…") },
                    onClick = {
                        actionsOpen = false
                        nameDraft = ""
                        saveScope = if (canSaveProfile) {
                            ObdDashboardScope.PROFILE
                        } else {
                            ObdDashboardScope.GLOBAL
                        }
                        dialog = ObdDashboardDialog.Save
                    },
                )
                DropdownMenuItem(
                    text = { Text("Update active") },
                    enabled = hasActive,
                    onClick = {
                        actionsOpen = false
                        onUpdateActiveDashboard()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename active") },
                    enabled = hasActive,
                    onClick = {
                        actionsOpen = false
                        nameDraft = activeName.orEmpty()
                        dialog = ObdDashboardDialog.Rename
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy global → profile") },
                    enabled = canCopyToProfile,
                    onClick = {
                        actionsOpen = false
                        onCopyGlobalDashboardToProfile()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete active") },
                    enabled = hasActive,
                    onClick = {
                        actionsOpen = false
                        onDeleteActiveDashboard()
                    },
                )
                if (favoriteMatchesActive) {
                    DropdownMenuItem(
                        text = { Text("Clear favorite") },
                        onClick = {
                            actionsOpen = false
                            onClearFavoriteDashboard()
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Set as favorite") },
                        enabled = hasActive,
                        onClick = {
                            actionsOpen = false
                            val id = state.obdActiveDashboardId ?: return@DropdownMenuItem
                            val scope = state.obdActiveDashboardScope ?: return@DropdownMenuItem
                            onSetFavoriteDashboard(id, scope)
                        },
                    )
                }
            }
        }
    }

    when (dialog) {
        ObdDashboardDialog.None -> Unit
        ObdDashboardDialog.Save -> {
            AlertDialog(
                onDismissRequest = { dialog = ObdDashboardDialog.None },
                title = { Text("Save dashboard") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Scope", color = TextMuted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = saveScope == ObdDashboardScope.PROFILE,
                                onClick = { saveScope = ObdDashboardScope.PROFILE },
                                enabled = canSaveProfile,
                                colors = RadioButtonDefaults.colors(selectedColor = Accent),
                            )
                            Text("This profile", color = if (canSaveProfile) TextPrimary else TextMuted)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = saveScope == ObdDashboardScope.GLOBAL,
                                onClick = { saveScope = ObdDashboardScope.GLOBAL },
                                colors = RadioButtonDefaults.colors(selectedColor = Accent),
                            )
                            Text("Global", color = TextPrimary)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog = ObdDashboardDialog.None
                            onSaveDashboard(nameDraft, saveScope)
                        },
                        enabled = nameDraft.isNotBlank(),
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = ObdDashboardDialog.None }) {
                        Text("Cancel")
                    }
                },
            )
        }
        ObdDashboardDialog.Rename -> {
            AlertDialog(
                onDismissRequest = { dialog = ObdDashboardDialog.None },
                title = { Text("Rename dashboard") },
                text = {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog = ObdDashboardDialog.None
                            onRenameActiveDashboard(nameDraft)
                        },
                        enabled = nameDraft.isNotBlank(),
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = ObdDashboardDialog.None }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun ObdGaugesFullscreen(
    signals: List<ObdSignalRow>,
    recording: Boolean,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, start = 4.dp, end = 4.dp, bottom = 4.dp),
        ) {
            val n = max(1, signals.size)
            val aspect = maxWidth / maxHeight
            val cols = adaptiveColumns(n, aspect)
            val rows = ceil(n.toDouble() / cols).toInt()
            val cellW = maxWidth / cols
            val cellH = maxHeight / rows
            val density = LocalDensity.current
            val minSidePx = with(density) {
                min(cellW.toPx(), cellH.toPx())
            }
            val valueSp = (minSidePx / 5.5f).coerceIn(14f, 56f)
            val labelSp = (valueSp * 0.28f).coerceIn(8f, 16f)
            val unitSp = (valueSp * 0.22f).coerceIn(7f, 14f)

            Column(modifier = Modifier.fillMaxSize()) {
                for (r in 0 until rows) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        for (c in 0 until cols) {
                            val index = r * cols + c
                            val weight = if (index < signals.size) {
                                expectedGaugeCharBudget(signals[index].unit).toFloat()
                            } else {
                                DEFAULT_GAUGE_CHAR_BUDGET.toFloat()
                            }
                            Box(
                                modifier = Modifier
                                    .weight(weight)
                                    .fillMaxHeight()
                                    .padding(3.dp),
                            ) {
                                if (index < signals.size) {
                                    GaugeCell(
                                        row = signals[index],
                                        valueSp = valueSp,
                                        labelSp = labelSp,
                                        unitSp = unitSp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Text("REC", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Spacer(Modifier.width(32.dp))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }
    }
}

@Composable
private fun GaugeCell(
    row: ObdSignalRow,
    valueSp: Float,
    labelSp: Float,
    unitSp: Float,
) {
    val textMeasurer = rememberTextMeasurer()
    val valueText = formatGaugeValue(row)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgElevated, RoundedCornerShape(8.dp))
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            row.label,
            color = TextMuted,
            fontSize = labelSp.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxWidthPx = constraints.maxWidth
            val fittedSp = remember(valueText, valueSp, maxWidthPx) {
                fitGaugeValueSp(
                    text = valueText,
                    baseSp = valueSp,
                    maxWidthPx = maxWidthPx,
                    textMeasurer = textMeasurer,
                )
            }
            Text(
                valueText,
                color = Accent,
                fontSize = fittedSp.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        row.unit?.let { unit ->
            Text(
                unit,
                color = TextMuted,
                fontSize = unitSp.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shrink [baseSp] until [text] fits [maxWidthPx] on one line (never ellipsize). */
private fun fitGaugeValueSp(
    text: String,
    baseSp: Float,
    maxWidthPx: Int,
    textMeasurer: TextMeasurer,
    minSp: Float = 12f,
): Float {
    if (maxWidthPx <= 0) return baseSp.coerceAtLeast(minSp)
    var sp = baseSp.coerceAtLeast(minSp)
    while (sp > minSp) {
        val width = textMeasurer.measure(
            text = text,
            style = TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
        ).size.width
        if (width <= maxWidthPx) break
        sp -= 1f
    }
    return sp
}

private fun formatGaugeValue(row: ObdSignalRow): String {
    val v = row.lastValue
    if (v != null) {
        return if (v == v.toLong().toDouble()) "%.0f".format(v) else "%.1f".format(v)
    }
    return row.lastText?.take(12) ?: "—"
}

private fun adaptiveColumns(n: Int, aspect: Float): Int {
    if (n <= 1) return 1
    if (n == 2) return if (aspect >= 1.2f) 2 else 1
    val base = ceil(sqrt(n.toDouble())).toInt()
    return when {
        aspect >= 1.6f -> min(n, max(base, base + 1))
        aspect <= 0.75f -> max(1, base - 1).coerceAtMost(n)
        else -> base.coerceIn(1, n)
    }
}

@Composable
private fun ObdChartFullscreen(
    label: String,
    unit: String?,
    points: List<ObdChartPoint>,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val latest = points.lastOrNull()?.value
                Text(
                    when {
                        latest != null && unit != null -> "%.2f %s".format(latest, unit)
                        latest != null -> "%.2f".format(latest)
                        else -> "Waiting for data…"
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg, RoundedCornerShape(8.dp))
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            if (points.size < 2) {
                Text(
                    "Collecting samples…",
                    color = TextMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ObdLineChart(points = points)
            }
        }
    }
}

@Composable
private fun ObdLineChart(points: List<ObdChartPoint>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val minV = points.minOf { it.value }
        val maxV = points.maxOf { it.value }
        val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0
        val pad = span * 0.08
        val yMin = minV - pad
        val yMax = maxV + pad
        val ySpan = yMax - yMin

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = if (points.size == 1) size.width / 2f else {
                i.toFloat() / (points.size - 1) * size.width
            }
            val y = ((yMax - p.value) / ySpan * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // baseline
        drawLine(
            color = Line,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
        drawPath(
            path = path,
            color = Accent,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )
    }
}
