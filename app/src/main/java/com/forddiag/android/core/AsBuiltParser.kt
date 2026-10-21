package com.forddiag.android.core

/**
 * Ford dealer / PTS As-Built XML (`AS_BUILT_DATA`).
 * Labels like `726-01-01` map to CAN addr 0x726, block 01 → DID 0xDE00.
 */
data class ParsedAsBuilt(
    val vin: String?,
    val nodeIds: Set<Int>,
    /** 1-based block numbers per CAN address. */
    val blocksByAddr: Map<Int, Set<Int>>,
) {
    fun moduleCount(): Int = (nodeIds + blocksByAddr.keys).size

    fun asBuiltDidCount(): Int = blocksByAddr.values.sumOf { it.size }
}

data class KnownModule(
    val id: String,
    val name: String,
    val bus: String,
)

/** Well-known Ford 11-bit diagnostic request IDs for Focus / Tourneo era. */
val KNOWN_MODULES: Map<Int, KnownModule> = mapOf(
    0x7E0 to KnownModule("PCM", "Powertrain Control Module", "hs"),
    0x760 to KnownModule("ABS", "Anti-lock Braking System", "hs"),
    0x726 to KnownModule("BCM", "Body Control Module", "ms"),
    0x720 to KnownModule("IPC", "Instrument Panel Cluster", "ms"),
    0x730 to KnownModule("EPS", "Electronic Power Steering", "ms"),
    0x737 to KnownModule("RCM", "Restraints Control Module", "ms"),
    0x736 to KnownModule("PAM", "Parking Aid Module", "ms"),
    0x727 to KnownModule("ACM", "Audio Control Module", "ms"),
    0x7D0 to KnownModule("APIM", "SYNC (APIM)", "ms"),
    0x7E5 to KnownModule("AHCM", "Auxiliary Heater Control Module", "ms"),
    0x733 to KnownModule("SODL", "Side Obstacle Detection Left", "ms"),
    0x734 to KnownModule("SODR", "Side Obstacle Detection Right", "ms"),
    0x740 to KnownModule("DDM", "Driver Door Module", "ms"),
    0x741 to KnownModule("PDM", "Passenger Door Module", "ms"),
    0x7A7 to KnownModule("GWM", "Gateway Module", "ms"),
    0x793 to KnownModule("HVAC", "Climate Control Module", "ms"),
    0x797 to KnownModule("PSCM", "Parking Steer Assist", "ms"),
)

object AsBuiltParser {
    private val vinRe = Regex("""<VIN>([^<]+)</VIN>""", RegexOption.IGNORE_CASE)
    private val nodeIdRe = Regex("""<NODEID>([0-9A-Fa-f]{3})""", RegexOption.IGNORE_CASE)
    private val bceLabelRe = Regex(
        """LABEL\s*=\s*"([0-9A-Fa-f]{3})-(\d{2})-\d{2}"""",
        RegexOption.IGNORE_CASE,
    )
    private val pcmLabelRe = Regex(
        """LABEL\s*=\s*"PCM\s+(\d+)"""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(xml: String): ParsedAsBuilt {
        if (!xml.contains("AS_BUILT_DATA", ignoreCase = true)) {
            throw FordException.Profile("not a Ford AS_BUILT_DATA file")
        }
        val vin = vinRe.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val nodeIds = nodeIdRe.findAll(xml)
            .map { it.groupValues[1].toInt(16) }
            .toSet()

        val blocks = mutableMapOf<Int, MutableSet<Int>>()
        bceLabelRe.findAll(xml).forEach { m ->
            val addr = m.groupValues[1].toInt(16)
            val block = m.groupValues[2].toInt()
            if (block in 1..255) {
                blocks.getOrPut(addr) { mutableSetOf() }.add(block)
            }
        }
        pcmLabelRe.findAll(xml).forEach { m ->
            val block = m.groupValues[1].toInt()
            if (block in 1..255) {
                blocks.getOrPut(0x7E0) { mutableSetOf() }.add(block)
            }
        }

        if (nodeIds.isEmpty() && blocks.isEmpty()) {
            throw FordException.Profile("no NODEID or as-built blocks found in .ab file")
        }
        return ParsedAsBuilt(vin = vin, nodeIds = nodeIds, blocksByAddr = blocks)
    }

    fun applyAsBuilt(base: VehicleProfile, parsed: ParsedAsBuilt): VehicleProfile {
        val byTx = base.modules.associateBy { it.txId }.toMutableMap()
        val order = base.modules.map { it.txId }.toMutableList()
        val allAddrs = (parsed.nodeIds + parsed.blocksByAddr.keys).sorted()

        for (addr in allAddrs) {
            val known = KNOWN_MODULES[addr]
            val blocks = parsed.blocksByAddr[addr].orEmpty()
            val newAbDids = blocks.sorted().map { block ->
                val offset = block - 1
                DidDef(
                    did = 0xDE00 + offset,
                    label = "As-Built DE%02X".format(offset),
                    asBuilt = true,
                )
            }
            val existing = byTx[addr]
            if (existing != null) {
                val nonAb = existing.dids.filter { !it.asBuilt }
                val abByDid = (existing.dids.filter { it.asBuilt } + newAbDids)
                    .associateBy { it.did }
                    .values
                    .sortedBy { it.did }
                byTx[addr] = existing.copy(dids = nonAb + abByDid)
            } else {
                val meta = known ?: KnownModule(
                    id = "0x%03X".format(addr),
                    name = "Module 0x%03X".format(addr),
                    bus = "ms",
                )
                val identity = listOf(DidDef(0xF188, "Software number"))
                byTx[addr] = ModuleDef(
                    id = meta.id,
                    name = meta.name,
                    txId = addr,
                    rxId = null,
                    bus = meta.bus,
                    dids = identity + newAbDids,
                )
                order.add(addr)
            }
        }

        val modules = order.distinct().mapNotNull { byTx[it] } +
            byTx.keys.filter { it !in order }.sorted().mapNotNull { byTx[it] }

        val notes = mergeVinNote(base.notes, parsed.vin)
        return base.copy(notes = notes, modules = modules)
    }

    fun summary(parsed: ParsedAsBuilt): String {
        val vin = parsed.vin ?: "—"
        return "VIN $vin · ${parsed.moduleCount()} modules · ${parsed.asBuiltDidCount()} as-built DIDs"
    }

    private fun mergeVinNote(existing: String?, vin: String?): String? {
        if (vin.isNullOrBlank()) return existing
        val tag = "VIN $vin"
        if (existing.isNullOrBlank()) return tag
        if (existing.contains(vin)) return existing
        return "$existing · $tag"
    }
}
