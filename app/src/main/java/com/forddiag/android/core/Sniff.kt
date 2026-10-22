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

/**
 * One timed stage of a guided press-capture sequence.
 * [expectedPresses] is 0 for the idle baseline stage.
 */
data class CaptureStage(
    val expectedPresses: Int,
    val startMs: Long,
    val endMs: Long,
)

/** UI snapshot of an in-progress guided press sequence. */
data class PressSequenceUi(
    val stageIndex: Int,
    val stageLabel: String,
    val progress: Float,
    val totalStages: Int = 4,
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

/**
 * Split a monitor buffer into frames.
 * Each line is stamped with the real wall-clock time at parse (arrival),
 * not a synthetic baseTs+index offset that drifts past stage windows.
 */
fun parseMonitorBuffer(buf: String, @Suppress("UNUSED_PARAMETER") baseTsMs: Long): List<CanFrame> {
    val normalized = buf.replace("\r\n", "\n").replace('\r', '\n')
    val frames = mutableListOf<CanFrame>()
    for (line in normalized.lineSequence()) {
        val ts = System.currentTimeMillis()
        parseMonitorLine(line, ts)?.let { frames += it }
    }
    return frames
}

private const val BASELINE_NOISE_CHANGES = 3
private const val SEQUENCE_SCORE_THRESHOLD = 0.5

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

/**
 * Score IDs/bytes whose change counts across guided stages match expected press counts.
 * Stage 0 (expectedPresses == 0) is the idle baseline used to reject periodic noise.
 */
fun correlatePressSequence(
    frames: List<CanFrame>,
    stages: List<CaptureStage>,
): List<PatternCandidate> {
    if (frames.isEmpty() || stages.size < 2) return emptyList()

    val baseline = stages.firstOrNull { it.expectedPresses == 0 } ?: return emptyList()
    val pressStages = stages.filter { it.expectedPresses > 0 }.sortedBy { it.expectedPresses }
    if (pressStages.isEmpty()) return emptyList()

    val byId = frames.groupBy { it.id }
    val candidates = mutableListOf<PatternCandidate>()

    for ((canId, list) in byId) {
        if (list.size < 2) continue
        val maxLen = list.maxOf { it.data.size }
        for (byteI in 0 until maxLen) {
            val stageCounts = IntArray(stages.size)
            val stageSamples = List(stages.size) { mutableListOf<String>() }
            for (w in list.windowed(2)) {
                val a = w[0].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: continue
                val b = w[1].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: continue
                if (a == b) continue
                val ts = w[1].tsMs
                val stageIdx = stages.indexOfFirst { ts in it.startMs..it.endMs }
                if (stageIdx < 0) continue
                stageCounts[stageIdx]++
                if (stageSamples[stageIdx].size < 4) {
                    stageSamples[stageIdx] += "s${stageIdx}:%02X->%02X".format(a, b)
                }
            }

            val baselineIdx = stages.indexOf(baseline)
            val c0 = stageCounts.getOrElse(baselineIdx) { 0 }
            if (c0 >= BASELINE_NOISE_CHANGES) continue

            val observed = pressStages.map { stage ->
                val idx = stages.indexOf(stage)
                stageCounts.getOrElse(idx) { 0 }.toDouble()
            }
            if (observed.all { it == 0.0 }) continue

            var bestScore = 0.0
            for (k in 1..2) {
                val expected = pressStages.map { (it.expectedPresses * k).toDouble() }
                val sim = sequenceSimilarity(observed, expected)
                val penalty = 1.0 - (c0.toDouble() / BASELINE_NOISE_CHANGES)
                val score = sim * penalty.coerceAtLeast(0.0)
                if (score > bestScore) bestScore = score
            }
            if (bestScore < SEQUENCE_SCORE_THRESHOLD) continue

            var bestBit: Pair<Int, Double>? = null
            for (bit in 0 until 8) {
                val mask = 1 shl bit
                val bitCounts = IntArray(stages.size)
                for (w in list.windowed(2)) {
                    val a = w[0].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: continue
                    val b = w[1].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: continue
                    if ((a xor b) and mask == 0) continue
                    val ts = w[1].tsMs
                    val stageIdx = stages.indexOfFirst { ts in it.startMs..it.endMs }
                    if (stageIdx >= 0) bitCounts[stageIdx]++
                }
                val bitC0 = bitCounts.getOrElse(baselineIdx) { 0 }
                if (bitC0 >= BASELINE_NOISE_CHANGES) continue
                val bitObs = pressStages.map { stage ->
                    bitCounts.getOrElse(stages.indexOf(stage)) { 0 }.toDouble()
                }
                if (bitObs.all { it == 0.0 }) continue
                var bitBest = 0.0
                for (k in 1..2) {
                    val expected = pressStages.map { (it.expectedPresses * k).toDouble() }
                    val sim = sequenceSimilarity(bitObs, expected)
                    val penalty = 1.0 - (bitC0.toDouble() / BASELINE_NOISE_CHANGES)
                    bitBest = maxOf(bitBest, sim * penalty.coerceAtLeast(0.0))
                }
                if (bestBit == null || bitBest > bestBit.second) {
                    bestBit = bit to bitBest
                }
            }

            candidates += PatternCandidate(
                canId = canId,
                byteIndex = byteI,
                bitIndex = bestBit?.takeIf { it.second >= SEQUENCE_SCORE_THRESHOLD }?.first,
                changeScore = bestScore,
                sampleValues = stageSamples.flatten().take(8),
            )
        }
    }

    return candidates.sortedByDescending { it.changeScore }.take(40)
}

/** Mean relative similarity of observed change counts to an expected sequence. */
internal fun sequenceSimilarity(observed: List<Double>, expected: List<Double>): Double {
    if (observed.size != expected.size || observed.isEmpty()) return 0.0
    var sum = 0.0
    for (i in observed.indices) {
        val exp = expected[i]
        val obs = observed[i]
        if (exp <= 0.0) {
            sum += if (obs == 0.0) 1.0 else 0.0
            continue
        }
        val err = kotlin.math.abs(obs - exp) / exp
        sum += (1.0 - err).coerceIn(0.0, 1.0)
    }
    return sum / observed.size
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
