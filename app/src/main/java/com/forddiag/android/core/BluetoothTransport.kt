package com.forddiag.android.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Standard Serial Port Profile UUID used by OBDLink MX+ / ELM adapters. */
val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class BtDeviceInfo(
    val name: String,
    val address: String,
)

@SuppressLint("MissingPermission")
fun listBondedObdDevices(context: Context): List<BtDeviceInfo> {
    val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    val adapter = manager.adapter ?: return emptyList()
    return adapter.bondedDevices
        .orEmpty()
        .map { BtDeviceInfo(it.name ?: "Unknown", it.address) }
        .sortedBy { it.name.lowercase() }
}

/**
 * Line-oriented Bluetooth Classic (RFCOMM/SPP) transport for ELM327 / STN adapters.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport private constructor(
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : Transport {
    private var timeoutMs = 5_000L

    override fun setTimeoutMs(timeoutMs: Long) {
        this.timeoutMs = timeoutMs
    }

    override fun command(cmd: String): String {
        drain()
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        return readUntilPrompt()
    }

    override fun writeRaw(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun startMonitor() {
        drain()
        // ATMA does not return until stopped — do not wait for prompt.
        writeRaw("ATMA\r".toByteArray(Charsets.US_ASCII))
    }

    override fun readMonitorChunk(maxWaitMs: Long): String {
        val deadline = System.currentTimeMillis() + maxWaitMs
        val buf = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') break
                buf.append(c)
                if ((c == '\n' || c == '\r') && buf.length > 8) break
                if (buf.length > 512) break
            } else {
                if (buf.isNotEmpty()) break
                sleepQuiet(10)
            }
        }
        return buf.toString()
    }

    override fun stopMonitor() {
        writeRaw("\r".toByteArray(Charsets.US_ASCII))
        sleepQuiet(50)
        try {
            readUntilPrompt()
        } catch (_: Exception) {
            runCatching { command("ATI") }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun drain() {
        try {
            while (input.available() > 0) {
                input.read()
            }
        } catch (_: Exception) {
        }
    }

    private fun readUntilPrompt(): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (b < 0) break
                buf.append(b.toChar())
                if (b.toChar() == '>') break
            } else {
                sleepQuiet(10)
            }
        }
        val text = buf.toString()
        if (!text.contains('>')) {
            throw FordException.Timeout()
        }
        return text.trimEnd('>').trim()
    }

    companion object {
        fun connect(context: Context, address: String): BluetoothTransport {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: throw FordException.Other("Bluetooth not available")
            val adapter = manager.adapter
                ?: throw FordException.Other("Bluetooth adapter missing")
            if (!adapter.isEnabled) {
                throw FordException.Other("Bluetooth is disabled")
            }
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            adapter.cancelDiscovery()
            val socket = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (e: Exception) {
                // Fallback reflection channel used by some adapters / Android versions
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    @Suppress("UNCHECKED_CAST")
                    (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
                } catch (e2: Exception) {
                    throw FordException.Other("Bluetooth connect failed: ${e.message ?: e2.message}")
                }
            }
            return BluetoothTransport(socket, socket.inputStream, socket.outputStream)
        }
    }
}

enum class CanBus { HS, MS }

data class AdapterInfo(
    val identity: String,
    val isStn: Boolean,
    val voltage: Float?,
    /** How MS-CAN capability was decided (for UI). */
    val msCanHint: String = "",
)

/**
 * STN / ELM327 session helpers on top of any [Transport].
 */
class StnSession(private val transport: Transport) {
    var info: AdapterInfo? = null
        private set
    private var currentBus: CanBus? = null
    private var currentHeader: Int? = null

    fun initialize(btName: String? = null): AdapterInfo {
        runCatching { transport.command("ATZ") }
        sleepQuiet(800)
        transport.command("ATE0")
        runCatching { transport.command("ATL0") }
        // Spaces ON — denser ATS0 + ATH1 yields odd-length hex with 11-bit IDs
        runCatching { transport.command("ATS1") }
        runCatching { transport.command("ATH1") }
        runCatching { transport.command("ATAL") }

        val identity = transport.command("ATI").trim()
        val (isStn, hint) = detectStn(identity, btName)
        val voltage = runCatching { parseVoltage(transport.command("ATRV")) }.getOrNull()
        val adapter = AdapterInfo(identity, isStn, voltage, hint)
        info = adapter
        return adapter
    }

    /**
     * OBDLink MX often answers ATI as plain "ELM327 v1.x" without OBDLINK/STN.
     * Probe ST commands and bonded BT name before giving up on MS-CAN.
     */
    private fun detectStn(identity: String, btName: String?): Pair<Boolean, String> {
        val idUp = identity.uppercase()
        if (idUp.contains("STN") || idUp.contains("OBDLINK")) {
            return true to "STN/OBDLink (ATI)"
        }

        val btUp = btName?.uppercase().orEmpty()
        if (btUp.contains("OBDLINK") || btUp.contains("MX")) {
            return true to "OBDLink/MX (Bluetooth name)"
        }

        for (cmd in listOf("STI", "STDI")) {
            val resp = runCatching { transport.command(cmd) }.getOrNull() ?: continue
            val up = resp.uppercase()
            if (up.contains('?') && up.length < 8) continue
            if (up.contains("STN") || up.contains("OBDLINK") || up.contains("OK") ||
                (resp.isNotBlank() && !up.contains('?'))
            ) {
                return true to "STN ($cmd)"
            }
        }

        // Accept STP 53 → restore HS-CAN afterward
        val stp = runCatching { transport.command("STP 53") }.getOrNull()
        if (stp != null) {
            val up = stp.uppercase()
            if (!up.contains('?') && !up.contains("ERROR")) {
                runCatching { transport.command("ATSP6") }
                currentBus = CanBus.HS
                return true to "STN (STP 53 accepted)"
            }
        }
        runCatching { transport.command("ATSP6") }
        currentBus = CanBus.HS

        return false to "ELM-only (no STN/MS-CAN probe)"
    }

    fun selectBus(bus: CanBus) {
        if (currentBus == bus) return
        when (bus) {
            CanBus.HS -> transport.command("ATSP6")
            CanBus.MS -> {
                if (info?.isStn == true) {
                    transport.command("STP 53")
                } else {
                    runCatching { transport.command("ATSPB") }
                }
            }
        }
        currentBus = bus
        currentHeader = null
    }

    fun setHeader(header: Int) {
        if (currentHeader == header) return
        transport.command("ATSH%03X".format(header))
        val resp = header or 0x08
        runCatching { transport.command("ATCRA%03X".format(resp)) }
        currentHeader = header
    }

    fun clearReceiveFilter() {
        transport.clearReceiveFilter()
        currentHeader = null
    }

    fun startMonitor() {
        transport.startMonitor()
    }

    fun readMonitorChunk(maxWaitMs: Long): String = transport.readMonitorChunk(maxWaitMs)

    fun stopMonitor() {
        transport.stopMonitor()
        currentHeader = null
        currentBus = null
    }

    fun asTransport(): Transport = transport

    companion object {
        fun parseVoltage(s: String): Float? {
            val cleaned = s.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
            return cleaned.toFloatOrNull()
        }
    }
}
