package com.forddiag.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun parseHexIgnoresSearchingAndCr() {
        val data = parseHexResponse("SEARCHING...\r62 F190 41 42\r>")
        assertEquals(
            listOf(0x62, 0xF1, 0x90, 0x41, 0x42).map { it.toByte() },
            data.toList(),
        )
    }

    @Test
    fun isotpSingleFrameRoundtrip() {
        val payload = byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())
        val frame = Isotp.encodeSingleFrame(payload)
        assertEquals(0x03, frame[0].toInt() and 0xFF)
        val decoded = Isotp.decodeFrames(frame.copyOf(4))
        assertTrue(payload.contentEquals(decoded))
    }

    @Test
    fun isotpMultiFrameRoundtrip() {
        val payload = ByteArray(20) { it.toByte() }
        val frames = Isotp.encodeMultiFrame(payload)
        assertTrue(frames.size >= 2)
        val decoded = Isotp.assembleFrames(frames)
        assertTrue(payload.contentEquals(decoded))
    }

    @Test
    fun udsReadDidViaMock() {
        val t = MockTransport()
        t.expect("22F190", "62F19057464C414241")
        val client = UdsClient(t)
        val v = client.readDataByIdentifier(0xF190)
        assertEquals(0xF190, v.did)
        assertEquals("WFLABA", v.ascii)
    }

    @Test
    fun udsNegativeResponse() {
        val t = MockTransport()
        t.expect("22F190", "7F2231")
        val client = UdsClient(t)
        try {
            client.readDataByIdentifier(0xF190)
            throw AssertionError("expected UdsNegative")
        } catch (e: FordException.UdsNegative) {
            assertEquals(0x31, e.nrc)
        }
    }

    @Test
    fun formatP0301() {
        assertEquals("P030100", UdsClient.formatDtcIso(0x03, 0x01, 0x00))
    }

    @Test
    fun stnVoltageParse() {
        assertEquals(12.3f, StnSession.parseVoltage("12.3V"))
    }

    @Test
    fun decodeRpm() {
        assertEquals("1726 rpm", decodePid(0x0C, byteArrayOf(0x1A, 0xF8.toByte())))
    }

    @Test
    fun bitmask0100() {
        val bits = byteArrayOf(0xBE.toByte(), 0x1F, 0xA8.toByte(), 0x13)
        val pids = parseSupportedBitmask(0x00, bits)
        assertTrue(pids.contains(0x0C))
        assertTrue(pids.contains(0x0D))
    }

    @Test
    fun readPidMock() {
        val t = MockTransport()
        t.expect("010C", "41 0C 1A F8")
        val s = readPid(t, 0x0C)
        assertEquals(0x0C, s.pid)
        assertTrue(s.decoded!!.contains("rpm"))
    }

    @Test
    fun parseHexStripsElevenBitCanHeaderOddLength() {
        // ATH1 + ATS0 style: 7E8 + 03 41 0C 1A F8 without spaces
        val data = parseHexResponse("7E803410C1AF8\r>")
        assertEquals(
            listOf(0x03, 0x41, 0x0C, 0x1A, 0xF8).map { it.toByte() },
            data.toList(),
        )
    }

    @Test
    fun parseHexSpacedHeaderStillWorks() {
        val data = parseHexResponse("7E8 03 41 0C 1A F8\r>")
        // spaces stripped → same odd-length continuous hex → ID stripped
        assertEquals(
            listOf(0x03, 0x41, 0x0C, 0x1A, 0xF8).map { it.toByte() },
            data.toList(),
        )
    }

    @Test
    fun readPidWithCanHeader() {
        val t = MockTransport()
        t.expect("010C", "7E803410C1AF8")
        val s = readPid(t, 0x0C)
        assertEquals(0x0C, s.pid)
        assertEquals("1726 rpm", s.decoded)
    }

    @Test
    fun stnDetectFromBluetoothName() {
        val t = MockTransport()
        // ATI returns generic ELM — ST probes return OK from mock
        val stn = StnSession(t)
        val info = stn.initialize(btName = "OBDLink MX")
        assertTrue(info.isStn)
        assertTrue(info.msCanHint.contains("Bluetooth", ignoreCase = true) ||
            info.msCanHint.contains("MX", ignoreCase = true) ||
            info.msCanHint.contains("STI", ignoreCase = true) ||
            info.msCanHint.contains("STN", ignoreCase = true))
    }

    @Test
    fun parseSpacedFrame() {
        val f = parseMonitorLine("7E8 06 41 0C 1A F8", 100)!!
        assertEquals(0x7E8, f.id)
        assertEquals(0x06, f.data[0].toInt() and 0xFF)
    }

    @Test
    fun correlateFindsChangingByte() {
        val frames = listOf(
            CanFrame(0, 0x3B3, byteArrayOf(0x00, 0x00), ""),
            CanFrame(10, 0x3B3, byteArrayOf(0x00, 0x01), ""),
            CanFrame(20, 0x3B3, byteArrayOf(0x00, 0x00), ""),
            CanFrame(50, 0x3B3, byteArrayOf(0x00, 0x00), ""),
        )
        val c = correlatePressPattern(frames, listOf(5L to 25L))
        assertFalse(c.isEmpty())
        assertEquals(0x3B3, c[0].canId)
        assertEquals(1, c[0].byteIndex)
    }

    @Test
    fun correlatePressSequenceFindsButtonByte() {
        // Stages: baseline 0..99, 1-press 100..199, 2-press 200..299, 3-press 300..399
        val stages = listOf(
            CaptureStage(0, 0, 99),
            CaptureStage(1, 100, 199),
            CaptureStage(2, 200, 299),
            CaptureStage(3, 300, 399),
        )
        val frames = mutableListOf<CanFrame>()
        // Stable baseline
        frames += CanFrame(10, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(50, 0x3B3, byteArrayOf(0x00, 0x00), "")
        // 1 press → 1 change (and back)
        frames += CanFrame(110, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(120, 0x3B3, byteArrayOf(0x00, 0x01), "")
        frames += CanFrame(130, 0x3B3, byteArrayOf(0x00, 0x00), "")
        // 2 presses → 2 changes (press+release each = 4 edges; use k=2 path with 2 toggles = 4)
        // Use single-edge-per-press: 2 value flips
        frames += CanFrame(210, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(220, 0x3B3, byteArrayOf(0x00, 0x01), "")
        frames += CanFrame(230, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(240, 0x3B3, byteArrayOf(0x00, 0x01), "")
        frames += CanFrame(250, 0x3B3, byteArrayOf(0x00, 0x00), "")
        // 3 presses → 6 edges (k=2)
        frames += CanFrame(310, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(320, 0x3B3, byteArrayOf(0x00, 0x01), "")
        frames += CanFrame(330, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(340, 0x3B3, byteArrayOf(0x00, 0x01), "")
        frames += CanFrame(350, 0x3B3, byteArrayOf(0x00, 0x00), "")
        frames += CanFrame(360, 0x3B3, byteArrayOf(0x00, 0x01), "")
        frames += CanFrame(370, 0x3B3, byteArrayOf(0x00, 0x00), "")

        val c = correlatePressSequence(frames, stages)
        assertFalse(c.isEmpty())
        assertEquals(0x3B3, c[0].canId)
        assertEquals(1, c[0].byteIndex)
        assertTrue(c[0].changeScore >= 0.5)
    }

    @Test
    fun correlatePressSequenceRejectsPeriodicCounter() {
        val stages = listOf(
            CaptureStage(0, 0, 99),
            CaptureStage(1, 100, 199),
            CaptureStage(2, 200, 299),
            CaptureStage(3, 300, 399),
        )
        val frames = mutableListOf<CanFrame>()
        var counter = 0
        for (t in 0L..400 step 10) {
            frames += CanFrame(t, 0x3B3, byteArrayOf(counter.toByte(), 0x00), "")
            counter = (counter + 1) and 0xFF
        }
        val c = correlatePressSequence(frames, stages)
        assertTrue(
            "Periodic counter should be filtered out, got: $c",
            c.none { it.canId == 0x3B3 && it.byteIndex == 0 },
        )
    }

    @Test
    fun sequenceSimilarityPerfectMatch() {
        assertEquals(1.0, sequenceSimilarity(listOf(2.0, 4.0, 6.0), listOf(2.0, 4.0, 6.0)), 1e-9)
    }

    @Test
    fun diffDidSnapshotsDetectsChange() {
        val before = mapOf(0xF190 to "AA", 0xDE00 to "00")
        val after = mapOf(0xF190 to "BB", 0xDE00 to "00", 0xDE01 to "11")
        val diff = diffDidSnapshots(before, after)
        assertEquals(2, diff.size)
        assertEquals(0xDE01, diff[0].first)
        assertEquals(0xF190, diff[1].first)
        assertEquals("AA", diff[1].second)
        assertEquals("BB", diff[1].third)
    }
}
