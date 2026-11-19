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
    unit: String,
    format: (Double) -> String = { v ->
        if (v == v.toLong().toDouble()) "%.0f".format(v) else "%.2f".format(v)
    },
    decode: (ByteArray) -> Double?,
) = ObdPidDef(unit, decode, format)

private fun pct255(d: ByteArray): Double? =
    if (d.isEmpty()) null else u8(d) * 100.0 / 255.0

private fun tempC(d: ByteArray): Double? =
    if (d.isEmpty()) null else (u8(d) - 40).toDouble()

private fun fuelTrim(d: ByteArray): Double? =
    if (d.isEmpty()) null else (u8(d) - 128) * 100.0 / 128.0

private fun catalystTemp(d: ByteArray): Double? =
    if (d.size < 2) null else u16(d) / 10.0 - 40.0

/** SAE J1979 Mode 01 display names (Torque table + short overrides for common PIDs). */
private val MODE01_NAMES: Array<String> = arrayOf(
    "", // 0x00
    "Number of trouble codes and I/M info", // 0x01
    "Freeze DTC", // 0x02
    "Fuel system status", // 0x03
    "Engine load", // 0x04
    "Coolant temp", // 0x05
    "Short term fuel trim B1", // 0x06
    "Long term fuel trim B1", // 0x07
    "Short term fuel trim B2", // 0x08
    "Long term fuel trim B2", // 0x09
    "Fuel pressure", // 0x0A
    "MAP", // 0x0B
    "RPM", // 0x0C
    "Speed", // 0x0D
    "Timing advance", // 0x0E
    "Intake air temp", // 0x0F
    "MAF", // 0x10
    "Throttle", // 0x11
    "Sec. air status", // 0x12
    "Oxygen sensors present", // 0x13
    "Bank 1, Sensor 1:Oxygen sensor & Short Term Fuel Trim", // 0x14
    "Bank 1, Sensor 2:Oxygen sensor & Short Term Fuel Trim", // 0x15
    "Bank 1, Sensor 3:Oxygen sensor & Short Term Fuel Trim", // 0x16
    "Bank 1, Sensor 4:Oxygen sensor & Short Term Fuel Trim", // 0x17
    "Bank 2, Sensor 1:Oxygen sensor & Short Term Fuel Trim", // 0x18
    "Bank 2, Sensor 2:Oxygen sensor & Short Term Fuel Trim", // 0x19
    "Bank 2, Sensor 3:Oxygen sensor & Short Term Fuel Trim", // 0x1A
    "Bank 2, Sensor 4:Oxygen sensor & Short Term Fuel Trim", // 0x1B
    "OBD standards this vehicle conforms to", // 0x1C
    "Oxygen sensors present", // 0x1D
    "Auxiliary input status", // 0x1E
    "Run time", // 0x1F
    "", // 0x20
    "Distance with MIL", // 0x21
    "Fuel Pressure relative to vacuum", // 0x22
    "Fuel Rail Pressure", // 0x23
    "O2 S1 Equiv. Ratio and/or Voltage", // 0x24
    "O2 S2 Equiv. Ratio and/or Voltage", // 0x25
    "O2 S3 Equiv. Ratio and/or Voltage", // 0x26
    "O2 S4 Equiv. Ratio and/or Voltage", // 0x27
    "O2 S5 Equiv. Ratio and/or Voltage", // 0x28
    "O2 S6 Equiv. Ratio and/or Voltage", // 0x29
    "O2 S7 Equiv. Ratio and/or Voltage", // 0x2A
    "O2 S8 Equiv. Ratio and/or Voltage", // 0x2B
    "Commanded EGR", // 0x2C
    "EGR Error", // 0x2D
    "Commanded evaporative purge", // 0x2E
    "Fuel level", // 0x2F
    "Number of warm-ups since codes cleared", // 0x30
    "Distance traveled since codes cleared", // 0x31
    "Evap. System Vapor Pressure", // 0x32
    "Barometric pressure", // 0x33
    "O2 S1 Equiv. Ratio and/or Current", // 0x34
    "O2 S2 Equiv. Ratio and/or Current", // 0x35
    "O2 S3 Equiv. Ratio and/or Current", // 0x36
    "O2 S4 Equiv. Ratio and/or Current", // 0x37
    "O2 S5 Equiv. Ratio and/or Current", // 0x38
    "O2 S6 Equiv. Ratio and/or Current", // 0x39
    "O2 S7 Equiv. Ratio and/or Current", // 0x3A
    "O2 S8 Equiv. Ratio and/or Current", // 0x3B
    "Catalyst Temp - Bank 1, Sensor 1", // 0x3C
    "Catalyst Temp - Bank 2, Sensor 1", // 0x3D
    "Catalyst Temp - Bank 1, Sensor 2", // 0x3E
    "Catalyst Temp - Bank 2, Sensor 2", // 0x3F
    "", // 0x40
    "Monitor status this drive cycle", // 0x41
    "Control module voltage", // 0x42
    "Absolute load", // 0x43
    "Command equivalence ratio", // 0x44
    "Relative throttle", // 0x45
    "Ambient air temp", // 0x46
    "Absolute throttle position B", // 0x47
    "Absolute throttle position C", // 0x48
    "Accelerator pedal position D", // 0x49
    "Accelerator pedal position E", // 0x4A
    "Accelerator pedal position F", // 0x4B
    "Commanded throttle actuator", // 0x4C
    "Time run with MIL on", // 0x4D
    "Time since trouble codes cleared", // 0x4E
    "Max val for equivalence ratio, oxygen sensor voltage, oxygen sensor current, and intake manifold absolute pressure", // 0x4F
    "Max val for air flow rate from mass air flow sensor", // 0x50
    "Fuel Type", // 0x51
    "Ethanol fuel%", // 0x52
    "Absoulute Evap system Vapour Pressure", // 0x53
    "Evap system vapor pressure", // 0x54
    "Short term secondary oxygen sensor trim bank 1 and bank 3", // 0x55
    "Long term secondary oxygen sensor trim bank 1 and bank 3", // 0x56
    "Short term secondary oxygen sensor trim bank 2 and bank 4", // 0x57
    "Long term secondary oxygen sensor trim bank 2 and bank 4", // 0x58
    "Fuel rail pressure (absolute)", // 0x59
    "Relative accelerator pedal position", // 0x5A
    "Hybrid battery pack remaining life", // 0x5B
    "Oil temp", // 0x5C
    "Fuel injection timing", // 0x5D
    "Fuel rate", // 0x5E
    "Emission requirements to which vehicle is designed", // 0x5F
    "", // 0x60
    "Driver's demand engine - percent torque", // 0x61
    "Actual engine - percent torque", // 0x62
    "Engine reference torque", // 0x63
    "Engine percent torque data", // 0x64
    "Auxiliary input / output supported", // 0x65
    "Mass air flow sensor", // 0x66
    "Engine coolant temperature", // 0x67
    "Intake air temperature sensor", // 0x68
    "Commanded EGR and EGR Error", // 0x69
    "Commanded Diesel intake air flow control and relative intake air flow position", // 0x6A
    "Exhaust gas recirculation temperature", // 0x6B
    "Commanded throttle actuator control and relative throttle position", // 0x6C
    "Fuel pressure control system", // 0x6D
    "Injection pressure control system", // 0x6E
    "Turbocharger compressor inlet pressure", // 0x6F
    "Boost pressure control", // 0x70
    "Variable Geometry turbo (VGT) control", // 0x71
    "Wastegate control", // 0x72
    "Exhaust pressure", // 0x73
    "Turbocharger RPM", // 0x74
    "Turbocharger A temperature", // 0x75
    "Turbocharger B temperature", // 0x76
    "Charge air cooler temperature (CACT)", // 0x77
    "Exhaust Gas temperature (EGT) Bank 1", // 0x78
    "Exhaust Gas temperature (EGT) Bank 2", // 0x79
    "Diesel particulate filter (DPF) Bank 1", // 0x7A
    "Diesel particulate filter (DPF) Bank 2", // 0x7B
    "Diesel Particulate filter (DPF) temperature", // 0x7C
    "NOx NTE control area status", // 0x7D
    "PM NTE control area status", // 0x7E
    "Engine run time", // 0x7F
    "", // 0x80
    "Engine run time for AECD #1 - #5", // 0x81
    "Engine run time for AECD #6 - #10", // 0x82
    "NOx sensor", // 0x83
    "Manifold surface temperature", // 0x84
    "NOx control system", // 0x85
    "Particulate matter (PM) sensor", // 0x86
    "Intake manifold absolute pressure", // 0x87
    "SCR inducement system actual state", // 0x88
    "Engine Run Time for AECD #11 - #15", // 0x89
    "Engine Run Time for AECD #16 - #20", // 0x8A
    "Diesel Aftertreatment Status", // 0x8B
    "O2 Sensor (Wide Range)", // 0x8C
    "Absolute Throttle Position G", // 0x8D
    "Engine Friction - Percent Torque", // 0x8E
    "Particulate Matter (PM) Sensor Output", // 0x8F
    "WWH-OBD Vehicle OBD System Information", // 0x90
    "WWH-OBD ECU OBD System Information", // 0x91
    "Fuel System Control Status(Compression Ignition)", // 0x92
    "WWH-OBD Vehicle OBD Counters", // 0x93
    "NOx control - driver inducement system status and counters", // 0x94
    "", // 0x95
    "", // 0x96
    "", // 0x97
    "Exhaust Gas Temperature (EGT) Bank 1", // 0x98
    "Exhaust Gas Temperature (EGT) Bank 2", // 0x99
    "Hybrid/EV Vehicle System Data", // 0x9A
    "Diesel Exhaust Fluid Sensor Output", // 0x9B
    "O2 Sensor (Wide Range)", // 0x9C
    "Engine Fuel Rate", // 0x9D
    "Engine Exhaust Flow Rate", // 0x9E
    "Fuel System Percentage Use", // 0x9F
    "", // 0xA0
    "NOx Sensor Corrected", // 0xA1
    "Cylinder Fuel Rate" // 0xA2
)


/** Numeric decoders for common Mode 01 PIDs (label comes from [MODE01_NAMES]). */
private val PID_DECODERS: Map<Int, ObdPidDef> = mapOf(
    0x04 to pid("%") { pct255(it) },
    0x05 to pid("°C") { tempC(it) },
    0x06 to pid("%") { fuelTrim(it) },
    0x07 to pid("%") { fuelTrim(it) },
    0x08 to pid("%") { fuelTrim(it) },
    0x09 to pid("%") { fuelTrim(it) },
    0x0A to pid("kPa") { d -> if (d.isEmpty()) null else u8(d) * 3.0 },
    0x0B to pid("kPa") { d -> if (d.isEmpty()) null else u8(d).toDouble() },
    0x0C to pid("rpm", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d) / 4.0
    },
    0x0D to pid("km/h", format = { "%.0f".format(it) }) { d ->
        if (d.isEmpty()) null else u8(d).toDouble()
    },
    0x0E to pid("°") { d -> if (d.isEmpty()) null else u8(d) / 2.0 - 64.0 },
    0x0F to pid("°C") { tempC(it) },
    0x10 to pid("g/s") { d -> if (d.size < 2) null else u16(d) / 100.0 },
    0x11 to pid("%") { pct255(it) },
    0x1F to pid("s", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x21 to pid("km", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x22 to pid("kPa") { d -> if (d.size < 2) null else u16(d) * 0.079 },
    0x23 to pid("kPa") { d -> if (d.size < 2) null else u16(d) * 10.0 },
    0x2C to pid("%") { pct255(it) },
    0x2D to pid("%") { fuelTrim(it) },
    0x2E to pid("%") { pct255(it) },
    0x2F to pid("%") { pct255(it) },
    0x30 to pid("", format = { "%.0f".format(it) }) { d ->
        if (d.isEmpty()) null else u8(d).toDouble()
    },
    0x31 to pid("km", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x33 to pid("kPa") { d -> if (d.isEmpty()) null else u8(d).toDouble() },
    0x3C to pid("°C") { catalystTemp(it) },
    0x3D to pid("°C") { catalystTemp(it) },
    0x3E to pid("°C") { catalystTemp(it) },
    0x3F to pid("°C") { catalystTemp(it) },
    0x42 to pid("V") { d -> if (d.size < 2) null else u16(d) / 1000.0 },
    0x43 to pid("%") { d -> if (d.size < 2) null else u16(d) * 100.0 / 255.0 },
    0x44 to pid("", format = { "%.3f".format(it) }) { d ->
        if (d.size < 2) null else u16(d) / 32768.0
    },
    0x45 to pid("%") { pct255(it) },
    0x46 to pid("°C") { tempC(it) },
    0x47 to pid("%") { pct255(it) },
    0x48 to pid("%") { pct255(it) },
    0x49 to pid("%") { pct255(it) },
    0x4A to pid("%") { pct255(it) },
    0x4B to pid("%") { pct255(it) },
    0x4C to pid("%") { pct255(it) },
    0x4D to pid("min", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x4E to pid("min", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x52 to pid("%") { pct255(it) },
    0x59 to pid("kPa") { d -> if (d.size < 2) null else u16(d) * 10.0 },
    0x5A to pid("%") { pct255(it) },
    0x5B to pid("%") { pct255(it) },
    0x5C to pid("°C") { tempC(it) },
    0x5D to pid("°") { d ->
        if (d.size < 2) null else u16(d) / 128.0 - 210.0
    },
    0x5E to pid("L/h") { d -> if (d.size < 2) null else u16(d) / 20.0 },
    0x61 to pid("%", format = { "%.0f".format(it) }) { d ->
        if (d.isEmpty()) null else (u8(d) - 125).toDouble()
    },
    0x62 to pid("%", format = { "%.0f".format(it) }) { d ->
        if (d.isEmpty()) null else (u8(d) - 125).toDouble()
    },
    0x63 to pid("Nm", format = { "%.0f".format(it) }) { d ->
        if (d.size < 2) null else u16(d).toDouble()
    },
    0x8D to pid("%") { pct255(it) },
    0x8E to pid("%", format = { "%.0f".format(it) }) { d ->
        if (d.isEmpty()) null else (u8(d) - 125).toDouble()
    },
)

/** Friendly label for a Mode 01 PID, or null if unknown. */
fun pidLabel(pid: Int): String? =
    MODE01_NAMES.getOrNull(pid)?.takeIf { it.isNotBlank() }

/** Decode Mode 01 PID into numeric value + display text. */
fun decodePidFull(pid: Int, data: ByteArray): ObdPidDecoded {
    val label = pidLabel(pid) ?: "PID %02X".format(pid)
    val def = PID_DECODERS[pid]
    if (def == null) {
        return ObdPidDecoded(
            value = rawNumericDid(data),
            unit = null,
            label = label,
            text = null,
        )
    }
    val value = def.decode(data)
    val text = value?.let { v ->
        val formatted = def.format(v)
        if (def.unit.isBlank()) formatted else "$formatted ${def.unit}"
    }
    return ObdPidDecoded(value = value, unit = def.unit.takeIf { it.isNotBlank() }, label = label, text = text)
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

private val SUPPORT_BITMASK_PIDS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

/** Query supported PIDs via Mode 01 support bitmasks (00/20/…/C0). */
fun listSupportedPids(transport: Transport): List<Int> {
    val supported = mutableListOf<Int>()
    for (base in SUPPORT_BITMASK_PIDS) {
        val sample = runCatching { readPid(transport, base) }.getOrNull() ?: break
        if (sample.raw.size < 4) break
        val nextSupported = (sample.raw[3].toInt() and 0x01) != 0
        supported += parseSupportedBitmask(base, sample.raw.copyOfRange(0, 4))
        if (!nextSupported) break
    }
    return supported.filter { it !in SUPPORT_BITMASK_PIDS }
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
