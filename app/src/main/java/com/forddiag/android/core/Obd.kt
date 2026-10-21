package com.forddiag.android.core

/** A Mode 01 PID sample. */
data class ObdPidSample(
    val pid: Int,
    val raw: ByteArray,
    val hex: String,
    val decoded: String?,
)

/** Decode common Mode 01 PIDs into a short human string. */
fun decodePid(pid: Int, data: ByteArray): String? {
    return when {
        pid == 0x0C && data.size >= 2 -> {
            val rpm = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) / 4.0f
            "%.0f rpm".format(rpm)
        }
        pid == 0x0D && data.isNotEmpty() -> "${data[0].toInt() and 0xFF} km/h"
        pid == 0x05 && data.isNotEmpty() -> "${(data[0].toInt() and 0xFF) - 40} °C"
        pid == 0x0F && data.isNotEmpty() -> "${(data[0].toInt() and 0xFF) - 40} °C"
        pid == 0x11 && data.isNotEmpty() ->
            "%.0f%%".format((data[0].toInt() and 0xFF) * 100.0f / 255.0f)
        pid == 0x04 && data.isNotEmpty() ->
            "%.0f%%".format((data[0].toInt() and 0xFF) * 100.0f / 255.0f)
        pid == 0x42 && data.size >= 2 -> {
            val mv = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) / 1000.0f
            "%.2f V".format(mv)
        }
        else -> null
    }
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

    var i = 0
    while (i + 1 < normalized.size) {
        if ((normalized[i].toInt() and 0xFF) == 0x41 &&
            (normalized[i + 1].toInt() and 0xFF) == pid
        ) {
            val data = normalized.copyOfRange(i + 2, normalized.size)
            return ObdPidSample(
                pid = pid,
                raw = data,
                hex = data.toHexUpper(),
                decoded = decodePid(pid, data),
            )
        }
        i++
    }

    if (normalized.isNotEmpty() && (normalized[0].toInt() and 0xFF) != 0x41) {
        return ObdPidSample(
            pid = pid,
            raw = normalized,
            hex = normalized.toHexUpper(),
            decoded = decodePid(pid, normalized),
        )
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
