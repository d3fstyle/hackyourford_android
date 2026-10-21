package com.forddiag.android.core

data class Dtc(val code: String, val status: Int, val raw: Int)

data class DidValue(
    val did: Int,
    val data: ByteArray,
    val hex: String,
    val ascii: String?,
)

class UdsClient(private val transport: Transport) {
    fun rawRequest(payload: ByteArray): ByteArray {
        var response = Isotp.request(transport, payload)
        if (response.size >= 3 &&
            (response[0].toInt() and 0xFF) == 0x7F &&
            (response[2].toInt() and 0xFF) == 0x78
        ) {
            response = Isotp.request(transport, payload)
        }
        checkNegative(response, payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
        return response
    }

    fun diagnosticSessionControl(session: Int) {
        val resp = rawRequest(byteArrayOf(SID_DSC.toByte(), session.toByte()))
        if ((resp.firstOrNull()?.toInt()?.and(0xFF) ?: 0) != SID_DSC + 0x40) {
            throw FordException.Uds("unexpected session response")
        }
    }

    fun testerPresent() {
        val resp = rawRequest(byteArrayOf(SID_TESTER_PRESENT.toByte(), 0x00))
        if ((resp.firstOrNull()?.toInt()?.and(0xFF) ?: 0) != SID_TESTER_PRESENT + 0x40) {
            throw FordException.Uds("unexpected tester present")
        }
    }

    fun readDataByIdentifier(did: Int): DidValue {
        val payload = byteArrayOf(
            SID_RDBI.toByte(),
            ((did shr 8) and 0xFF).toByte(),
            (did and 0xFF).toByte(),
        )
        val resp = rawRequest(payload)
        if (resp.size < 3 || (resp[0].toInt() and 0xFF) != SID_RDBI + 0x40) {
            throw FordException.Uds("bad ReadDataById response")
        }
        val got = ((resp[1].toInt() and 0xFF) shl 8) or (resp[2].toInt() and 0xFF)
        if (got != did) throw FordException.Uds("DID mismatch")
        val data = resp.copyOfRange(3, resp.size)
        return DidValue(did, data, data.toHexUpper(), bytesToAscii(data))
    }

    fun readDtcs(): List<Dtc> {
        val resp = rawRequest(byteArrayOf(SID_READ_DTC.toByte(), 0x02, 0xFF.toByte()))
        if ((resp.firstOrNull()?.toInt()?.and(0xFF) ?: 0) != SID_READ_DTC + 0x40) {
            throw FordException.Uds("bad ReadDTC response")
        }
        if (resp.size < 3) return emptyList()
        val out = mutableListOf<Dtc>()
        var i = 3
        while (i + 3 < resp.size) {
            val a = resp[i].toInt() and 0xFF
            val b = resp[i + 1].toInt() and 0xFF
            val c = resp[i + 2].toInt() and 0xFF
            val status = resp[i + 3].toInt() and 0xFF
            val raw = (a shl 16) or (b shl 8) or c
            out += Dtc(formatDtcIso(a, b, c), status, raw)
            i += 4
        }
        return out
    }

    fun clearDtcs() {
        val resp = rawRequest(
            byteArrayOf(SID_CLEAR_DTC.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        if ((resp.firstOrNull()?.toInt()?.and(0xFF) ?: 0) != SID_CLEAR_DTC + 0x40) {
            throw FordException.Uds("bad ClearDTC response")
        }
    }

    companion object {
        const val SID_DSC = 0x10
        const val SID_CLEAR_DTC = 0x14
        const val SID_READ_DTC = 0x19
        const val SID_RDBI = 0x22
        const val SID_TESTER_PRESENT = 0x3E
        const val SESSION_EXTENDED = 0x03

        fun formatDtcIso(a: Int, b: Int, c: Int): String {
            val prefix = when ((a and 0xC0) shr 6) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }
            val d1 = (a and 0x30) shr 4
            val d2 = a and 0x0F
            return "%s%d%X%02X%02X".format(prefix, d1, d2, b, c)
        }

        fun bytesToAscii(data: ByteArray): String? {
            if (data.isEmpty()) return null
            if (!data.all { b ->
                    val c = b.toInt() and 0xFF
                    c == 0 || c.toChar().isLetterOrDigit() || c.toChar().isWhitespace() ||
                        c in 0x20..0x7E
                }
            ) {
                return null
            }
            val s = data.map { b ->
                val c = b.toInt() and 0xFF
                if (c == 0) ' ' else c.toChar()
            }.joinToString("").trim()
            return s.ifEmpty { null }
        }

        private fun checkNegative(response: ByteArray, requestedSid: Int) {
            if (response.size >= 3 && (response[0].toInt() and 0xFF) == 0x7F) {
                val nrc = response[2].toInt() and 0xFF
                if (nrc == 0x78) return
                throw FordException.UdsNegative(nrc, nrcDescription(nrc))
            }
            if (response.isEmpty()) {
                throw FordException.Uds("empty response for SID 0x%02X".format(requestedSid))
            }
        }
    }
}
