package com.forddiag.android.core

/** A Mode 01 PID sample. */
data class ObdPidSample(
    val pid: Int,
    val raw: ByteArray,
    val hex: String,
    val decoded: String?,
    val value: Double? = null,
    val unit: String? = null,
    val label: String? = null,
)

/** Decoded Mode 01 PID reading with numeric value for charts/CSV. */
data class ObdPidDecoded(
    val value: Double?,
    val unit: String?,
    val label: String,
    val text: String?,
)

private data class ObdPidDef(
    val label: String,
    val unit: String,
    val decode: (ByteArray) -> Double?,
    val format: (Double) -> String = { v ->
        if (v == v.toLong().toDouble()) "%.0f".format(v) else "%.2f".format(v)
    },
)

private fun u8(data: ByteArray, i: Int = 0): Int = data[i].toInt() and 0xFF

private fun u16(data: ByteArray, i: Int = 0): Int =
    ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)

private fun pid(
    label: String,
    unit: String,
    format: (Double) -> String = { v ->
        if (v == v.toLong().toDouble()) "%.0f".format(v) else "%.2f".format(v)
    },
    decode: (ByteArray) -> Double?,
) = ObdPidDef(label, unit, decode, format)

private val PID_CATALOG: Map<Int, ObdPidDef> = mapOf(
    0x04 to pid("Engine load", "%") { d ->
        if (d.isEmpty()) null else u8(d) * 100.0 / 255.0
    },
    0x05 to pid("Coolant temp", "°C") { d ->
        if (d.isEmpty()) null else (u8(d) - 40).toDouble()
    },
    0x06 to pid("Short term fuel trim B1", "%") { d ->
        if (d.isEmpty()) null else (u8(d) - 128) * 100.0 / 128.0
    },
    0x07 to pid("Long term fuel trim B1", "%") { d ->
        if (d.isEmpty()) null else (u8(d) - 128) * 100.0 / 128.0
    },
    0x0A to pid("Fuel pressure", "kPa") { d ->
        if (d.isEmpty()) null else u8(d) * 3.0
    },
    0x0B to pid("MAP", "kPa") { d ->
        if (d.isEmpty()) null else u8(d).toDouble()
    },
    0x0C to pid("RPM", "rpm", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d) / 4.0
    },
    0x0D to pid("Speed", "km/h", format = { "%.0f".format(it) }) { d ->
        if (d.isEmpty()) null else u8(d).toDouble()
    },
    0x0E to pid("Timing advance", "°") { d ->
        if (d.isEmpty()) null else u8(d) / 2.0 - 64.0
    },
    0x0F to pid("Intake air temp", "°C") { d ->
        if (d.isEmpty()) null else (u8(d) - 40).toDouble()
    },
    0x10 to pid("MAF", "g/s") { d ->
        if (d.size < 2) null else u16(d) / 100.0
    },
    0x11 to pid("Throttle", "%") { d ->
        if (d.isEmpty()) null else u8(d) * 100.0 / 255.0
    },
    0x1F to pid("Run time", "s", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x21 to pid("Distance with MIL", "km", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x2F to pid("Fuel level", "%") { d ->
        if (d.isEmpty()) null else u8(d) * 100.0 / 255.0
    },
    0x33 to pid("Barometric pressure", "kPa") { d ->
        if (d.isEmpty()) null else u8(d).toDouble()
    },
    0x42 to pid("Control module voltage", "V") { d ->
        if (d.size < 2) null else u16(d) / 1000.0
    },
    0x43 to pid("Absolute load", "%") { d ->
        if (d.size < 2) null else u16(d) * 100.0 / 255.0
    },
    0x45 to pid("Relative throttle", "%") { d ->
        if (d.isEmpty()) null else u8(d) * 100.0 / 255.0
    },
    0x46 to pid("Ambient air temp", "°C") { d ->
        if (d.isEmpty()) null else (u8(d) - 40).toDouble()
    },
    0x5C to pid("Oil temp", "°C") { d ->
        if (d.isEmpty()) null else (u8(d) - 40).toDouble()
    },
    0x5E to pid("Fuel rate", "L/h") { d ->
        if (d.size < 2) null else u16(d) / 20.0
    },
)

/** Friendly label for a Mode 01 PID, or null if unknown. */
fun pidLabel(pid: Int): String? = PID_CATALOG[pid]?.label

/** Decode Mode 01 PID into numeric value + display text. */
fun decodePidFull(pid: Int, data: ByteArray): ObdPidDecoded {
    val def = PID_CATALOG[pid]
    if (def == null) {
        return ObdPidDecoded(
            value = rawNumericDid(data),
            unit = null,
            label = "PID %02X".format(pid),
            text = null,
        )
    }
    val value = def.decode(data)
    val text = value?.let { "${def.format(it)} ${def.unit}" }
    return ObdPidDecoded(value = value, unit = def.unit, label = def.label, text = text)
}

/** Decode common Mode 01 PIDs into a short human string. */
fun decodePid(pid: Int, data: ByteArray): String? = decodePidFull(pid, data).text

/**
 * Best-effort numeric from short DID payloads (1–2 bytes as unsigned).
 * Longer payloads return null (show hex/ascii in UI instead).
 */
fun rawNumericDid(data: ByteArray): Double? = when (data.size) {
    1 -> u8(data).toDouble()
    2 -> u16(data).toDouble()
    else -> null
}

/** Parse supported-PID bitmask response (`41 XX` + 4 bytes). */
fun parseSupportedBitmask(basePid: Int, payload: ByteArray): List<Int> {
    if (payload.size < 4) return emptyList()
    val out = mutableListOf<Int>()
    for (byteI in 0 until 4) {
        val byte = payload[byteI].toInt() and 0xFF
        for (bit in 0 until 8) {
            if (byte and (0x80 shr bit) != 0) {
                out += basePid + 1 + (byteI * 8 + bit)
            }
        }
    }
    return out
}

/** Request Mode 01 PID and return data bytes (after `41 PID`). */
fun readPid(transport: Transport, pid: Int): ObdPidSample {
    val cmd = "01%02X".format(pid)
    val resp = transport.sendHex(cmd)
    val bytes = parseHexResponse(resp)
    val normalized = normalizeElmPayload(bytes)

    fun sample(data: ByteArray): ObdPidSample {
        val decoded = decodePidFull(pid, data)
        return ObdPidSample(
            pid = pid,
            raw = data,
            hex = data.toHexUpper(),
            decoded = decoded.text,
            value = decoded.value,
            unit = decoded.unit,
            label = decoded.label,
        )
    }

    var i = 0
    while (i + 1 < normalized.size) {
        if ((normalized[i].toInt() and 0xFF) == 0x41 &&
            (normalized[i + 1].toInt() and 0xFF) == pid
        ) {
            return sample(normalized.copyOfRange(i + 2, normalized.size))
        }
        i++
    }

    if (normalized.isNotEmpty() && (normalized[0].toInt() and 0xFF) != 0x41) {
        return sample(normalized)
    }

    throw FordException.Unexpected("no Mode 01 response for PID %02X: $resp".format(pid))
}

/** Query supported PIDs via 01 00 / 01 20 / 01 40 / 01 60. */
fun listSupportedPids(transport: Transport): List<Int> {
    val supported = mutableListOf<Int>()
    for (base in listOf(0x00, 0x20, 0x40, 0x60)) {
        val sample = runCatching { readPid(transport, base) }.getOrNull() ?: break
        if (sample.raw.size < 4) break
        val nextSupported = (sample.raw[3].toInt() and 0x01) != 0
        supported += parseSupportedBitmask(base, sample.raw.copyOfRange(0, 4))
        if (!nextSupported) break
    }
    return supported.filter { it !in listOf(0x00, 0x20, 0x40, 0x60) }
}

/** Drop leading ISO-TP PCI / echo bytes when present in ELM hex dumps. */
internal fun normalizeElmPayload(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty()) return bytes
    // Single-frame PCI: high nibble 0, low nibble = length
    val first = bytes[0].toInt() and 0xFF
    if (first ushr 4 == 0 && (first and 0x0F) in 1..7 && bytes.size > 1) {
        return bytes.copyOfRange(1, bytes.size)
    }
    return bytes
}
