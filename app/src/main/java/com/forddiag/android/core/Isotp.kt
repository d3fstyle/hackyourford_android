package com.forddiag.android.core

object Isotp {
    fun request(transport: Transport, udsPayload: ByteArray): ByteArray {
        val hex = udsPayload.toHexUpper()
        val raw = transport.sendHex(hex)
        val bytes = parseHexResponse(raw)
        return reassembleFromElm(bytes)
    }

    fun reassembleFromElm(bytes: ByteArray): ByteArray {
        if (bytes.isNotEmpty()) {
            val b0 = bytes[0].toInt() and 0xFF
            if (b0 == 0x7F || b0 in 0x40..0x7E) return bytes
        }
        return decodeFrames(bytes)
    }

    fun decodeFrames(data: ByteArray): ByteArray {
        if (data.isEmpty()) throw FordException.Isotp("empty frame")
        val pciType = (data[0].toInt() and 0xFF) shr 4
        return when (pciType) {
            0 -> {
                val len = data[0].toInt() and 0x0F
                if (data.size < 1 + len) {
                    throw FordException.Isotp("single frame too short")
                }
                data.copyOfRange(1, 1 + len)
            }
            1 -> {
                if (data.size < 2) throw FordException.Isotp("first frame too short")
                val len = (((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF))
                val out = ArrayList<Byte>(len)
                val ffEnd = minOf(data.size, 8)
                for (i in 2 until ffEnd) out.add(data[i])
                var i = 8
                while (out.size < len && i < data.size) {
                    val cfPci = (data[i].toInt() and 0xFF) shr 4
                    if (cfPci != 2) {
                        throw FordException.Isotp("expected consecutive frame at $i")
                    }
                    val chunkEnd = minOf(data.size, i + 8)
                    for (j in i + 1 until chunkEnd) out.add(data[j])
                    i += 8
                }
                if (out.size < len) {
                    throw FordException.Isotp("incomplete multi-frame: got ${out.size} of $len")
                }
                out.take(len).toByteArray()
            }
            2 -> throw FordException.Isotp("consecutive frame without first frame")
            3 -> throw FordException.Isotp("flow control frame not expected")
            else -> data
        }
    }

    fun encodeSingleFrame(payload: ByteArray): ByteArray {
        require(payload.size <= 7) { "payload too long for SF" }
        val frame = ByteArray(8)
        frame[0] = payload.size.toByte()
        payload.copyInto(frame, 1)
        return frame
    }

    fun encodeMultiFrame(payload: ByteArray): List<ByteArray> {
        if (payload.size <= 7) return listOf(encodeSingleFrame(payload))
        require(payload.size <= 4095)
        val frames = mutableListOf<ByteArray>()
        val len = payload.size
        val first = ByteArray(8)
        first[0] = (0x10 or ((len shr 8) and 0x0F)).toByte()
        first[1] = (len and 0xFF).toByte()
        payload.copyInto(first, 2, 0, minOf(6, len))
        frames += first
        var offset = 6
        var seq = 1
        while (offset < len) {
            val cf = ByteArray(8)
            cf[0] = (0x20 or (seq and 0x0F)).toByte()
            val end = minOf(offset + 7, len)
            payload.copyInto(cf, 1, offset, end)
            frames += cf
            offset = end
            seq = (seq + 1) and 0x0F
        }
        return frames
    }

    fun assembleFrames(frames: List<ByteArray>): ByteArray {
        val flat = frames.fold(ByteArray(0)) { acc, f -> acc + f }
        return decodeFrames(flat)
    }
}
