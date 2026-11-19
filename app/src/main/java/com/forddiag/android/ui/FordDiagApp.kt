package com.forddiag.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forddiag.android.core.CanBus
import com.forddiag.android.core.CanFrame
import com.forddiag.android.core.DiscoveredEcu
import com.forddiag.android.core.DtcCatalog
import com.forddiag.android.core.ModuleDtcGroup
import com.forddiag.android.core.ModuleScanResult
import com.forddiag.android.core.PatternCandidate
import com.forddiag.android.core.VehicleProfile
import com.forddiag.android.ui.theme.Accent
import com.forddiag.android.ui.theme.Bg
import com.forddiag.android.ui.theme.BgDeep
import com.forddiag.android.ui.theme.BgElevated
import com.forddiag.android.ui.theme.Danger
import com.forddiag.android.ui.theme.Line
import com.forddiag.android.ui.theme.TextMuted
import com.forddiag.android.ui.theme.TextPrimary
import com.forddiag.android.ui.theme.Warn

private data class TabDef(val id: AppTab, val label: String, val needsConn: Boolean)

private val TABS = listOf(
    TabDef(AppTab.HOME, "Home", false),
    TabDef(AppTab.PROFILES, "Profiles", false),
    TabDef(AppTab.ECUS, "ECUs", true),
    TabDef(AppTab.DTCS, "DTCs", true),
    TabDef(AppTab.LIVE, "Live", true),
    TabDef(AppTab.OBD, "OBD", true),
)

@Composable
fun FordDiagApp(viewModel: FordDiagViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected = state.status != null
    val obdFullscreen = state.tab == AppTab.OBD && state.obdMode != ObdUiMode.LIST

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        if (!obdFullscreen) {
            Header(connected = connected, identity = state.status?.adapter?.identity)

            TabBar(
                tab = state.tab,
                connected = connected,
                onSelect = viewModel::selectTab,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                AppTab.HOME -> HomeScreen(
                    state = state,
                    onRefreshDevices = viewModel::refreshDevices,
                    onSelectDevice = viewModel::selectDevice,
                    onSelectProfile = viewModel::selectProfile,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onScan = viewModel::scan,
                )
                AppTab.PROFILES -> ProfilesScreen(
                    state = state,
                    onNewProfile = viewModel::newBlankProfile,
                    onSave = viewModel::saveProfile,
                    onDelete = viewModel::deleteProfile,
                    onImportAsBuilt = viewModel::importAsBuilt,
                    canDelete = viewModel::canDeleteProfile,
                    isBuiltIn = viewModel::isBuiltInProfile,
                )
                AppTab.ECUS -> if (connected) {
                    EcusScreen(
                        state = state,
                        onRescan = viewModel::scan,
                        onSelectModule = viewModel::selectModule,
                        onReadDtcs = viewModel::readModuleDtcs,
                        onDumpAsBuilt = viewModel::dumpAsBuilt,
                        onDiscover = viewModel::discoverEcus,
                        onStop = viewModel::stopActivity,
                        onDidSweep = viewModel::didSweep,
                        onSnapshot = viewModel::didSnapshot,
                        onDiff = viewModel::diffSnapshots,
                        onPinDid = viewModel::pinDid,
                    )
                }
                AppTab.DTCS -> if (connected) {
                    DtcsScreen(
                        state = state,
                        onReadAll = viewModel::readAllDtcs,
                        onClear = viewModel::clearDtcs,
                    )
                }
                AppTab.LIVE -> if (connected) {
                    LiveScreen(
                        state = state,
                        onStartSniff = viewModel::startSniff,
                        onStop = viewModel::stopActivity,
                        onStartPressSequence = viewModel::startPressSequence,
                        onCancelPressSequence = viewModel::cancelPressSequence,
                        onFrameIdFilter = viewModel::setFrameIdFilter,
                        onDismissAnalyzeEmpty = viewModel::dismissAnalyzeEmpty,
                        onDismissAnalyzeResults = viewModel::dismissAnalyzeResults,
                    )
                }
                AppTab.OBD -> if (connected) {
                    ObdScreen(
                        state = state,
                        onRefresh = viewModel::refreshObdCatalog,
                        onToggleRecord = viewModel::setObdRecordEnabled,
                        onToggleSignal = viewModel::toggleObdSignal,
                        onStartGauges = viewModel::startObdGauges,
                        onStartChart = viewModel::startObdChart,
                        onCloseFullscreen = viewModel::stopObdFullscreen,
                        onConsumePendingCsv = viewModel::consumeObdPendingCsv,
                        onApplyDashboard = viewModel::applyObdDashboard,
                        onSaveDashboard = viewModel::saveObdDashboard,
                        onUpdateActiveDashboard = viewModel::updateActiveObdDashboard,
                        onRenameActiveDashboard = viewModel::renameActiveObdDashboard,
                        onDeleteActiveDashboard = viewModel::deleteActiveObdDashboard,
                        onCopyGlobalDashboardToProfile = viewModel::copyActiveGlobalDashboardToProfile,
                        onSetFavoriteDashboard = viewModel::setObdFavoriteDashboard,
                        onClearFavoriteDashboard = viewModel::clearObdFavoriteDashboard,
                    )
                }
            }
        }

        if (!obdFullscreen) {
            Footer(busy = state.busy, error = state.error)
        }
    }
}

@Composable
private fun TabBar(tab: AppTab, connected: Boolean, onSelect: (AppTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, Line)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEach { t ->
            val enabled = !t.needsConn || connected
            val selected = tab == t.id
            Text(
                t.label,
                color = when {
                    !enabled -> TextMuted.copy(alpha = 0.4f)
                    selected -> Accent
                    else -> TextMuted
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { onSelect(t.id) }
                    .background(if (selected) Bg else BgDeep)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Header(connected: Boolean, identity: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("FordDiag", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Android · OBD", color = TextMuted, fontSize = 13.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (identity != null) {
                Text(
                    identity,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 200.dp),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Text(
                if (connected) "Connected" else "Disconnected",
                color = if (connected) Accent else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (connected) Accent.copy(alpha = 0.2f) else BgElevated)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ScreenToolbar(
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 13.sp)
        }
        ActionBar(content = actions)
    }
}

@Composable
private fun ExportMenuButton(
    enabled: Boolean,
    onCopy: () -> Unit,
    onExportCsv: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Text("Export", maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Copy to clipboard") },
                onClick = {
                    expanded = false
                    onCopy()
                },
            )
            DropdownMenuItem(
                text = { Text("Export CSV") },
                onClick = {
                    expanded = false
                    onExportCsv()
                },
            )
        }
    }
}

@Composable
private fun TabExportButton(
    tab: AppTab,
    state: UiState,
) {
    val context = LocalContext.current
    ExportMenuButton(
        enabled = tabExportAvailable(tab, state),
        onCopy = {
            val payload = buildTabExport(tab, state)
            copyTextToClipboard(context, "FordDiag $tab", payload.plainText)
        },
        onExportCsv = {
            val payload = buildTabExport(tab, state)
            shareFile(
                context,
                exportCsvFilename(payload.filenamePrefix),
                payload.csv,
                "text/csv",
                "Export CSV",
            )
        },
    )
}

@Composable
private fun Footer(busy: String?, error: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(busy ?: "Ready", color = TextMuted, fontSize = 12.sp)
        if (error != null) {
            Text(error, color = Danger, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: UiState,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
) {
    val connected = state.status != null
    val profile = state.profiles.find { it.id == state.selectedProfileId }
    var deviceExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("FordDiag", color = TextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(
            "Open-source Ford module diagnostics. Pair your OBDLink MX / MX+, pick a profile, scan ECUs on HS-CAN and MS-CAN.",
            color = TextMuted,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BLUETOOTH DEVICE", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            ExposedDropdownMenuBox(expanded = deviceExpanded, onExpandedChange = { deviceExpanded = it }) {
                val selected = state.devices.find { it.address == state.selectedAddress }
                OutlinedTextField(
                    value = selected?.let { "${it.name} (${it.address})" } ?: "No paired devices",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !connected,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(deviceExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = fieldColors(),
                )
                ExposedDropdownMenu(
                    expanded = deviceExpanded,
                    onDismissRequest = { deviceExpanded = false },
                ) {
                    state.devices.forEach { d ->
                        DropdownMenuItem(
                            text = { Text("${d.name}\n${d.address}") },
                            onClick = {
                                onSelectDevice(d.address)
                                deviceExpanded = false
                            },
                        )
                    }
                }
            }
            TextButton(onClick = onRefreshDevices) { Text("Refresh paired devices", color = Accent) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VEHICLE PROFILE", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            ExposedDropdownMenuBox(expanded = profileExpanded, onExpandedChange = { profileExpanded = it }) {
                OutlinedTextField(
                    value = profile?.name ?: "—",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = fieldColors(),
                )
                ExposedDropdownMenu(expanded = profileExpanded, onDismissRequest = { profileExpanded = false }) {
                    state.profiles.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                onSelectProfile(p.id)
                                profileExpanded = false
                            },
                        )
                    }
                }
            }
            profile?.notes?.let { Text(it, color = TextMuted, fontSize = 13.sp) }
        }

        if (!connected) {
            Button(
                onClick = onConnect,
                enabled = state.selectedAddress != null && state.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        } else {
            ActionBar {
                Button(
                    onClick = onScan,
                    enabled = state.busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
                ) {
                    Text("Scan modules", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onDisconnect, enabled = state.busy == null) {
                    Text("Disconnect", maxLines = 1, softWrap = false)
                }
            }
        }

        state.status?.let { status ->
            ActionBar {
                TabExportButton(tab = AppTab.HOME, state = state)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Line)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusRow("Adapter", status.adapter.identity)
                StatusRow(
                    "VIN",
                    status.vin ?: status.vinError?.let { "— ($it)" } ?: "—",
                )
                StatusRow(
                    "MS-CAN",
                    if (status.msCanCapable) {
                        "Supported · ${status.adapter.msCanHint.ifBlank { "STN/OBDLink" }}"
                    } else {
                        "Limited · ${status.adapter.msCanHint.ifBlank { "ELM-only" }}"
                    },
                )
                status.adapter.voltage?.let { StatusRow("Voltage", "%.1fV".format(it)) }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Line,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    disabledTextColor = TextMuted,
    focusedContainerColor = BgElevated,
    unfocusedContainerColor = BgElevated,
    disabledContainerColor = Bg,
)

@Composable
private fun ProfilesScreen(
    state: UiState,
    onNewProfile: () -> VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDelete: (String) -> Unit,
    onImportAsBuilt: (
        android.net.Uri,
        VehicleProfile,
        (Result<Pair<VehicleProfile, String>>) -> Unit,
    ) -> Unit,
    canDelete: (String) -> Boolean,
    isBuiltIn: (String) -> Boolean,
) {
    var editing by remember { mutableStateOf<VehicleProfile?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var importSummary by remember { mutableStateOf<String?>(null) }

    if (editing != null) {
        ProfileEditorScreen(
            draft = editing!!,
            isNew = isNew,
            busy = state.busy != null,
            importSummary = importSummary,
            onDraftChange = { editing = it },
            onImportSummary = { importSummary = it },
            onSave = {
                onSave(editing!!)
                editing = null
                isNew = false
                importSummary = null
            },
            onCancel = {
                editing = null
                isNew = false
                importSummary = null
            },
            onImportAsBuilt = onImportAsBuilt,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenToolbar(
            title = "Profiles",
            subtitle = "Edit vehicle profiles · import Ford As-Built (.ab)",
        ) {
            Button(
                onClick = {
                    editing = onNewProfile()
                    isNew = true
                    importSummary = null
                },
                enabled = state.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
            ) { Text("Add", maxLines = 1, softWrap = false) }
        }

        if (state.profiles.isEmpty()) {
            Text("No profiles loaded.", color = TextMuted)
        }

        state.profiles.forEach { profile ->
            val onDisk = profile.id in state.userProfileIds
            val badge = when {
                isBuiltIn(profile.id) && onDisk -> "built-in · edited"
                isBuiltIn(profile.id) -> "built-in"
                else -> "user"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Line)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            "${profile.id} · ${profile.years} · ${profile.modules.size} modules",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(badge, color = Accent, fontSize = 11.sp)
                    }
                }
                ActionBar {
                    OutlinedButton(
                        onClick = {
                            editing = profile
                            isNew = false
                            importSummary = null
                        },
                        enabled = state.busy == null,
                    ) { Text("Edit", maxLines = 1, softWrap = false) }
                    if (canDelete(profile.id)) {
                        OutlinedButton(
                            onClick = { deleteConfirmId = profile.id },
                            enabled = state.busy == null,
                        ) { Text("Delete", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }

    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Delete profile?") },
            text = { Text("Remove the user copy of $id from device storage?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    deleteConfirmId = null
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileEditorScreen(
    draft: VehicleProfile,
    isNew: Boolean,
    busy: Boolean,
    importSummary: String?,
    onDraftChange: (VehicleProfile) -> Unit,
    onImportSummary: (String?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onImportAsBuilt: (
        android.net.Uri,
        VehicleProfile,
        (Result<Pair<VehicleProfile, String>>) -> Unit,
    ) -> Unit,
) {
    val pickAb = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImportAsBuilt(uri, draft) { result ->
            result.onSuccess { (updated, summary) ->
                onDraftChange(updated)
                onImportSummary(summary)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenToolbar(
            title = if (isNew) "New profile" else "Edit profile",
            subtitle = draft.id,
        ) {
            OutlinedButton(onClick = onCancel, enabled = !busy) {
                Text("Cancel", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = { pickAb.launch(arrayOf("*/*")) },
                enabled = !busy,
            ) { Text("Import .ab", maxLines = 1, softWrap = false) }
            Button(
                onClick = onSave,
                enabled = !busy && draft.name.isNotBlank() && draft.modules.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
            ) { Text("Save", maxLines = 1, softWrap = false) }
        }

        if (isNew) {
            OutlinedTextField(
                value = draft.id,
                onValueChange = { onDraftChange(draft.copy(id = it.trim())) },
                label = { Text("Id") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
                singleLine = true,
            )
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.years,
            onValueChange = { onDraftChange(draft.copy(years = it)) },
            label = { Text("Years") },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.notes.orEmpty(),
            onValueChange = { onDraftChange(draft.copy(notes = it.ifBlank { null })) },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            minLines = 2,
        )

        importSummary?.let {
            Text(it, color = Accent, fontSize = 13.sp)
        }

        Text(
            "MODULES (${draft.modules.size})",
            color = TextMuted,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
        draft.modules.forEach { m ->
            val abCount = m.dids.count { it.asBuilt }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Line)
                    .padding(10.dp),
            ) {
                Text(m.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    "%s · 0x%03X · %s · %d as-built DIDs".format(m.id, m.txId, m.bus, abCount),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun EcusScreen(
    state: UiState,
    onRescan: () -> Unit,
    onSelectModule: (String) -> Unit,
    onReadDtcs: (String) -> Unit,
    onDumpAsBuilt: (String) -> Unit,
    onDiscover: (CanBus, Int, Int) -> Unit,
    onStop: () -> Unit,
    onDidSweep: (String, Int, Int) -> Unit,
    onSnapshot: (String, Int, Int, String) -> Unit,
    onDiff: () -> Unit,
    onPinDid: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    var bus by remember { mutableStateOf(CanBus.HS) }
    var fromId by remember { mutableStateOf("7E0") }
    var toId by remember { mutableStateOf("7EF") }
    val profile = state.profiles.find { it.id == state.selectedProfileId }
    var moduleId by remember { mutableStateOf(profile?.modules?.firstOrNull()?.id ?: "PCM") }
    var fromDid by remember { mutableStateOf("F190") }
    var toDid by remember { mutableStateOf("F1FF") }
    var moduleExpanded by remember { mutableStateOf(false) }
    val parseDid = { s: String ->
        s.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenToolbar(
            title = "ECUs",
            subtitle = "${state.status?.vin ?: "VIN unknown"} · ${state.modules.count { it.online }}/${state.modules.size} online · ${state.discovered.size} discovered",
        ) {
            TabExportButton(tab = AppTab.ECUS, state = state)
            if (state.discoverProgress != null || state.busy != null) {
                OutlinedButton(onClick = onStop) {
                    Text("Stop", maxLines = 1, softWrap = false)
                }
            }
            Button(
                onClick = onRescan,
                enabled = state.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
            ) { Text("Rescan", maxLines = 1, softWrap = false) }
        }

        // Discovery controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("DISCOVER RANGE", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            ActionBar {
                BusChip("HS", bus == CanBus.HS) { bus = CanBus.HS }
                BusChip("MS", bus == CanBus.MS) { bus = CanBus.MS }
                OutlinedTextField(
                    value = fromId,
                    onValueChange = { fromId = it },
                    label = { Text("From") },
                    modifier = Modifier.width(90.dp),
                    colors = fieldColors(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = toId,
                    onValueChange = { toId = it },
                    label = { Text("To") },
                    modifier = Modifier.width(90.dp),
                    colors = fieldColors(),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val from = fromId.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0x7E0
                        val to = toId.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0x7EF
                        onDiscover(bus, from, to)
                    },
                    enabled = state.busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
                ) { Text("Discover", maxLines = 1, softWrap = false) }
            }
            state.discoverProgress?.let { p ->
                Text(
                    "Scanning 0x%03X · found %d".format(p.currentId, p.found),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // DID sweep
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("DID SWEEP", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(expanded = moduleExpanded, onExpandedChange = { moduleExpanded = it }) {
                OutlinedTextField(
                    value = moduleId,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Module") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(moduleExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = fieldColors(),
                )
                ExposedDropdownMenu(expanded = moduleExpanded, onDismissRequest = { moduleExpanded = false }) {
                    profile?.modules?.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.id} — ${m.name}") },
                            onClick = {
                                moduleId = m.id
                                moduleExpanded = false
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromDid,
                    onValueChange = { fromDid = it },
                    label = { Text("From DID") },
                    modifier = Modifier.weight(1f),
                    colors = fieldColors(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = toDid,
                    onValueChange = { toDid = it },
                    label = { Text("To DID") },
                    modifier = Modifier.weight(1f),
                    colors = fieldColors(),
                    singleLine = true,
                )
            }
            ActionBar {
                Button(
                    onClick = { onDidSweep(moduleId, parseDid(fromDid), parseDid(toDid)) },
                    enabled = state.busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
                ) { Text("Sweep", maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { onSnapshot(moduleId, parseDid(fromDid), parseDid(toDid), "before") },
                    enabled = state.busy == null,
                ) { Text("Before", maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { onSnapshot(moduleId, parseDid(fromDid), parseDid(toDid), "after") },
                    enabled = state.busy == null,
                ) { Text("After", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = onDiff) {
                    Text("Diff", maxLines = 1, softWrap = false)
                }
            }
            if (state.sweptDids.isNotEmpty()) {
                Text("FOUND DIDs (${state.sweptDids.size})", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
                state.sweptDids.forEach { did ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPinDid(moduleId, did.did) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("0x%04X".format(did.did), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(
                            did.ascii ?: did.hex,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                }
            }
            if (state.didDiffs.isNotEmpty()) {
                Text("DIFF", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
                state.didDiffs.forEach { (did, before, after) ->
                    Text(
                        "0x%04X  %s → %s".format(did, before.ifEmpty { "—" }, after),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (state.discovered.isNotEmpty()) {
            Text("DISCOVERED", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            state.discovered.forEach { ecu ->
                DiscoveredRow(ecu)
            }
        }

        if (state.modules.isEmpty()) {
            Text("Scan the profile or discover the bus.", color = TextMuted)
        } else {
            state.modules.forEach { m ->
                ModuleCard(
                    module = m,
                    selected = m.id == state.selectedModuleId,
                    dtcs = if (m.id == state.selectedModuleId) state.moduleDtcs else emptyList(),
                    asBuiltJson = if (m.id == state.selectedModuleId) state.asBuiltJson else null,
                    busy = state.busy != null,
                    onSelect = { onSelectModule(m.id) },
                    onReadDtcs = { onReadDtcs(m.id) },
                    onDumpAsBuilt = { onDumpAsBuilt(m.id) },
                    onShareJson = { json ->
                        shareJson(context, "${m.id}-asbuilt.json", json, "Share As-Built")
                    },
                )
            }
        }
    }
}

@Composable
private fun BusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) BgDeep else TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Accent else BgElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun DiscoveredRow(ecu: DiscoveredEcu) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line)
            .padding(10.dp),
    ) {
        Text(
            "0x%03X · %s-CAN%s".format(
                ecu.txId,
                ecu.bus.name,
                ecu.name?.let { " · $it" } ?: "",
            ),
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        ecu.identity.firstOrNull()?.let {
            Text(it.ascii ?: it.hex, color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ModuleCard(
    module: ModuleScanResult,
    selected: Boolean,
    dtcs: List<com.forddiag.android.core.Dtc>,
    asBuiltJson: String?,
    busy: Boolean,
    onSelect: () -> Unit,
    onReadDtcs: () -> Unit,
    onDumpAsBuilt: () -> Unit,
    onShareJson: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Accent else Line)
            .background(if (selected) BgElevated else Bg)
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (module.online) Accent else TextMuted),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(module.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    "%s · 0x%03X · %s".format(module.id, module.txId, module.bus.name),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (!module.online && module.error != null) {
            Text(module.error, color = Warn, fontSize = 12.sp)
        }
        if (module.identity.isNotEmpty()) {
            module.identity.forEach { d ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0x%04X".format(d.did), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        d.ascii ?: d.hex,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (module.online && selected) {
            ActionBar {
                OutlinedButton(onClick = onReadDtcs, enabled = !busy) {
                    Text("Read DTCs", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onDumpAsBuilt, enabled = !busy) {
                    Text("Dump As-Built", maxLines = 1, softWrap = false)
                }
            }
            if (dtcs.isNotEmpty()) {
                dtcs.forEach { d ->
                    Text(
                        DtcCatalog.formatDisplay(d),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            asBuiltJson?.let { json ->
                Text(json, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 8)
                OutlinedButton(onClick = { onShareJson(json) }) { Text("Share JSON") }
            }
        }
    }
}

@Composable
private fun DtcsScreen(
    state: UiState,
    onReadAll: () -> Unit,
    onClear: (String) -> Unit,
) {
    var confirmModule by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenToolbar(
            title = "DTCs",
            subtitle = "Read / clear diagnostic trouble codes per module",
        ) {
            TabExportButton(tab = AppTab.DTCS, state = state)
            Button(
                onClick = onReadAll,
                enabled = state.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
            ) { Text("Read all", maxLines = 1, softWrap = false) }
        }

        if (state.dtcGroups.isEmpty()) {
            Text("Tap Read all to query every module in the profile.", color = TextMuted)
        }

        state.dtcGroups.forEach { group ->
            DtcGroupCard(group = group, busy = state.busy != null, onClear = { confirmModule = group.moduleId })
        }
    }

    confirmModule?.let { moduleId ->
        AlertDialog(
            onDismissRequest = { confirmModule = null },
            title = { Text("Clear DTCs?") },
            text = { Text("Clear all DTCs on $moduleId? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear(moduleId)
                    confirmModule = null
                }) { Text("Clear", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmModule = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DtcGroupCard(group: ModuleDtcGroup, busy: Boolean, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(group.moduleName, color = TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    "%s · 0x%03X · %s".format(group.moduleId, group.txId, group.bus.name),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (group.online) {
                OutlinedButton(onClick = onClear, enabled = !busy) { Text("Clear") }
            }
        }
        when {
            group.error != null -> Text(group.error, color = Warn, fontSize = 12.sp)
            group.dtcs.isEmpty() -> Text("No DTCs", color = TextMuted, fontSize = 13.sp)
            else -> group.dtcs.forEach { d ->
                Text(
                    DtcCatalog.formatDisplay(d),
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private val LiveCandidateBlockMaxHeight = 200.dp
private val AnalyzeResultsListMaxHeight = 360.dp

@Composable
private fun LiveScreen(
    state: UiState,
    onStartSniff: (CanBus) -> Unit,
    onStop: () -> Unit,
    onStartPressSequence: () -> Unit,
    onCancelPressSequence: () -> Unit,
    onFrameIdFilter: (String) -> Unit,
    onDismissAnalyzeEmpty: () -> Unit,
    onDismissAnalyzeResults: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var bus by remember { mutableStateOf(CanBus.MS) }
    val frames = state.frames
    val pressSeq = state.pressSequence

    LaunchedEffect(frames.lastOrNull()?.tsMs, frames.size) {
        if (frames.isNotEmpty()) {
            listState.scrollToItem(frames.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenToolbar(
            title = "Live",
            subtitle = "CAN monitor · press-pattern Analyze",
        ) {
            TabExportButton(tab = AppTab.LIVE, state = state)
            BusChip("HS", bus == CanBus.HS && !state.sniffing) { if (!state.sniffing) bus = CanBus.HS }
            BusChip("MS", bus == CanBus.MS && !state.sniffing) { if (!state.sniffing) bus = CanBus.MS }
            if (!state.sniffing) {
                Button(
                    onClick = { onStartSniff(bus) },
                    enabled = state.busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDeep),
                ) { Text("Start", maxLines = 1, softWrap = false) }
            } else {
                OutlinedButton(onClick = onStop) {
                    Text("Stop", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onStartPressSequence,
                    enabled = pressSeq == null,
                ) {
                    Text("Analyze", maxLines = 1, softWrap = false)
                }
            }
        }

        OutlinedTextField(
            value = state.frameIdFilter,
            onValueChange = onFrameIdFilter,
            label = { Text("Filter ID") },
            placeholder = { Text("e.g. 3E8 or 0x3E8") },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
            singleLine = true,
        )

        val filteredCount = frames.size
        Text(
            "FRAMES ($filteredCount/${state.framesTotal})",
            color = TextMuted,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )

        if (!state.sniffing && frames.isEmpty()) {
            Text("Start monitoring to see CAN frames as they arrive.", color = TextMuted)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, Line)
                    .background(Bg),
            ) {
                itemsIndexed(
                    frames,
                    key = { index, frame -> liveFrameRowKey(index, frame) },
                ) { _, frame ->
                    FrameRow(frame)
                }
            }
        }

        if (state.candidates.isNotEmpty() && !state.analyzeResultsOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LiveCandidateBlockMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("CANDIDATES", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
                state.candidates.forEach { c ->
                    CandidateRow(
                        c = c,
                        onCopyId = {
                            val id = "0x%03X".format(c.canId)
                            copyTextToClipboard(context, "CAN ID", id)
                            onFrameIdFilter(id)
                        },
                        onExport = {
                            val payload = exportCandidatePayload(c)
                            shareFile(
                                context,
                                exportCsvFilename(payload.filenamePrefix),
                                payload.csv,
                                "text/csv",
                                "Export candidate",
                            )
                        },
                    )
                }
            }
        }
    }

    pressSeq?.let { seq ->
        AlertDialog(
            onDismissRequest = onCancelPressSequence,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
            title = {
                Text("Etapa ${seq.stageIndex + 1}/${seq.totalStages}")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(seq.stageLabel, color = TextPrimary)
                    LinearProgressIndicator(
                        progress = { seq.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Line,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onCancelPressSequence) { Text("Cancelar") }
            },
        )
    }

    if (state.analyzeEmpty) {
        AlertDialog(
            onDismissRequest = onDismissAnalyzeEmpty,
            title = { Text("Analyze") },
            text = {
                Text(
                    "No se detectó ninguna trama relevante",
                    color = TextPrimary,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissAnalyzeEmpty) { Text("Cerrar") }
            },
        )
    }

    if (state.analyzeResultsOpen) {
        AlertDialog(
            onDismissRequest = onDismissAnalyzeResults,
            title = { Text("Candidatos (${state.candidates.size})") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = AnalyzeResultsListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "Pulsa una fila para copiar el ID. Export guarda solo ese análisis.",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    itemsIndexed(
                        state.candidates,
                        key = { index, c -> "$index-${c.canId}-${c.byteIndex}-${c.bitIndex}" },
                    ) { _, c ->
                        CandidateRow(
                            c = c,
                            onCopyId = {
                                val id = "0x%03X".format(c.canId)
                                copyTextToClipboard(context, "CAN ID", id)
                                onFrameIdFilter(id)
                            },
                            onExport = {
                                val payload = exportCandidatePayload(c)
                                shareFile(
                                    context,
                                    exportCsvFilename(payload.filenamePrefix),
                                    payload.csv,
                                    "text/csv",
                                    "Export candidate",
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissAnalyzeResults) { Text("Cerrar") }
            },
        )
    }
}

@Composable
private fun FrameRow(frame: CanFrame) {
    Text(
        "0x%03X  %s".format(frame.id, frame.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }),
        color = TextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun CandidateRow(
    c: PatternCandidate,
    onCopyId: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopyId)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "0x%03X byte[%d]%s  score=%.2f".format(
                c.canId,
                c.byteIndex,
                c.bitIndex?.let { " bit$it" } ?: "",
                c.changeScore,
            ),
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExport) {
            Text("Export", fontSize = 11.sp)
        }
    }
}

private fun shareJson(context: android.content.Context, filename: String, json: String, title: String) {
    shareFile(context, filename, json, "application/json", title)
}
