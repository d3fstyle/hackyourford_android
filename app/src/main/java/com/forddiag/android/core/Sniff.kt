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

/**
 * True when ELM/STN ended ATMA (buffer full or stopped).
 * Torque re-issues ATMA when it sees these; silence follows if we do not.
 */
fun monitorOutputEnded(text: String): Boolean {
    if (text.isEmpty()) return false
    val up = text.uppercase()
    return up.contains("BUFFER FULL") ||
        up.contains("STOPPED") ||
        // ELM also prints bare "FULL" on some firmwares
        Regex("""(^|[^A-Z])FULL([^A-Z]|$)""").containsMatchIn(up)
}

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

private const val SEQUENCE_SCORE_THRESHOLD = 0.4
private const val BASELINE_FRAME_NOISE_RATIO = 0.15
private val EXPECTED_PRESS_RANKS = listOf(1.0, 2.0, 3.0)

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
 * Score IDs/bytes/bits whose change counts across guided stages rise with press counts.
 * Stage 0 (expectedPresses == 0) is the idle baseline used to reject periodic noise.
 * Bits are scored independently so a quiet button bit survives inside a noisy byte.
 */
fun correlatePressSequence(
    frames: List<CanFrame>,
    stages: List<CaptureStage>,
): List<PatternCandidate> {
    if (frames.isEmpty() || stages.size < 2) return emptyList()

    val baseline = stages.firstOrNull { it.expectedPresses == 0 } ?: return emptyList()
    val pressStages = stages.filter { it.expectedPresses > 0 }.sortedBy { it.expectedPresses }
    if (pressStages.isEmpty()) return emptyList()

    val baselineIdx = stages.indexOf(baseline)
    val pressIdx = pressStages.map { stages.indexOf(it) }
    val expectedRanks = pressStages.map { it.expectedPresses.toDouble() }.ifEmpty { EXPECTED_PRESS_RANKS }

    val byId = frames.groupBy { it.id }
    val candidates = mutableListOf<PatternCandidate>()

    for ((canId, list) in byId) {
        if (list.size < 2) continue
        val baselineFrames = list.count { it.tsMs in baseline.startMs..baseline.endMs }
        val maxLen = list.maxOf { it.data.size }

        for (byteI in 0 until maxLen) {
            val byteCounts = IntArray(stages.size)
            val bitCounts = Array(8) { IntArray(stages.size) }
            val stageSamples = List(stages.size) { mutableListOf<String>() }

            for (w in list.windowed(2)) {
                val a = w[0].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: continue
                val b = w[1].data.getOrNull(byteI)?.toInt()?.and(0xFF) ?: continue
                if (a == b) continue
                val ts = w[1].tsMs
                val stageIdx = stages.indexOfFirst { ts in it.startMs..it.endMs }
                if (stageIdx < 0) continue
                byteCounts[stageIdx]++
                if (stageSamples[stageIdx].size < 4) {
                    stageSamples[stageIdx] += "s${stageIdx}:%02X->%02X".format(a, b)
                }
                val flipped = a xor b
                for (bit in 0 until 8) {
                    if ((flipped shr bit) and 1 != 0) bitCounts[bit][stageIdx]++
                }
            }

            var bestBit: Pair<Int, Double>? = null
            for (bit in 0 until 8) {
                val counts = bitCounts[bit]
                val c0 = counts.getOrElse(baselineIdx) { 0 }
                val observed = pressIdx.map { counts.getOrElse(it) { 0 }.toDouble() }
                if (!passesBaseline(c0, observed, baselineFrames)) continue
                val score = scoreSequenceChanges(observed, expectedRanks, c0, baselineFrames)
                if (score < SEQUENCE_SCORE_THRESHOLD) continue
                if (bestBit == null || score > bestBit.second) {
                    bestBit = bit to score
                }
            }

            val byteC0 = byteCounts.getOrElse(baselineIdx) { 0 }
            val byteObs = pressIdx.map { byteCounts.getOrElse(it) { 0 }.toDouble() }
            val byteScore = if (passesBaseline(byteC0, byteObs, baselineFrames)) {
                scoreSequenceChanges(byteObs, expectedRanks, byteC0, baselineFrames)
            } else {
                0.0
            }

            val bitScore = bestBit?.second ?: 0.0
            val bestScore = maxOf(byteScore, bitScore)
            if (bestScore < SEQUENCE_SCORE_THRESHOLD) continue

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

/**
 * Baseline is too noisy when idle changes rival press activity or exceed a share of
 * baseline frames (covers periodic counters without killing quiet button bits).
 */
internal fun passesBaseline(
    baselineChanges: Int,
    pressObserved: List<Double>,
    baselineFrameCount: Int,
): Boolean {
    if (pressObserved.isEmpty() || pressObserved.all { it == 0.0 }) return false
    if (pressObserved.first() <= 0.0) return false
    val noiseCeiling = maxOf(3, (BASELINE_FRAME_NOISE_RATIO * baselineFrameCount).toInt())
    if (baselineChanges >= noiseCeiling) return false
    val minPress = pressObserved.minOrNull() ?: return false
    if (minPress > 0.0 && baselineChanges >= minPress) return false
    return true
}

/**
 * Primary score: Pearson correlation of observed press-stage counts vs expected ranks
 * (monotonicity). Exact-count similarity with k=1..6 is a small tie-break bonus.
 */
internal fun scoreSequenceChanges(
    observed: List<Double>,
    expectedRanks: List<Double>,
    baselineChanges: Int,
    baselineFrameCount: Int,
): Double {
    if (observed.size != expectedRanks.size || observed.isEmpty()) return 0.0
    if (observed.first() <= 0.0) return 0.0

    val mono = pearsonCorrelation(observed, expectedRanks).coerceIn(0.0, 1.0)
    if (mono < SEQUENCE_SCORE_THRESHOLD) return mono

    var exactBest = 0.0
    for (k in 1..6) {
        val expected = expectedRanks.map { it * k }
        exactBest = maxOf(exactBest, sequenceSimilarity(observed, expected))
    }
    val noiseCeiling = maxOf(3, (BASELINE_FRAME_NOISE_RATIO * baselineFrameCount).toInt()).toDouble()
    val penalty = (1.0 - baselineChanges.toDouble() / noiseCeiling).coerceIn(0.0, 1.0)
    // Weight monotonic gate heavily; exact match only breaks ties.
    return (0.85 * mono + 0.15 * exactBest) * penalty
}

/** Pearson correlation clamped to [0, 1] (negative correlations → 0). */
internal fun pearsonCorrelation(xs: List<Double>, ys: List<Double>): Double {
    if (xs.size != ys.size || xs.size < 2) return 0.0
    val n = xs.size.toDouble()
    val meanX = xs.sum() / n
    val meanY = ys.sum() / n
    var num = 0.0
    var denX = 0.0
    var denY = 0.0
    for (i in xs.indices) {
        val dx = xs[i] - meanX
        val dy = ys[i] - meanY
        num += dx * dy
        denX += dx * dx
        denY += dy * dy
    }
    if (denX <= 0.0 || denY <= 0.0) {
        // Constant series: treat perfect proportional match as 1 when all positive & ordered.
        return if (xs == ys) 1.0 else 0.0
    }
    return (num / kotlin.math.sqrt(denX * denY)).coerceIn(0.0, 1.0)
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
