package com.forddiag.android.core

import java.util.concurrent.atomic.AtomicBoolean

data class ModuleScanResult(
    val id: String,
    val name: String,
    val txId: Int,
    val bus: CanBus,
    val online: Boolean,
    val error: String?,
    val identity: List<DidValue>,
)

data class AsBuiltDump(
    val moduleId: String,
    val moduleName: String,
    val profileId: String,
    val blocks: List<DidValue>,
    val exportedAtUnixMs: Long,
)

data class ConnectionStatus(
    val address: String,
    val adapter: AdapterInfo,
    val profileId: String?,
    val vin: String?,
    val msCanCapable: Boolean,
    val vinError: String? = null,
)

class ScannerSession(
    private val address: String,
    private val transport: Transport,
    private val stn: StnSession,
) {
    private var profile: VehicleProfile? = null
    var vin: String? = null
        private set
    var vinError: String? = null
        private set
    var lastLiveError: String? = null
        private set

    fun status(): ConnectionStatus {
        val adapter = stn.info ?: throw FordException.NotConnected()
        return ConnectionStatus(
            address = address,
            adapter = adapter,
            profileId = profile?.id,
            vin = vin,
            msCanCapable = adapter.isStn,
            vinError = vinError,
        )
    }

    fun setProfile(profile: VehicleProfile) {
        this.profile = profile
    }

    fun profile(): VehicleProfile? = profile

    fun address(): String = address

    private fun <T> withModule(module: ModuleDef, block: (Transport) -> T): T {
        stn.selectBus(module.canBus())
        stn.setHeader(module.txId)
        return block(stn.asTransport())
    }

    fun scanModules(): List<ModuleScanResult> {
        val profile = this.profile ?: throw FordException.Profile("no profile selected")
        val results = mutableListOf<ModuleScanResult>()
        for (module in profile.modules) {
            if (module.canBus() == CanBus.MS && stn.info?.isStn != true) {
                results += ModuleScanResult(
                    id = module.id,
                    name = module.name,
                    txId = module.txId,
                    bus = module.canBus(),
                    online = false,
                    error = "MS-CAN skipped: adapter is not STN/OBDLink (use OBDLink MX / MX+)",
                    identity = emptyList(),
                )
                continue
            }
            val result = try {
                withModule(module) { t ->
                    val uds = UdsClient(t)
                    runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
                    val identity = mutableListOf<DidValue>()
                    val first = module.dids.firstOrNull()
                    if (first != null) {
                        runCatching { uds.readDataByIdentifier(first.did) }
                            .onSuccess { identity += it }
                            .onFailure { uds.testerPresent() }
                    } else {
                        uds.testerPresent()
                    }
                    module.dids.drop(1).take(2).forEach { did ->
                        runCatching { uds.readDataByIdentifier(did.did) }
                            .onSuccess { identity += it }
                    }
                    if (module.id == "PCM") {
                        identity.find { it.did == 0xF190 }?.let {
                            vin = it.ascii ?: it.hex
                        }
                    }
                    ModuleScanResult(
                        id = module.id,
                        name = module.name,
                        txId = module.txId,
                        bus = module.canBus(),
                        online = true,
                        error = null,
                        identity = identity,
                    )
                }
            } catch (e: Exception) {
                ModuleScanResult(
                    id = module.id,
                    name = module.name,
                    txId = module.txId,
                    bus = module.canBus(),
                    online = false,
                    error = e.message,
                    identity = emptyList(),
                )
            }
            results += result
        }
        return results
    }

    fun readModuleDtcs(moduleId: String): List<Dtc> {
        val module = profile?.module(moduleId)
            ?: throw FordException.Profile("unknown module $moduleId")
        return withModule(module) { t ->
            val uds = UdsClient(t)
            runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
            uds.readDtcs()
        }
    }

    fun clearModuleDtcs(moduleId: String) {
        val module = profile?.module(moduleId)
            ?: throw FordException.Profile("unknown module $moduleId")
        withModule(module) { t ->
            val uds = UdsClient(t)
            runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
            uds.clearDtcs()
        }
    }

    fun readAllDtcs(): List<ModuleDtcGroup> {
        val profile = this.profile ?: throw FordException.Profile("no profile selected")
        val groups = mutableListOf<ModuleDtcGroup>()
        for (module in profile.modules) {
            var group = ModuleDtcGroup(
                moduleId = module.id,
                moduleName = module.name,
                txId = module.txId,
                bus = module.canBus(),
                online = false,
                error = null,
                dtcs = emptyList(),
            )
            if (module.canBus() == CanBus.MS && stn.info?.isStn != true) {
                groups += group.copy(error = "MS-CAN skipped (non-STN adapter)")
                continue
            }
            group = try {
                val dtcs = withModule(module) { t ->
                    val uds = UdsClient(t)
                    runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
                    uds.readDtcs()
                }
                group.copy(online = true, dtcs = dtcs)
            } catch (e: Exception) {
                group.copy(error = e.message)
            }
            groups += group
        }
        return groups
    }

    fun dumpAsBuilt(moduleId: String): AsBuiltDump {
        val profile = this.profile ?: throw FordException.Profile("no profile selected")
        val module = profile.module(moduleId)
            ?: throw FordException.Profile("unknown module $moduleId")
        val dids = module.dids.filter { it.asBuilt }
        if (dids.isEmpty()) {
            throw FordException.Profile("module $moduleId has no as_built DIDs")
        }
        val blocks = withModule(module) { t ->
            val uds = UdsClient(t)
            runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
            dids.mapNotNull { did ->
                runCatching { uds.readDataByIdentifier(did.did) }.getOrNull()
            }
        }
        return AsBuiltDump(
            moduleId = module.id,
            moduleName = module.name,
            profileId = profile.id,
            blocks = blocks,
            exportedAtUnixMs = System.currentTimeMillis(),
        )
    }

    fun readVin(): String? {
        stn.selectBus(CanBus.HS)
        stn.setHeader(0x7E0)
        val uds = UdsClient(stn.asTransport())
        runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
        return try {
            val v = uds.readDataByIdentifier(0xF190)
            val value = v.ascii ?: v.hex
            vin = value
            vinError = null
            value
        } catch (e: Exception) {
            vinError = e.message ?: e.toString()
            null
        }
    }

    /** Brute-force probe of 11-bit diagnostic IDs on one bus. */
    fun discoverEcus(
        bus: CanBus,
        fromId: Int,
        toId: Int,
        cancel: AtomicBoolean,
        onProgress: (EcuDiscoverProgress) -> Unit,
    ): List<DiscoveredEcu> {
        if (bus == CanBus.MS && stn.info?.isStn != true) {
            throw FordException.Adapter("MS-CAN discovery requires STN/OBDLink adapter")
        }

        stn.selectBus(bus)
        val profile = this.profile
        val found = mutableListOf<DiscoveredEcu>()
        val end = maxOf(toId, fromId)

        for (id in fromId..end) {
            if (cancel.get()) break
            onProgress(
                EcuDiscoverProgress(
                    bus = bus,
                    currentId = id,
                    fromId = fromId,
                    toId = end,
                    found = found.size,
                ),
            )

            stn.setHeader(id)
            transport.setTimeoutMs(400)
            val probe = runCatching {
                val uds = UdsClient(stn.asTransport())
                try {
                    uds.testerPresent()
                    null as DidValue?
                } catch (_: Exception) {
                    uds.readDataByIdentifier(0xF190)
                }
            }
            transport.setTimeoutMs(5_000)

            if (probe.isFailure) continue
            val maybeVin = probe.getOrNull()

            val identity = mutableListOf<DidValue>()
            if (maybeVin != null) {
                identity += maybeVin
            } else {
                val uds = UdsClient(stn.asTransport())
                runCatching { uds.readDataByIdentifier(0xF190) }.onSuccess { identity += it }
                runCatching { uds.readDataByIdentifier(0xF188) }.onSuccess { identity += it }
            }

            val match = profile?.modules?.find { it.txId == id && it.canBus() == bus }
            found += DiscoveredEcu(
                txId = id,
                rxId = id or 0x08,
                bus = bus,
                online = true,
                profileId = match?.id,
                name = match?.name,
                identity = identity,
                error = null,
            )
        }
        return found
    }

    /** One live poll cycle: Mode 01 PIDs + selected UDS DIDs (incl. Ford extended). */
    fun pollLiveOnce(pids: List<Int>, dids: List<LiveDidPin>): List<LiveSample> {
        val tsMs = System.currentTimeMillis()
        val samples = mutableListOf<LiveSample>()
        var lastErr: String? = null

        stn.selectBus(CanBus.HS)
        stn.setHeader(0x7E0)
        for (pid in pids) {
            runCatching { readPid(stn.asTransport(), pid) }
                .onSuccess { s ->
                    samples += LiveSample(
                        tsMs = tsMs,
                        source = LiveSource.PID,
                        id = pid,
                        rawHex = s.hex,
                        decoded = s.decoded ?: s.hex,
                        moduleId = null,
                        label = s.label ?: pidLabel(pid) ?: "PID %02X".format(pid),
                        value = s.value,
                        unit = s.unit,
                    )
                }
                .onFailure { lastErr = it.message ?: it.toString() }
        }

        val profile = this.profile
        // Prefer Ford baro DID first so BARO() equations can use it in the same cycle.
        val orderedDids = dids.sortedBy { pin ->
            when {
                pin.did == FORD_BARO_DID -> 0
                pin.equation?.uppercase()?.contains("BARO()") == true -> 2
                else -> 1
            }
        }
        var baro: Double? = null
        for (pin in orderedDids) {
            val module = resolvePollModule(profile, pin.moduleId) ?: continue
            runCatching {
                withModule(module) { t ->
                    val uds = UdsClient(t)
                    runCatching { uds.diagnosticSessionControl(UdsClient.SESSION_EXTENDED) }
                    uds.readDataByIdentifier(pin.did)
                }
            }
                .onSuccess { v ->
                    val eq = pin.equation
                    val numeric = if (!eq.isNullOrBlank()) {
                        TorqueEquation.evaluate(eq, v.data, baro)
                    } else {
                        rawNumericDid(v.data)
                    }
                    if (pin.did == FORD_BARO_DID && numeric != null) {
                        baro = numeric
                    }
                    val unit = pin.unit
                    val display = when {
                        numeric != null && !unit.isNullOrBlank() -> formatFordValue(numeric, unit)
                        numeric != null -> formatFordValue(numeric, "")
                        !v.ascii.isNullOrBlank() -> v.ascii
                        else -> v.hex
                    }
                    samples += LiveSample(
                        tsMs = tsMs,
                        source = LiveSource.DID,
                        id = pin.did,
                        rawHex = v.hex,
                        decoded = display,
                        moduleId = pin.moduleId,
                        label = pin.label ?: "DID 0x%04X".format(pin.did),
                        value = numeric,
                        unit = unit,
                    )
                }
                .onFailure { lastErr = it.message ?: it.toString() }
        }
        lastLiveError = if (samples.isEmpty()) lastErr else null
        return samples
    }

    fun listSupportedObdPids(): List<Int> {
        stn.selectBus(CanBus.HS)
        stn.setHeader(0x7E0)
        return listSupportedPids(stn.asTransport())
    }

    fun startCanSniff(bus: CanBus) {
        stn.selectBus(bus)
        stn.clearReceiveFilter()
        runCatching { transport.command("ATH1") }
        // Raw CAN frames; leave ISO-TP formatting off during ATMA.
        runCatching { transport.command("ATCAF0") }
        stn.startMonitor()
    }

    fun readSniffChunk(maxWaitMs: Long): List<CanFrame> {
        val chunk = stn.readMonitorChunk(maxWaitMs)
        val frames = parseMonitorBuffer(chunk, System.currentTimeMillis())
        // ELM ATMA stops on BUFFER FULL / STOPPED / prompt — Torque re-issues ATMA.
        if (transport.lastMonitorHitPrompt() || monitorOutputEnded(chunk)) {
            stn.startMonitor()
        }
        return frames
    }

    fun stopCanSniff() {
        stn.stopMonitor()
    }

    /** Sweep DIDs on a module; returns successful reads. */
    fun sweepDids(
        moduleId: String,
        fromDid: Int,
        toDid: Int,
        cancel: AtomicBoolean,
        onFound: (DidValue) -> Unit = {},
    ): List<DidValue> {
        val module = profile?.module(moduleId)
            ?: throw FordException.Profile("unknown module $moduleId")
        val end = maxOf(toDid, fromDid)
        val found = mutableListOf<DidValue>()
        stn.selectBus(module.canBus())
        stn.setHeader(module.txId)
        runCatching {
            UdsClient(stn.asTransport()).diagnosticSessionControl(UdsClient.SESSION_EXTENDED)
        }

        for (did in fromDid..end) {
            if (cancel.get()) break
            transport.setTimeoutMs(350)
            val result = runCatching {
                UdsClient(stn.asTransport()).readDataByIdentifier(did)
            }
            transport.setTimeoutMs(5_000)
            result.getOrNull()?.let {
                onFound(it)
                found += it
            }
        }
        return found
    }

    fun snapshotDidRange(
        moduleId: String,
        fromDid: Int,
        toDid: Int,
        cancel: AtomicBoolean,
    ): Map<Int, String> {
        return sweepDids(moduleId, fromDid, toDid, cancel).associate { it.did to it.hex }
    }

    fun close() {
        transport.close()
    }

    companion object {
        /** Ford-specific barometric pressure DID used by Torque BARO(). */
        const val FORD_BARO_DID = 0x1127

        /** Profile module or synthetic `HDR_XXX` from hex header. */
        fun resolvePollModule(profile: VehicleProfile?, moduleId: String): ModuleDef? {
            profile?.module(moduleId)?.let { return it }
            val m = Regex("""^HDR_([0-9A-Fa-f]+)$""").matchEntire(moduleId) ?: return null
            val tx = m.groupValues[1].toIntOrNull(16) ?: return null
            return ModuleDef(
                id = moduleId,
                name = "Header 0x%03X".format(tx),
                txId = tx,
                bus = "hs",
            )
        }

        fun connect(
            context: android.content.Context,
            address: String,
            deviceName: String? = null,
        ): ScannerSession {
            val transport = BluetoothTransport.connect(context, address)
            transport.setTimeoutMs(5_000)
            val stn = StnSession(transport)
            stn.initialize(deviceName)
            return ScannerSession(address, transport, stn)
        }
    }
}
