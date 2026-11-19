package com.forddiag.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forddiag.android.core.AppPreferences
import com.forddiag.android.core.AsBuiltDump
import com.forddiag.android.core.AsBuiltParser
import com.forddiag.android.core.BtDeviceInfo
import com.forddiag.android.core.CanBus
import com.forddiag.android.core.CanFrame
import com.forddiag.android.core.ConnectionStatus
import com.forddiag.android.core.CaptureStage
import com.forddiag.android.core.DidDef
import com.forddiag.android.core.DidValue
import com.forddiag.android.core.DiscoveredEcu
import com.forddiag.android.core.DtcCatalog
import com.forddiag.android.core.EcuDiscoverProgress
import com.forddiag.android.core.FordExtendedPidCatalog
import com.forddiag.android.core.LiveDidPin
import com.forddiag.android.core.LiveSample
import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ModuleDef
import com.forddiag.android.core.ModuleDtcGroup
import com.forddiag.android.core.ModuleScanResult
import com.forddiag.android.core.ObdDashboard
import com.forddiag.android.core.ObdDashboardIds
import com.forddiag.android.core.ObdDashboardRepository
import com.forddiag.android.core.ObdDashboardScope
import com.forddiag.android.core.ObdFavoritePointer
import com.forddiag.android.core.PatternCandidate
import com.forddiag.android.core.PressSequenceUi
import com.forddiag.android.core.ProfileRepository
import com.forddiag.android.core.ScannerSession
import com.forddiag.android.core.VehicleProfile
import com.forddiag.android.core.correlatePressSequence
import com.forddiag.android.core.diffDidSnapshots
import com.forddiag.android.core.isLinkLost
import com.forddiag.android.core.listBondedObdDevices
import com.forddiag.android.core.pidLabel
import com.forddiag.android.core.resolveFordExtendedPids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class AppTab { HOME, PROFILES, ECUS, DTCS, LIVE, OBD }

enum class ObdUiMode { LIST, GAUGES, CHART }

data class ObdSignalId(
    val source: LiveSource,
    val id: Int,
    val moduleId: String? = null,
)

/** Map a live sample to the catalog key (PIDs are never module-scoped). */
fun obdSampleKey(sample: LiveSample): ObdSignalId =
    ObdSignalId(
        source = sample.source,
        id = sample.id,
        moduleId = if (sample.source == LiveSource.PID) null else sample.moduleId,
    )

/**
 * LazyColumn row key for a live CAN frame.
 * [index] keeps two identical frames parsed in the same millisecond distinct.
 */
fun liveFrameRowKey(index: Int, frame: CanFrame): String =
    "$index:${frame.tsMs}:${frame.id}:${frame.hashCode()}"

data class ObdSignalRow(
    val key: ObdSignalId,
    val label: String,
    val unit: String?,
    val selected: Boolean = false,
    val lastValue: Double? = null,
    val lastText: String? = null,
    val chartable: Boolean = false,
    /** True when Mode 01 PID appeared in the reference TorqueScan export. */
    val confirmed: Boolean = false,
)

data class ObdDashboardSummary(
    val id: String,
    val name: String,
    val scope: ObdDashboardScope,
    val signalCount: Int,
    val updatedAtMs: Long,
)

fun ObdDashboard.toSummary(scope: ObdDashboardScope) = ObdDashboardSummary(
    id = id,
    name = name,
    scope = scope,
    signalCount = signals.size,
    updatedAtMs = updatedAtMs,
)

data class ObdChartPoint(val tsMs: Long, val value: Double)

data class UiState(
    val tab: AppTab = AppTab.HOME,
    val devices: List<BtDeviceInfo> = emptyList(),
    val profiles: List<VehicleProfile> = emptyList(),
    /** Profile ids that exist under filesDir/profiles (user-writable). */
    val userProfileIds: Set<String> = emptySet(),
    val selectedAddress: String? = null,
    val selectedProfileId: String? = null,
    val status: ConnectionStatus? = null,
    val modules: List<ModuleScanResult> = emptyList(),
    val selectedModuleId: String? = null,
    val moduleDtcs: List<com.forddiag.android.core.Dtc> = emptyList(),
    val asBuilt: AsBuiltDump? = null,
    val asBuiltJson: String? = null,
    val dtcGroups: List<ModuleDtcGroup> = emptyList(),
    val discovered: List<DiscoveredEcu> = emptyList(),
    val discoverProgress: EcuDiscoverProgress? = null,
    val sniffing: Boolean = false,
    /** Visible (filtered) frames for the Live list. */
    val frames: List<CanFrame> = emptyList(),
    /** Unfiltered ring-buffer size (for FRAMES filtered/total). */
    val framesTotal: Int = 0,
    /** Hex CAN ID filter text (empty = all). */
    val frameIdFilter: String = "",
    val candidates: List<PatternCandidate> = emptyList(),
    /** Non-null while a guided press-capture sequence is running. */
    val pressSequence: PressSequenceUi? = null,
    /** True after Analyze finishes with zero candidates. */
    val analyzeEmpty: Boolean = false,
    /** True after Analyze finishes with one or more candidates (results dialog). */
    val analyzeResultsOpen: Boolean = false,
    val sweptDids: List<DidValue> = emptyList(),
    val didDiffs: List<Triple<Int, String, String>> = emptyList(),
    val obdMode: ObdUiMode = ObdUiMode.LIST,
    val obdSignals: List<ObdSignalRow> = emptyList(),
    val obdRecordEnabled: Boolean = false,
    val obdPolling: Boolean = false,
    val obdChartKey: ObdSignalId? = null,
    val obdChartPoints: List<ObdChartPoint> = emptyList(),
    /** CSV content ready to share after closing a recorded gauges session. */
    val obdPendingCsv: String? = null,
    val obdCatalogLoaded: Boolean = false,
    val obdDashboardsProfile: List<ObdDashboardSummary> = emptyList(),
    val obdDashboardsGlobal: List<ObdDashboardSummary> = emptyList(),
    val obdActiveDashboardId: String? = null,
    val obdActiveDashboardScope: ObdDashboardScope? = null,
    val obdFavorite: ObdFavoritePointer? = null,
    val busy: String? = null,
    val error: String? = null,
)

class FordDiagViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: ScannerSession? = null
    private val mutex = Mutex()
    private var sniffJob: Job? = null
    private var longJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    private var pressSequenceJob: Job? = null
    private var snapshotBefore: Map<Int, String> = emptyMap()
    private var snapshotAfter: Map<Int, String> = emptyMap()
    private val pinnedDids = mutableListOf<LiveDidPin>()
    /** Equation/unit for Ford extended DIDs keyed like OBD rows. */
    private var fordDidPins = mapOf<ObdSignalId, LiveDidPin>()
    private val sniffFrames = mutableListOf<CanFrame>()
    /** Frames captured only while Analyze is running (not ring-trimmed). */
    private val analyzeCapture = mutableListOf<CanFrame>()
    private var capturingAnalyze = false
    private var obdPollJob: Job? = null
    private var obdRecording = false
    private val obdRecordHeaders = mutableListOf<String>()
    private val obdRecordKeys = mutableListOf<ObdSignalId>()
    private val obdRecordRows = mutableListOf<List<String>>()
    private val obdChartBuffer = mutableListOf<ObdChartPoint>()

    companion object {
        const val PRESS_STAGE_MS = 5_000L
        const val ADAPTER_CONNECTION_LOST = "Adapter connection lost"
        private const val PRESS_TICK_MS = 50L
        private const val OBD_POLL_DELAY_MS = 120L
        private const val OBD_CHART_MAX_POINTS = 120

        private val PRESS_STAGE_DEFS = listOf(
            0 to "No pulses. Espera…",
            1 to "Pulsa 1 vez y espera",
            2 to "Pulsa 2 veces y espera",
            3 to "Pulsa 3 veces y espera",
        )
    }

    init {
        refreshDevices()
        loadProfiles()
        reloadObdDashboards()
        DtcCatalog.ensureLoaded(getApplication())
    }

    fun selectTab(tab: AppTab) {
        val needsConn = tab != AppTab.HOME && tab != AppTab.PROFILES
        if (needsConn && _state.value.status == null) return
        _state.update { it.copy(tab = tab) }
        if (tab == AppTab.OBD && _state.value.status != null) {
            refreshObdCatalog()
        }
    }

    fun isBuiltInProfile(id: String): Boolean = ProfileRepository.isBuiltIn(id)

    fun canDeleteProfile(id: String): Boolean =
        ProfileRepository.canDelete(getApplication(), id)

    fun refreshDevices() {
        val devices = runCatching {
            listBondedObdDevices(getApplication())
        }.getOrElse { emptyList() }
        _state.update {
            it.copy(
                devices = devices,
                selectedAddress = it.selectedAddress
                    ?: devices.firstOrNull { d ->
                        d.name.contains("OBD", ignoreCase = true) ||
                            d.name.contains("MX", ignoreCase = true)
                    }?.address
                    ?: devices.firstOrNull()?.address,
            )
        }
    }

    private fun loadProfiles() {
        val app = getApplication<Application>()
        val profiles = runCatching {
            ProfileRepository.loadAll(app)
        }.getOrElse { emptyList() }
        val userIds = profiles.map { it.id }.filter { ProfileRepository.isOnDisk(app, it) }.toSet()
        val remembered = AppPreferences.getSelectedProfileId(app)
            ?.takeIf { id -> profiles.any { p -> p.id == id } }
        val current = _state.value.selectedProfileId
            ?.takeIf { id -> profiles.any { p -> p.id == id } }
        val selected = current ?: remembered ?: profiles.firstOrNull()?.id
        if (selected != null && selected != remembered) {
            AppPreferences.setSelectedProfileId(app, selected)
        }
        _state.update {
            it.copy(
                profiles = profiles,
                userProfileIds = userIds,
                selectedProfileId = selected,
            )
        }
        reloadObdDashboards()
    }

    fun newBlankProfile(): VehicleProfile = VehicleProfile(
        id = "custom_${System.currentTimeMillis()}",
        name = "New profile",
        years = "",
        notes = null,
        modules = listOf(
            ModuleDef(
                id = "PCM",
                name = "Powertrain Control Module",
                txId = 0x7E0,
                bus = "hs",
                dids = listOf(DidDef(0xF188, "Software number")),
            ),
        ),
    )

    fun saveProfile(profile: VehicleProfile) {
        runBusy("Saving profile…") {
            ProfileRepository.save(getApplication(), profile)
            loadProfiles()
            AppPreferences.setSelectedProfileId(getApplication(), profile.id)
            _state.update { it.copy(selectedProfileId = profile.id, error = null) }
        }
    }

    fun deleteProfile(id: String) {
        runBusy("Deleting profile…") {
            val ok = ProfileRepository.delete(getApplication(), id)
            if (!ok) throw IllegalStateException("Profile not on disk (built-in cannot be deleted)")
            loadProfiles()
        }
    }

    fun importAsBuilt(
        uri: Uri,
        base: VehicleProfile,
        onDone: (Result<Pair<VehicleProfile, String>>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Cannot read file")
                    val parsed = AsBuiltParser.parse(text)
                    AsBuiltParser.applyAsBuilt(base, parsed) to AsBuiltParser.summary(parsed)
                }
            }
            result.exceptionOrNull()?.let { e ->
                _state.update { it.copy(error = e.message ?: "Import failed") }
            }
            onDone(result)
        }
    }

    fun selectDevice(address: String) {
        _state.update { it.copy(selectedAddress = address) }
    }

    fun selectProfile(id: String) {
        AppPreferences.setSelectedProfileId(getApplication(), id)
        _state.update { st ->
            val clearActive = st.obdActiveDashboardScope == ObdDashboardScope.PROFILE
            st.copy(
                selectedProfileId = id,
                obdActiveDashboardId = if (clearActive) null else st.obdActiveDashboardId,
                obdActiveDashboardScope = if (clearActive) null else st.obdActiveDashboardScope,
            )
        }
        reloadObdDashboards()
    }

    fun selectModule(id: String) {
        _state.update {
            it.copy(selectedModuleId = id, moduleDtcs = emptyList(), asBuilt = null, asBuiltJson = null)
        }
    }

    fun connect() {
        val address = _state.value.selectedAddress ?: return
        val profileId = _state.value.selectedProfileId
        runBusy("Connecting…") {
            stopBackgroundLocked()
            session?.close()
            val deviceName = _state.value.devices.find { it.address == address }?.name
            val s = ScannerSession.connect(getApplication(), address, deviceName)
            if (profileId != null) {
                s.setProfile(ProfileRepository.loadById(getApplication(), profileId))
            }
            s.readVin()
            session = s
            val status = s.status()
            _state.update {
                it.copy(
                    status = status,
                    error = status.vinError?.let { err -> "VIN read failed: $err" },
                    tab = AppTab.HOME,
                    modules = emptyList(),
                    dtcGroups = emptyList(),
                    discovered = emptyList(),
                )
            }
        }
    }

    fun disconnect() {
        runBusy("Disconnecting…") {
            clearSessionLocked(error = null)
        }
    }

    /** Tear down the OBD session after a dead Bluetooth/adapter link. */
    private fun forceDisconnect() {
        clearSessionLocked(error = ADAPTER_CONNECTION_LOST)
    }

    private fun clearSessionLocked(error: String?) {
        stopBackgroundLocked()
        session?.close()
        session = null
        _state.update {
            UiState(
                devices = it.devices,
                profiles = it.profiles,
                userProfileIds = it.userProfileIds,
                selectedAddress = it.selectedAddress,
                selectedProfileId = it.selectedProfileId,
                tab = AppTab.HOME,
                error = error,
            )
        }
    }

    private fun handleSessionError(e: Exception) {
        if (isLinkLost(e)) {
            forceDisconnect()
        } else {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun scan() {
        val profileId = _state.value.selectedProfileId ?: return
        runBusy("Scanning modules…") {
            val s = session ?: throw IllegalStateException("Not connected")
            s.setProfile(ProfileRepository.loadById(getApplication(), profileId))
            val results = s.scanModules()
            val firstOnline = results.firstOrNull { it.online }?.id ?: results.firstOrNull()?.id
            _state.update {
                it.copy(
                    status = s.status(),
                    modules = results,
                    selectedModuleId = firstOnline,
                    tab = AppTab.ECUS,
                    moduleDtcs = emptyList(),
                    asBuilt = null,
                    asBuiltJson = null,
                )
            }
        }
    }

    fun readModuleDtcs(moduleId: String) {
        runBusy("Reading DTCs…") {
            val codes = session?.readModuleDtcs(moduleId).orEmpty()
            _state.update { it.copy(moduleDtcs = codes, asBuilt = null, asBuiltJson = null) }
        }
    }

    fun dumpAsBuilt(moduleId: String) {
        runBusy("Dumping As-Built…") {
            val dump = session?.dumpAsBuilt(moduleId)
                ?: throw IllegalStateException("Not connected")
            val jsonStr = buildAsBuiltJson(dump)
            _state.update { it.copy(asBuilt = dump, asBuiltJson = jsonStr, moduleDtcs = emptyList()) }
        }
    }

    fun readAllDtcs() {
        runBusy("Reading all DTCs…") {
            val groups = session?.readAllDtcs().orEmpty()
            _state.update { it.copy(dtcGroups = groups, tab = AppTab.DTCS) }
        }
    }

    fun clearDtcs(moduleId: String) {
        runBusy("Clearing DTCs…") {
            session?.clearModuleDtcs(moduleId)
            val groups = session?.readAllDtcs().orEmpty()
            _state.update { it.copy(dtcGroups = groups) }
        }
    }

    fun discoverEcus(bus: CanBus, fromId: Int, toId: Int) {
        stopLongJob()
        cancelFlag.set(false)
        longJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Discovering ECUs…", error = null, discovered = emptyList()) }
            try {
                val found = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val s = session ?: throw IllegalStateException("Not connected")
                        s.discoverEcus(bus, fromId, toId, cancelFlag) { progress ->
                            _state.update { it.copy(discoverProgress = progress) }
                        }
                    }
                }
                _state.update {
                    it.copy(discovered = found, discoverProgress = null, tab = AppTab.ECUS)
                }
            } catch (e: Exception) {
                if (isLinkLost(e)) {
                    forceDisconnect()
                } else {
                    _state.update { it.copy(error = e.message ?: e.toString(), discoverProgress = null) }
                }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun pinDid(moduleId: String, did: Int) {
        if (pinnedDids.none { it.moduleId == moduleId && it.did == did }) {
            val label = profileDidLabel(moduleId, did) ?: "DID 0x%04X".format(did)
            pinnedDids += LiveDidPin(moduleId, did, label)
            mergeObdDidSignal(moduleId, did, label)
        }
    }

    fun setObdRecordEnabled(enabled: Boolean) {
        _state.update { it.copy(obdRecordEnabled = enabled) }
    }

    fun toggleObdSignal(key: ObdSignalId) {
        _state.update { st ->
            st.copy(
                obdSignals = st.obdSignals.map { row ->
                    if (row.key == key) row.copy(selected = !row.selected) else row
                },
            )
        }
    }

    fun reloadObdDashboards() {
        val app = getApplication<Application>()
        val profileId = _state.value.selectedProfileId
        val global = ObdDashboardRepository.listGlobal(app)
            .map { it.toSummary(ObdDashboardScope.GLOBAL) }
        val profile = profileId?.let { ObdDashboardRepository.listForProfile(app, it) }
            ?.map { it.toSummary(ObdDashboardScope.PROFILE) }
            .orEmpty()
        val favorite = AppPreferences.getObdFavorite(app)
        _state.update {
            it.copy(
                obdDashboardsGlobal = global,
                obdDashboardsProfile = profile,
                obdFavorite = favorite,
            )
        }
    }

    fun applyObdDashboard(id: String, scope: ObdDashboardScope) {
        val app = getApplication<Application>()
        val profileId = _state.value.selectedProfileId
        try {
            val dash = ObdDashboardRepository.load(
                app,
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                id = id,
            )
            val keys = dash.signals.mapNotNull { it.toObdSignalId() }.toSet()
            val result = applyDashboardSelection(_state.value.obdSignals, keys)
            val err = if (result.skipped > 0) {
                "${result.skipped} signals not in catalog"
            } else {
                null
            }
            _state.update {
                it.copy(
                    obdSignals = result.rows,
                    obdActiveDashboardId = dash.id,
                    obdActiveDashboardScope = scope,
                    error = err,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun saveObdDashboard(name: String, scope: ObdDashboardScope) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Name required") }
            return
        }
        val selected = _state.value.obdSignals.filter { it.selected }
        if (selected.isEmpty()) {
            _state.update { it.copy(error = "Select at least one signal") }
            return
        }
        val profileId = _state.value.selectedProfileId
        if (scope == ObdDashboardScope.PROFILE && profileId.isNullOrBlank()) {
            _state.update { it.copy(error = "No profile selected") }
            return
        }
        try {
            val dash = ObdDashboard(
                id = ObdDashboardIds.newId(trimmed),
                name = trimmed,
                signals = selected.map { it.key.toDashboardKey() },
                updatedAtMs = System.currentTimeMillis(),
            )
            ObdDashboardRepository.save(
                getApplication(),
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                dashboard = dash,
            )
            reloadObdDashboards()
            _state.update {
                it.copy(
                    obdActiveDashboardId = dash.id,
                    obdActiveDashboardScope = scope,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun updateActiveObdDashboard() {
        val id = _state.value.obdActiveDashboardId
        val scope = _state.value.obdActiveDashboardScope
        if (id == null || scope == null) {
            _state.update { it.copy(error = "No active dashboard") }
            return
        }
        val selected = _state.value.obdSignals.filter { it.selected }
        if (selected.isEmpty()) {
            _state.update { it.copy(error = "Select at least one signal") }
            return
        }
        val profileId = _state.value.selectedProfileId
        try {
            val existing = ObdDashboardRepository.load(
                getApplication(),
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                id = id,
            )
            val updated = existing.copy(
                signals = selected.map { it.key.toDashboardKey() },
                updatedAtMs = System.currentTimeMillis(),
            )
            ObdDashboardRepository.save(
                getApplication(),
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                dashboard = updated,
            )
            reloadObdDashboards()
            _state.update { it.copy(error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun renameActiveObdDashboard(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Name required") }
            return
        }
        val id = _state.value.obdActiveDashboardId
        val scope = _state.value.obdActiveDashboardScope
        if (id == null || scope == null) {
            _state.update { it.copy(error = "No active dashboard") }
            return
        }
        val profileId = _state.value.selectedProfileId
        try {
            val existing = ObdDashboardRepository.load(
                getApplication(),
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                id = id,
            )
            val renamed = existing.copy(name = trimmed, updatedAtMs = System.currentTimeMillis())
            ObdDashboardRepository.save(
                getApplication(),
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                dashboard = renamed,
            )
            reloadObdDashboards()
            _state.update { it.copy(error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun deleteActiveObdDashboard() {
        val id = _state.value.obdActiveDashboardId
        val scope = _state.value.obdActiveDashboardScope
        if (id == null || scope == null) {
            _state.update { it.copy(error = "No active dashboard") }
            return
        }
        val app = getApplication<Application>()
        val profileId = _state.value.selectedProfileId
        try {
            ObdDashboardRepository.delete(
                app,
                scope,
                profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
                id = id,
            )
            val fav = AppPreferences.getObdFavorite(app)
            if (fav != null && fav.dashboardId == id && fav.scope == scope) {
                AppPreferences.clearObdFavorite(app)
            }
            reloadObdDashboards()
            _state.update {
                it.copy(
                    obdActiveDashboardId = null,
                    obdActiveDashboardScope = null,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun copyActiveGlobalDashboardToProfile() {
        val id = _state.value.obdActiveDashboardId
        val scope = _state.value.obdActiveDashboardScope
        val profileId = _state.value.selectedProfileId
        if (id == null || scope != ObdDashboardScope.GLOBAL) {
            _state.update { it.copy(error = "Active dashboard must be global") }
            return
        }
        if (profileId.isNullOrBlank()) {
            _state.update { it.copy(error = "No profile selected") }
            return
        }
        try {
            val copy = ObdDashboardRepository.copyGlobalToProfile(getApplication(), id, profileId)
            reloadObdDashboards()
            _state.update {
                it.copy(
                    obdActiveDashboardId = copy.id,
                    obdActiveDashboardScope = ObdDashboardScope.PROFILE,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        }
    }

    fun setObdFavoriteDashboard(id: String, scope: ObdDashboardScope) {
        val profileId = _state.value.selectedProfileId
        if (scope == ObdDashboardScope.PROFILE && profileId.isNullOrBlank()) {
            _state.update { it.copy(error = "No profile selected") }
            return
        }
        val favorite = ObdFavoritePointer(
            scope = scope,
            dashboardId = id,
            profileId = if (scope == ObdDashboardScope.PROFILE) profileId else null,
        )
        AppPreferences.setObdFavorite(getApplication(), favorite)
        _state.update { it.copy(obdFavorite = favorite, error = null) }
    }

    fun clearObdFavoriteDashboard() {
        AppPreferences.clearObdFavorite(getApplication())
        _state.update { it.copy(obdFavorite = null, error = null) }
    }

    fun refreshObdCatalog() {
        if (_state.value.status == null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "Listing OBD signals…", error = null) }
            try {
                val supportedPids = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        session?.listSupportedObdPids().orEmpty()
                    }
                }
                val selected = _state.value.obdSignals.filter { it.selected }.map { it.key }.toSet()
                var rows = buildObdCatalogRows(supportedPids, selected)
                val app = getApplication<Application>()
                val favorite = AppPreferences.getObdFavorite(app)
                val profileId = _state.value.selectedProfileId
                var activeId: String? = null
                var activeScope: ObdDashboardScope? = null
                var applyError: String? = null
                if (favorite != null && favorite.isApplicable(profileId)) {
                    val dash = runCatching {
                        ObdDashboardRepository.load(
                            app,
                            favorite.scope,
                            if (favorite.scope == ObdDashboardScope.PROFILE) favorite.profileId else null,
                            favorite.dashboardId,
                        )
                    }.getOrNull()
                    if (dash != null) {
                        val keys = dash.signals.mapNotNull { it.toObdSignalId() }.toSet()
                        val applied = applyDashboardSelection(rows, keys)
                        rows = applied.rows
                        activeId = dash.id
                        activeScope = favorite.scope
                        if (applied.skipped > 0) {
                            applyError = "${applied.skipped} signals not in catalog"
                        }
                    }
                }
                _state.update {
                    it.copy(
                        obdSignals = rows,
                        obdCatalogLoaded = true,
                        obdActiveDashboardId = activeId ?: it.obdActiveDashboardId,
                        obdActiveDashboardScope = activeScope ?: it.obdActiveDashboardScope,
                        obdFavorite = favorite,
                        busy = null,
                        error = applyError,
                    )
                }
            } catch (e: Exception) {
                if (isLinkLost(e)) {
                    forceDisconnect()
                } else {
                    _state.update {
                        it.copy(
                            error = e.message ?: e.toString(),
                            busy = null,
                            obdCatalogLoaded = true,
                        )
                    }
                }
            }
        }
    }

    fun startObdGauges() {
        val selected = _state.value.obdSignals.filter { it.selected }
        if (selected.isEmpty()) {
            _state.update { it.copy(error = "Select at least one signal") }
            return
        }
        val record = _state.value.obdRecordEnabled
        stopBackgroundJobs()
        obdRecording = record
        obdRecordHeaders.clear()
        obdRecordKeys.clear()
        obdRecordRows.clear()
        if (record) {
            obdRecordHeaders += "timestamp"
            selected.forEach { row ->
                obdRecordKeys += row.key
                val col = signalColumnName(row)
                obdRecordHeaders += col
            }
        }
        _state.update {
            it.copy(
                obdMode = ObdUiMode.GAUGES,
                obdChartKey = null,
                obdChartPoints = emptyList(),
                obdPendingCsv = null,
                error = null,
            )
        }
        startObdPoll(selected.map { it.key }, chartOnly = false)
    }

    fun startObdChart(key: ObdSignalId) {
        val row = _state.value.obdSignals.find { it.key == key } ?: return
        if (!row.chartable && row.lastValue == null) {
            // Allow start; poll may still produce numeric values for short DIDs.
        }
        stopBackgroundJobs()
        obdRecording = false
        obdChartBuffer.clear()
        _state.update {
            it.copy(
                obdMode = ObdUiMode.CHART,
                obdChartKey = key,
                obdChartPoints = emptyList(),
                obdPendingCsv = null,
                error = null,
            )
        }
        startObdPoll(listOf(key), chartOnly = true)
    }

    /**
     * Exit gauges/chart fullscreen. If gauges were recording, builds CSV into [UiState.obdPendingCsv].
     */
    fun stopObdFullscreen() {
        val wasRecording = obdRecording && obdRecordRows.isNotEmpty()
        val csv = if (wasRecording) {
            buildObdRecordCsv(obdRecordHeaders.toList(), obdRecordRows.toList())
        } else {
            null
        }
        stopObdPoll()
        obdRecording = false
        obdRecordHeaders.clear()
        obdRecordKeys.clear()
        obdRecordRows.clear()
        obdChartBuffer.clear()
        _state.update {
            it.copy(
                obdMode = ObdUiMode.LIST,
                obdPolling = false,
                obdChartKey = null,
                obdChartPoints = emptyList(),
                obdPendingCsv = csv,
            )
        }
    }

    fun consumeObdPendingCsv() {
        _state.update { it.copy(obdPendingCsv = null) }
    }

    fun setFrameIdFilter(text: String) {
        _state.update { it.copy(frameIdFilter = text) }
        publishFrames()
    }

    fun dismissAnalyzeEmpty() {
        _state.update { it.copy(analyzeEmpty = false) }
    }

    fun dismissAnalyzeResults() {
        _state.update { it.copy(analyzeResultsOpen = false) }
    }

    fun startSniff(bus: CanBus) {
        cancelPressSequence()
        stopBackgroundJobs()
        sniffJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = "Starting live…",
                    error = null,
                    frames = emptyList(),
                    framesTotal = 0,
                    candidates = emptyList(),
                    pressSequence = null,
                    analyzeEmpty = false,
                    analyzeResultsOpen = false,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        session?.startCanSniff(bus)
                            ?: throw IllegalStateException("Not connected")
                    }
                }
                sniffFrames.clear()
                analyzeCapture.clear()
                capturingAnalyze = false
                _state.update { it.copy(busy = null, sniffing = true, tab = AppTab.LIVE) }
                while (isActive) {
                    val chunk = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            session?.readSniffChunk(200).orEmpty()
                        }
                    }
                    if (chunk.isNotEmpty()) {
                        sniffFrames += chunk
                        if (sniffFrames.size > 2000) {
                            sniffFrames.subList(0, sniffFrames.size - 2000).clear()
                        }
                        if (capturingAnalyze) {
                            analyzeCapture += chunk
                        }
                        publishFrames()
                    }
                    delay(50)
                }
            } catch (e: Exception) {
                if (isLinkLost(e)) {
                    forceDisconnect()
                } else {
                    _state.update { it.copy(error = e.message ?: e.toString(), sniffing = false) }
                }
            } finally {
                withContext(Dispatchers.IO) {
                    mutex.withLock { runCatching { session?.stopCanSniff() } }
                }
                capturingAnalyze = false
                _state.update { it.copy(sniffing = false, busy = null) }
            }
        }
    }

    fun stopSniff() {
        cancelPressSequence()
        sniffJob?.cancel()
        sniffJob = null
    }

    /** Start the guided 4-stage press capture (baseline + 1/2/3 presses). */
    fun startPressSequence() {
        if (!_state.value.sniffing) return
        if (pressSequenceJob?.isActive == true) return
        pressSequenceJob = viewModelScope.launch {
            val stages = mutableListOf<CaptureStage>()
            val total = PRESS_STAGE_DEFS.size
            analyzeCapture.clear()
            capturingAnalyze = true
            try {
                for ((index, def) in PRESS_STAGE_DEFS.withIndex()) {
                    val (expectedPresses, label) = def
                    val startMs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            pressSequence = PressSequenceUi(
                                stageIndex = index,
                                stageLabel = label,
                                progress = 0f,
                                totalStages = total,
                            ),
                            candidates = if (index == 0) emptyList() else it.candidates,
                            analyzeEmpty = false,
                            analyzeResultsOpen = false,
                        )
                    }
                    var elapsed = 0L
                    while (elapsed < PRESS_STAGE_MS && isActive) {
                        delay(PRESS_TICK_MS)
                        elapsed = System.currentTimeMillis() - startMs
                        val progress = (elapsed.toFloat() / PRESS_STAGE_MS).coerceIn(0f, 1f)
                        _state.update {
                            it.copy(
                                pressSequence = it.pressSequence?.copy(progress = progress),
                            )
                        }
                    }
                    if (!isActive) return@launch
                    val endMs = System.currentTimeMillis()
                    stages += CaptureStage(expectedPresses, startMs, endMs)
                }
                capturingAnalyze = false
                val candidates = correlatePressSequence(analyzeCapture.toList(), stages)
                _state.update {
                    it.copy(
                        candidates = candidates,
                        pressSequence = null,
                        analyzeEmpty = candidates.isEmpty(),
                        analyzeResultsOpen = candidates.isNotEmpty(),
                    )
                }
            } finally {
                capturingAnalyze = false
                if (_state.value.pressSequence != null) {
                    _state.update { it.copy(pressSequence = null) }
                }
            }
        }
    }

    fun cancelPressSequence() {
        pressSequenceJob?.cancel()
        pressSequenceJob = null
        capturingAnalyze = false
        _state.update { it.copy(pressSequence = null) }
    }

    private fun parseFrameIdFilter(text: String): Int? {
        val t = text.trim().removePrefix("0x").removePrefix("0X")
        if (t.isEmpty()) return null
        return t.toIntOrNull(16)
    }

    private fun publishFrames() {
        val filter = parseFrameIdFilter(_state.value.frameIdFilter)
        val filtered = if (filter == null) sniffFrames else sniffFrames.filter { it.id == filter }
        _state.update {
            it.copy(
                frames = filtered.takeLast(80),
                framesTotal = sniffFrames.size,
            )
        }
    }

    fun didSweep(moduleId: String, fromDid: Int, toDid: Int) {
        stopLongJob()
        cancelFlag.set(false)
        longJob = viewModelScope.launch {
            _state.update { it.copy(busy = "DID sweep…", error = null, sweptDids = emptyList()) }
            try {
                val found = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val s = session ?: throw IllegalStateException("Not connected")
                        val acc = mutableListOf<DidValue>()
                        s.sweepDids(moduleId, fromDid, toDid, cancelFlag) { v ->
                            acc += v
                            _state.update { it.copy(sweptDids = acc.toList()) }
                        }
                    }
                }
                _state.update { it.copy(sweptDids = found) }
            } catch (e: Exception) {
                handleSessionError(e)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun didSnapshot(moduleId: String, fromDid: Int, toDid: Int, which: String) {
        stopLongJob()
        cancelFlag.set(false)
        longJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Snapshot $which…", error = null) }
            try {
                val snap = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        session?.snapshotDidRange(moduleId, fromDid, toDid, cancelFlag)
                            ?: throw IllegalStateException("Not connected")
                    }
                }
                if (which == "before") snapshotBefore = snap else snapshotAfter = snap
            } catch (e: Exception) {
                handleSessionError(e)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun diffSnapshots() {
        val diffs = diffDidSnapshots(snapshotBefore, snapshotAfter)
        _state.update { it.copy(didDiffs = diffs) }
    }

    fun stopActivity() {
        cancelFlag.set(true)
        stopBackgroundJobs()
        longJob?.cancel()
        longJob = null
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock { runCatching { session?.stopCanSniff() } }
        }
        _state.update {
            it.copy(
                sniffing = false,
                obdPolling = false,
                obdMode = ObdUiMode.LIST,
                obdChartKey = null,
                obdChartPoints = emptyList(),
                discoverProgress = null,
                busy = null,
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun startObdPoll(keys: List<ObdSignalId>, chartOnly: Boolean) {
        stopObdPoll()
        val pids = keys.filter { it.source == LiveSource.PID }.map { it.id }
        val didPins = keys.filter { it.source == LiveSource.DID }.mapNotNull { key ->
            val moduleId = key.moduleId ?: return@mapNotNull null
            val ford = fordDidPins[key]
            val pinned = pinnedDids.find { it.moduleId == moduleId && it.did == key.id }
            val label = _state.value.obdSignals.find { it.key == key }?.label
                ?: ford?.label
                ?: pinned?.label
            LiveDidPin(
                moduleId = moduleId,
                did = key.id,
                label = label,
                equation = ford?.equation ?: pinned?.equation,
                unit = ford?.unit ?: pinned?.unit
                    ?: _state.value.obdSignals.find { it.key == key }?.unit,
            )
        }
        obdPollJob = viewModelScope.launch {
            _state.update { it.copy(obdPolling = true, busy = null) }
            try {
                while (isActive) {
                    val samples = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            session?.pollLiveOnce(pids, didPins).orEmpty()
                        }
                    }
                    applyObdSamples(samples, keys, chartOnly)
                    delay(OBD_POLL_DELAY_MS)
                }
            } catch (e: Exception) {
                if (isLinkLost(e)) {
                    forceDisconnect()
                } else {
                    _state.update {
                        it.copy(
                            error = e.message ?: e.toString(),
                            obdPolling = false,
                        )
                    }
                }
            } finally {
                _state.update { it.copy(obdPolling = false) }
            }
        }
    }

    private fun applyObdSamples(
        samples: List<com.forddiag.android.core.LiveSample>,
        keys: List<ObdSignalId>,
        chartOnly: Boolean,
    ) {
        val byKey = samples.associateBy(::obdSampleKey)
        val ts = samples.firstOrNull()?.tsMs ?: System.currentTimeMillis()

        if (obdRecording && !chartOnly) {
            val row = mutableListOf(ts.toString())
            for (key in obdRecordKeys) {
                val s = byKey[key]
                val cell = s?.value?.let { formatObdNumber(it) }
                    ?: s?.decoded
                    ?: ""
                row += cell
            }
            obdRecordRows += row
        }

        if (chartOnly) {
            val key = keys.firstOrNull()
            val sample = key?.let { byKey[it] }
            val value = sample?.value
            if (value != null) {
                obdChartBuffer += ObdChartPoint(ts, value)
                if (obdChartBuffer.size > OBD_CHART_MAX_POINTS) {
                    obdChartBuffer.subList(0, obdChartBuffer.size - OBD_CHART_MAX_POINTS).clear()
                }
            }
        }

        _state.update { st ->
            val updated = st.obdSignals.map { row ->
                val s = byKey[row.key] ?: return@map row
                row.copy(
                    lastValue = s.value ?: row.lastValue,
                    lastText = s.decoded ?: s.rawHex,
                    unit = s.unit ?: row.unit,
                    label = s.label ?: row.label,
                    chartable = (s.value != null) || row.chartable,
                )
            }
            st.copy(
                obdSignals = updated,
                obdChartPoints = if (chartOnly) obdChartBuffer.toList() else st.obdChartPoints,
            )
        }
    }

    private fun stopObdPoll() {
        obdPollJob?.cancel()
        obdPollJob = null
    }

    private fun buildObdCatalogRows(
        supportedPids: List<Int>,
        selected: Set<ObdSignalId>,
    ): List<ObdSignalRow> {
        val existing = _state.value.obdSignals.associateBy { it.key }
        val rows = linkedMapOf<ObdSignalId, ObdSignalRow>()
        val scanSupported = runCatching {
            FordExtendedPidCatalog.loadMode01ScanSupported(getApplication())
        }.getOrDefault(emptySet())

        // Confirmed (scan) Mode 01 first, then remaining by PID id.
        val orderedPids = supportedPids.sortedWith(
            compareByDescending<Int> { it in scanSupported }.thenBy { it },
        )
        for (pid in orderedPids) {
            val key = ObdSignalId(LiveSource.PID, pid, null)
            val prev = existing[key]
            rows[key] = ObdSignalRow(
                key = key,
                label = pidLabel(pid) ?: prev?.label ?: "PID %02X".format(pid),
                unit = prev?.unit,
                selected = key in selected || prev?.selected == true,
                lastValue = prev?.lastValue,
                lastText = prev?.lastText,
                chartable = prev?.chartable == true || pidLabel(pid) != null,
                confirmed = pid in scanSupported,
            )
        }

        val profile = _state.value.selectedProfileId?.let { id ->
            _state.value.profiles.find { it.id == id }
        }
        profile?.modules?.forEach { mod ->
            mod.dids.filter { !it.asBuilt }.forEach { def ->
                addDidRow(rows, existing, selected, mod.id, def.did, def.label)
            }
            // Also include as-built DIDs with labels so "todo lo que se pueda" is covered,
            // but after live DIDs so they don't dominate the list order.
            mod.dids.filter { it.asBuilt }.forEach { def ->
                addDidRow(rows, existing, selected, mod.id, def.did, def.label)
            }
        }

        pinnedDids.forEach { pin ->
            addDidRow(
                rows,
                existing,
                selected,
                pin.moduleId,
                pin.did,
                pin.label ?: "DID 0x%04X".format(pin.did),
                unit = pin.unit,
                chartable = !pin.equation.isNullOrBlank(),
            )
        }

        val fordMeta = linkedMapOf<ObdSignalId, LiveDidPin>()
        if (profile != null) {
            val catalog = runCatching {
                FordExtendedPidCatalog.load(getApplication())
            }.getOrDefault(emptyList())
            for (resolved in resolveFordExtendedPids(catalog, profile)) {
                if (!resolved.pid.isMode22) continue
                val did = resolved.pid.pidOrDid
                val key = ObdSignalId(LiveSource.DID, did, resolved.moduleId)
                val pin = LiveDidPin(
                    moduleId = resolved.moduleId,
                    did = did,
                    label = resolved.pid.name,
                    equation = resolved.pid.equation,
                    unit = resolved.pid.unit.ifBlank { null },
                )
                // First equation wins for a given module+DID
                if (!fordMeta.containsKey(key)) {
                    fordMeta[key] = pin
                }
                if (rows.containsKey(key)) {
                    // Enrich existing row with unit/chartable when missing
                    val prev = rows[key]!!
                    rows[key] = prev.copy(
                        unit = prev.unit ?: pin.unit,
                        chartable = prev.chartable || pin.equation != null,
                    )
                    continue
                }
                addDidRow(
                    rows,
                    existing,
                    selected,
                    resolved.moduleId,
                    did,
                    resolved.pid.name,
                    unit = pin.unit,
                    chartable = true,
                )
            }
        }
        fordDidPins = fordMeta

        val sweepModule = _state.value.selectedModuleId
        if (sweepModule != null) {
            _state.value.sweptDids.forEach { did ->
                val label = profileDidLabel(sweepModule, did.did)
                    ?: "DID 0x%04X".format(did.did)
                addDidRow(rows, existing, selected, sweepModule, did.did, label)
            }
        }

        return rows.values.toList()
    }

    private fun addDidRow(
        rows: MutableMap<ObdSignalId, ObdSignalRow>,
        existing: Map<ObdSignalId, ObdSignalRow>,
        selected: Set<ObdSignalId>,
        moduleId: String,
        did: Int,
        label: String,
        unit: String? = null,
        chartable: Boolean = false,
    ) {
        val key = ObdSignalId(LiveSource.DID, did, moduleId)
        if (rows.containsKey(key)) return
        val prev = existing[key]
        rows[key] = ObdSignalRow(
            key = key,
            label = "$label ($moduleId)",
            unit = prev?.unit ?: unit,
            selected = key in selected || prev?.selected == true,
            lastValue = prev?.lastValue,
            lastText = prev?.lastText,
            chartable = prev?.chartable == true || chartable,
        )
    }

    private fun mergeObdDidSignal(moduleId: String, did: Int, label: String) {
        val key = ObdSignalId(LiveSource.DID, did, moduleId)
        _state.update { st ->
            if (st.obdSignals.any { it.key == key }) return@update st
            st.copy(
                obdSignals = st.obdSignals + ObdSignalRow(
                    key = key,
                    label = "$label ($moduleId)",
                    unit = null,
                    selected = false,
                    chartable = false,
                ),
            )
        }
    }

    private fun profileDidLabel(moduleId: String, did: Int): String? {
        val profile = _state.value.selectedProfileId?.let { id ->
            _state.value.profiles.find { it.id == id }
        } ?: return null
        return profile.module(moduleId)?.dids?.find { it.did == did }?.label
    }

    private fun signalColumnName(row: ObdSignalRow): String {
        val base = row.label.replace(';', ',').replace('\n', ' ')
        return if (row.unit.isNullOrBlank()) base else "$base (${row.unit})"
    }

    private fun formatObdNumber(v: Double): String =
        if (v == v.toLong().toDouble()) "%.0f".format(v) else "%.2f".format(v)

    private fun stopBackgroundJobs() {
        cancelPressSequence()
        sniffJob?.cancel()
        sniffJob = null
        stopObdPoll()
        obdRecording = false
    }

    private fun stopLongJob() {
        cancelFlag.set(true)
        longJob?.cancel()
        longJob = null
    }

    private fun stopBackgroundLocked() {
        cancelFlag.set(true)
        cancelPressSequence()
        sniffJob?.cancel()
        longJob?.cancel()
        stopObdPoll()
        sniffJob = null
        longJob = null
        obdRecording = false
        runCatching { session?.stopCanSniff() }
    }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = label, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock { block() }
                }
            } catch (e: Exception) {
                handleSessionError(e)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun buildAsBuiltJson(dump: AsBuiltDump): String = buildString {
        appendLine("{")
        appendLine("  \"module_id\": \"${dump.moduleId}\",")
        appendLine("  \"module_name\": \"${dump.moduleName}\",")
        appendLine("  \"profile_id\": \"${dump.profileId}\",")
        appendLine("  \"exported_at_unix_ms\": ${dump.exportedAtUnixMs},")
        appendLine("  \"blocks\": [")
        dump.blocks.forEachIndexed { i, b ->
            append("    {\"did\": ${b.did}, \"hex\": \"${b.hex}\"")
            b.ascii?.let { append(", \"ascii\": \"$it\"") }
            append("}")
            if (i < dump.blocks.lastIndex) append(",")
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    override fun onCleared() {
        stopBackgroundLocked()
        session?.close()
        super.onCleared()
    }
}
