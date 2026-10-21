package com.forddiag.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forddiag.android.core.AsBuiltDump
import com.forddiag.android.core.AsBuiltParser
import com.forddiag.android.core.BtDeviceInfo
import com.forddiag.android.core.CanBus
import com.forddiag.android.core.CanFrame
import com.forddiag.android.core.ConnectionStatus
import com.forddiag.android.core.DidDef
import com.forddiag.android.core.DiscoveredEcu
import com.forddiag.android.core.DidValue
import com.forddiag.android.core.DtcCatalog
import com.forddiag.android.core.EcuDiscoverProgress
import com.forddiag.android.core.LiveDidPin
import com.forddiag.android.core.LiveSample
import com.forddiag.android.core.LiveSessionFile
import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ModuleDef
import com.forddiag.android.core.ModuleDtcGroup
import com.forddiag.android.core.ModuleScanResult
import com.forddiag.android.core.CaptureStage
import com.forddiag.android.core.PatternCandidate
import com.forddiag.android.core.PressSequenceUi
import com.forddiag.android.core.ProfileRepository
import com.forddiag.android.core.ScannerSession
import com.forddiag.android.core.VehicleProfile
import com.forddiag.android.core.correlatePressSequence
import com.forddiag.android.core.diffDidSnapshots
import com.forddiag.android.core.listBondedObdDevices
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

enum class AppTab { HOME, PROFILES, ECUS, DTCS, LIVE, DISCOVER }

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
    val liveRunning: Boolean = false,
    val liveStream: List<LiveSample> = emptyList(),
    val liveJson: String? = null,
    val sniffing: Boolean = false,
    val frames: List<CanFrame> = emptyList(),
    val candidates: List<PatternCandidate> = emptyList(),
    /** Non-null while a guided press-capture sequence is running. */
    val pressSequence: PressSequenceUi? = null,
    val sweptDids: List<DidValue> = emptyList(),
    val didDiffs: List<Triple<Int, String, String>> = emptyList(),
    val busy: String? = null,
    val error: String? = null,
)

class FordDiagViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: ScannerSession? = null
    private val mutex = Mutex()
    private var liveJob: Job? = null
    private var sniffJob: Job? = null
    private var longJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    private val liveSamples = mutableListOf<LiveSample>()
    private var liveStartedAt = 0L
    private var pressSequenceJob: Job? = null
    private var snapshotBefore: Map<Int, String> = emptyMap()
    private var snapshotAfter: Map<Int, String> = emptyMap()
    private val pinnedDids = mutableListOf<LiveDidPin>()
    private val sniffFrames = mutableListOf<CanFrame>()

    companion object {
        const val PRESS_STAGE_MS = 5_000L
        private const val PRESS_TICK_MS = 50L

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
        DtcCatalog.ensureLoaded(getApplication())
    }

    fun selectTab(tab: AppTab) {
        val needsConn = tab != AppTab.HOME && tab != AppTab.PROFILES
        if (needsConn && _state.value.status == null) return
        _state.update { it.copy(tab = tab) }
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
        _state.update {
            val selected = it.selectedProfileId
                ?.takeIf { id -> profiles.any { p -> p.id == id } }
                ?: profiles.firstOrNull()?.id
            it.copy(
                profiles = profiles,
                userProfileIds = userIds,
                selectedProfileId = selected,
            )
        }
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
        _state.update { it.copy(selectedProfileId = id) }
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
            stopBackgroundLocked()
            session?.close()
            session = null
            _state.update {
                UiState(
                    devices = it.devices,
                    profiles = it.profiles,
                    selectedAddress = it.selectedAddress,
                    selectedProfileId = it.selectedProfileId,
                )
            }
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
                _state.update { it.copy(error = e.message ?: e.toString(), discoverProgress = null) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun startLive() {
        stopBackgroundJobs()
        val profile = session?.profile()
        var pids = listOf(0x0C, 0x0D, 0x05, 0x11)
        val defaultDids = profile?.modules?.flatMap { m ->
            m.dids.filter { !it.asBuilt }.take(2).map { LiveDidPin(m.id, it.did, it.label) }
        }.orEmpty()
        val dids = defaultDids + pinnedDids

        liveJob = viewModelScope.launch {
            _state.update {
                it.copy(busy = "Starting live…", error = null, liveStream = emptyList(), liveJson = null)
            }
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val s = session ?: throw IllegalStateException("Not connected")
                        val supported = runCatching { s.listSupportedObdPids() }.getOrDefault(emptyList())
                        if (supported.isNotEmpty()) {
                            val preferred = listOf(0x0C, 0x0D, 0x05, 0x0F, 0x11, 0x04, 0x42)
                            val filtered = supported.filter { it in preferred }
                            pids = filtered.ifEmpty { supported.take(6) }
                        }
                    }
                }
                liveSamples.clear()
                liveStartedAt = System.currentTimeMillis()
                _state.update {
                    it.copy(busy = null, liveRunning = true, tab = AppTab.LIVE, error = null)
                }
                var emptyCycles = 0
                while (isActive) {
                    val samples = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            session?.pollLiveOnce(pids, dids).orEmpty()
                        }
                    }
                    if (samples.isNotEmpty()) {
                        emptyCycles = 0
                        liveSamples += samples
                        val stream = liveSamples.takeLast(200)
                        _state.update { it.copy(liveStream = stream, error = null) }
                    } else {
                        emptyCycles++
                        if (emptyCycles >= 3) {
                            val why = session?.lastLiveError ?: "no PID/DID responses"
                            _state.update {
                                it.copy(error = "Live: $why")
                            }
                        }
                    }
                    delay(300)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString(), liveRunning = false) }
            } finally {
                _state.update { it.copy(liveRunning = false, busy = null) }
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        _state.update { it.copy(liveRunning = false) }
    }

    fun saveLiveSession(): String {
        val s = session ?: throw IllegalStateException("Not connected")
        val file = LiveSessionFile(
            startedAtUnixMs = liveStartedAt,
            profileId = s.profile()?.id,
            address = s.address(),
            samples = liveSamples.toList(),
        )
        // Manual JSON to avoid needing @Serializable on all nested types in this pass
        val jsonStr = buildString {
            appendLine("{")
            appendLine("  \"started_at_unix_ms\": ${file.startedAtUnixMs},")
            appendLine("  \"profile_id\": ${file.profileId?.let { "\"$it\"" } ?: "null"},")
            appendLine("  \"address\": \"${file.address}\",")
            appendLine("  \"samples\": [")
            file.samples.forEachIndexed { i, sample ->
                append("    {")
                append("\"ts_ms\": ${sample.tsMs}, ")
                append("\"source\": \"${if (sample.source == LiveSource.PID) "pid" else "did"}\", ")
                append("\"id\": ${sample.id}, ")
                append("\"raw_hex\": \"${sample.rawHex}\", ")
                append("\"decoded\": ${sample.decoded?.let { "\"$it\"" } ?: "null"}, ")
                append("\"module_id\": ${sample.moduleId?.let { "\"$it\"" } ?: "null"}, ")
                append("\"label\": ${sample.label?.let { "\"$it\"" } ?: "null"}")
                append("}")
                if (i < file.samples.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
        _state.update { it.copy(liveJson = jsonStr) }
        return jsonStr
    }

    fun pinDid(moduleId: String, did: Int) {
        if (pinnedDids.none { it.moduleId == moduleId && it.did == did }) {
            pinnedDids += LiveDidPin(moduleId, did, "0x%04X".format(did))
        }
    }

    fun startSniff(bus: CanBus) {
        cancelPressSequence()
        stopBackgroundJobs()
        sniffJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = "Starting sniff…",
                    error = null,
                    frames = emptyList(),
                    candidates = emptyList(),
                    pressSequence = null,
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
                _state.update { it.copy(busy = null, sniffing = true, tab = AppTab.DISCOVER) }
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
                        _state.update { it.copy(frames = sniffFrames.takeLast(80)) }
                    }
                    delay(50)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString(), sniffing = false) }
            } finally {
                withContext(Dispatchers.IO) {
                    mutex.withLock { runCatching { session?.stopCanSniff() } }
                }
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
                val candidates = correlatePressSequence(sniffFrames.toList(), stages)
                _state.update { it.copy(candidates = candidates, pressSequence = null) }
            } finally {
                if (_state.value.pressSequence != null) {
                    _state.update { it.copy(pressSequence = null) }
                }
            }
        }
    }

    fun cancelPressSequence() {
        pressSequenceJob?.cancel()
        pressSequenceJob = null
        _state.update { it.copy(pressSequence = null) }
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
                _state.update { it.copy(error = e.message ?: e.toString()) }
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
                _state.update { it.copy(error = e.message ?: e.toString()) }
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
                liveRunning = false,
                sniffing = false,
                discoverProgress = null,
                busy = null,
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun stopBackgroundJobs() {
        cancelPressSequence()
        liveJob?.cancel()
        liveJob = null
        sniffJob?.cancel()
        sniffJob = null
    }

    private fun stopLongJob() {
        cancelFlag.set(true)
        longJob?.cancel()
        longJob = null
    }

    private fun stopBackgroundLocked() {
        cancelFlag.set(true)
        cancelPressSequence()
        liveJob?.cancel()
        sniffJob?.cancel()
        longJob?.cancel()
        liveJob = null
        sniffJob = null
        longJob = null
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
                _state.update { it.copy(error = e.message ?: e.toString()) }
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
