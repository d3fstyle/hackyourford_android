package com.forddiag.android.core

/** A single captured CAN frame (11-bit ID). */
data class CanFrame(
    val tsMs: Long,
    val id: Int,
    val data: ByteArray,
    val rawLine: String,
)

/** Candidate ID/byte that correlates with press windows. */
data class PatternCandidate(
    val canId: Int,
    val byteIndex: Int,
    val bitIndex: Int?,
    val changeScore: Double,
    val sampleValues: List<String>,
)

/** Parse one ELM monitor line into a frame (headers on). */
fun parseMonitorLine(line: String, tsMs: Long): CanFrame? {
    val trimmed = line.trim().trimEnd('>')
    if (trimmed.isEmpty()) return null
    val upper = trimmed.uppercase()
    if (upper.contains("SEARCHING") ||
        upper == "OK" ||
        upper == "STOPPED" ||
        upper.startsWith("AT") ||
        upper.startsWith("ST") ||
        upper.contains('?')
    ) {
        return null
    }

    val cleaned = trimmed.filter { it.isDigit() || it.isWhitespace() || it in 'A'..'F' || it in 'a'..'f' }
    val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) {
        val hexOnly = trimmed.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
        if (hexOnly.length < 5) return null
        val id = hexOnly.substring(0, 3).toIntOrNull(16) ?: return null
        val dataHex = hexOnly.substring(3)
        if (dataHex.length % 2 != 0) return null
        return CanFrame(tsMs, id, dataHex.hexToByteArray(), trimmed)
    }

    val id = parts[0].toIntOrNull(16) ?: return null
    val data = mutableListOf<Byte>()
    for (p in parts.drop(1)) {
        if (p.length == 2) {
            p.toIntOrNull(16)?.let { data += it.toByte() }
        } else {
            runCatching { data.addAll(p.hexToByteArray().toList()) }
        }
    }
    return CanFrame(tsMs, id, data.toByteArray(), trimmed)
}

/** Split a monitor buffer into frames. */
fun parseMonitorBuffer(buf: String, baseTsMs: Long): List<CanFrame> {
    val normalized = buf.replace("\r\n", "\n").replace('\r', '\n')
    val frames = mutableListOf<CanFrame>()
    var offset = 0L
    for (line in normalized.lineSequence()) {
        parseMonitorLine(line, baseTsMs + offset)?.let {
            frames += it
            offset++
        }
    }
    return frames
}

/** Score IDs/bytes whose changes cluster inside press windows more than outside. */
fun correlatePressPattern(
    frames: List<CanFrame>,
    pressWindows: List<Pair<Long, Long>>,
): List<PatternCandidate> {
    if (frames.isEmpty() || pressWindows.isEmpty()) return emptyList()

    val byId = frames.groupBy { it.id }
    val candidates = mutableListOf<PatternCandidate>()

    for ((canId, list) in byId) {
        if (list.size < 2) continue
        val maxLen = list.maxOf { it.data.size }
        for (byteI in 0 until maxLen) {
            var changesIn = 0
            var changesOut = 0
            val samples = mutableListOf<String>()
            for (w in list.windowed(2)) {
                val a = w[0].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: 0
                val b = w[1].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: 0
                if (a == b) continue
                val ts = w[1].tsMs
                val inside = pressWindows.any { (s, e) -> ts in s..e }
                if (inside) {
                    changesIn++
                    samples += "%02X->%02X @$ts".format(a, b)
                } else {
                    changesOut++
                }
            }
            if (changesIn == 0) continue
            val score = changesIn.toDouble() / (changesIn + maxOf(changesOut, 1)).toDouble()
            if (score < 0.5) continue

            var bestBit: Pair<Int, Double>? = null
            for (bit in 0 until 8) {
                var bin = 0
                var bout = 0
                val mask = 1 shl bit
                for (w in list.windowed(2)) {
                    val a = w[0].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: 0
                    val b = w[1].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: 0
                    if ((a xor b) and mask == 0) continue
                    val ts = w[1].tsMs
                    val inside = pressWindows.any { (s, e) -> ts in s..e }
                    if (inside) bin++ else bout++
                }
                if (bin == 0) continue
                val bscore = bin.toDouble() / (bin + maxOf(bout, 1)).toDouble()
                if (bestBit == null || bscore > bestBit.second) {
                    bestBit = bit to bscore
                }
            }

            candidates += PatternCandidate(
                canId = canId,
                byteIndex = byteI,
                bitIndex = bestBit?.takeIf { it.second >= 0.5 }?.first,
                changeScore = score,
                sampleValues = samples.take(8),
            )
        }
    }

    return candidates.sortedByDescending { it.changeScore }.take(40)
}

/** Diff two DID snapshots (did -> hex). */
fun diffDidSnapshots(
    before: Map<Int, String>,
    after: Map<Int, String>,
): List<Triple<Int, String, String>> {
    val out = mutableListOf<Triple<Int, String, String>>()
    for ((did, afterHex) in after) {
        val beforeHex = before[did]
        when {
            beforeHex == null -> out += Triple(did, "", afterHex)
            beforeHex != afterHex -> out += Triple(did, beforeHex, afterHex)
        }
    }
    return out.sortedBy { it.first }
}
