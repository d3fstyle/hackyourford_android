package com.forddiag.android.core

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

interface Transport {
    fun command(cmd: String): String
    fun sendHex(hexPayload: String): String = command(hexPayload)
    fun setTimeoutMs(timeoutMs: Long)
    fun close()

    /** Write bytes without waiting for `>` (used by ATMA). */
    fun writeRaw(bytes: ByteArray) {
        throw FordException.Other("writeRaw not supported on this transport")
    }

    /** Clear adapter receive address filter (`ATAR`). */
    fun clearReceiveFilter() {
        command("ATAR")
    }

    /** Start continuous CAN monitor (`ATMA`). Does not wait for `>`. */
    fun startMonitor() {
        writeRaw("ATMA\r".toByteArray(Charsets.US_ASCII))
    }

    /** Read a chunk of monitor output for up to [maxWaitMs]. */
    fun readMonitorChunk(maxWaitMs: Long): String {
        throw FordException.Other("readMonitorChunk not supported on this transport")
    }

    /** Interrupt ATMA and resync to the prompt. */
    fun stopMonitor() {
        writeRaw("\r".toByteArray(Charsets.US_ASCII))
        sleepQuiet(50)
        runCatching { command("ATI") }
    }
}

fun parseHexResponse(response: String): ByteArray {
    val normalized = response.replace("\r\n", "\n").replace('\r', '\n')
    val out = ByteArrayOutputStream()
    for (rawLine in normalized.lineSequence()) {
        val line = rawLine.trim().trimEnd('>')
        if (line.isEmpty()) continue
        val upper = line.uppercase()
        when {
            upper.contains("NO DATA") ||
                upper.contains("UNABLE TO CONNECT") ||
                upper.contains("CAN ERROR") ||
                upper.contains("BUS ERROR") ||
                upper.contains("BUFFER FULL") ->
                throw FordException.Adapter(line)

            upper.contains("SEARCHING") ||
                upper == "OK" ||
                upper == "STOPPED" ||
                upper.startsWith("AT") ||
                upper.startsWith("ST") ||
                upper.contains('?') ||
                upper == "ERROR" ||
                upper.contains("BUS INIT") -> continue
        }
        val hex = stripCanHeaderIfNeeded(
            line.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' },
        )
        if (hex.isEmpty()) continue
        if (hex.length % 2 != 0) {
            throw FordException.Unexpected("odd hex length in line: $line")
        }
        out.write(hex.hexToByteArray())
    }
    val bytes = out.toByteArray()
    if (bytes.isEmpty()) {
        throw FordException.Unexpected("no hex data in response: $response")
    }
    return bytes
}

/**
 * With ATH1 + spaces off, ELM prints an 11-bit CAN ID as 3 hex digits then the payload.
 * That makes the digit count odd (e.g. `7E803410C1AF8`). Drop the leading 3 nibbles when
 * the remainder is even-length payload.
 */
internal fun stripCanHeaderIfNeeded(cleaned: String): String {
    if (cleaned.isEmpty()) return cleaned
    if (cleaned.length % 2 == 0) return cleaned
    // 11-bit ID (3 hex) + even payload
    if (cleaned.length >= 5 && (cleaned.length - 3) % 2 == 0) {
        return cleaned.substring(3)
    }
    // 29-bit ID is 8 hex digits; odd total after that is less common — leave for error
    return cleaned
}

fun String.hexToByteArray(): ByteArray {
    val len = length / 2
    val data = ByteArray(len)
    for (i in 0 until len) {
        data[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return data
}

fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }

/** Scripted transport for unit tests. */
class MockTransport : Transport {
    private val scripts = ArrayDeque<Pair<String, String>>()
    val sent = mutableListOf<String>()
    private var timeoutMs = 1_000L
    private val monitorChunks = ArrayDeque<String>()
    var monitoring = false
        private set

    fun expect(prefix: String, response: String) {
        scripts.addLast(prefix to response)
    }

    fun enqueueMonitorChunk(chunk: String) {
        monitorChunks.addLast(chunk)
    }

    override fun command(cmd: String): String {
        sent += cmd
        val upper = cmd.uppercase()
        val idx = scripts.indexOfFirst { (p, _) ->
            upper.startsWith(p.uppercase()) || upper == p.uppercase()
        }
        if (idx >= 0) {
            return scripts.removeAt(idx).second
        }
        if (upper == "ATZ" || upper == "ATI") return "ELM327 v1.5"
        if (upper.startsWith("AT") || upper.startsWith("ST")) return "OK"
        throw FordException.Unexpected("mock has no script for command: $cmd")
    }

    override fun setTimeoutMs(timeoutMs: Long) {
        this.timeoutMs = timeoutMs
    }

    override fun writeRaw(bytes: ByteArray) {
        sent += String(bytes, Charsets.US_ASCII).trimEnd('\r')
        if (String(bytes, Charsets.US_ASCII).uppercase().startsWith("ATMA")) {
            monitoring = true
        }
        if (bytes.contentEquals(byteArrayOf('\r'.code.toByte()))) {
            monitoring = false
        }
    }

    override fun readMonitorChunk(maxWaitMs: Long): String {
        if (monitorChunks.isNotEmpty()) return monitorChunks.removeFirst()
        sleepQuiet(minOf(maxWaitMs, 5))
        return ""
    }

    override fun stopMonitor() {
        monitoring = false
        sent += "STOP_MONITOR"
    }

    override fun close() = Unit
}

inline fun <T> withTimeoutHint(@Suppress("UNUSED_PARAMETER") ms: Long, block: () -> T): T = block()

fun sleepQuiet(ms: Long) {
    try {
        TimeUnit.MILLISECONDS.sleep(ms)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
