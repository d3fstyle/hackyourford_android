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
        assertTrue(c[0].changeScore >= 0.4)
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
    fun correlatePressSequenceFindsButtonBitInsideNoisyByte() {
        val stages = listOf(
            CaptureStage(0, 0, 99),
            CaptureStage(1, 100, 199),
            CaptureStage(2, 200, 299),
            CaptureStage(3, 300, 399),
        )
        val frames = mutableListOf<CanFrame>()
        var counter = 0
        fun payload(t: Long, buttonHigh: Boolean): ByteArray {
            val low = (counter and 0x0F).toByte()
            counter = (counter + 1) and 0x0F
            val button = if (buttonHigh) 0x80 else 0x00
            return byteArrayOf((low.toInt() or button).toByte())
        }
        // Noisy counter in nibble 0 throughout; button bit 7 quiet in baseline
        for (t in 0L..90 step 10) {
            frames += CanFrame(t, 0x3B3, payload(t, buttonHigh = false), "")
        }
        // 1 press: two edges on bit 7
        frames += CanFrame(110, 0x3B3, payload(110, false), "")
        frames += CanFrame(120, 0x3B3, payload(120, true), "")
        frames += CanFrame(130, 0x3B3, payload(130, false), "")
        for (t in 140L..190 step 10) {
            frames += CanFrame(t, 0x3B3, payload(t, false), "")
        }
        // 2 presses
        frames += CanFrame(210, 0x3B3, payload(210, false), "")
        frames += CanFrame(220, 0x3B3, payload(220, true), "")
        frames += CanFrame(230, 0x3B3, payload(230, false), "")
        frames += CanFrame(240, 0x3B3, payload(240, true), "")
        frames += CanFrame(250, 0x3B3, payload(250, false), "")
        for (t in 260L..290 step 10) {
            frames += CanFrame(t, 0x3B3, payload(t, false), "")
        }
        // 3 presses
        frames += CanFrame(310, 0x3B3, payload(310, false), "")
        frames += CanFrame(320, 0x3B3, payload(320, true), "")
        frames += CanFrame(330, 0x3B3, payload(330, false), "")
        frames += CanFrame(340, 0x3B3, payload(340, true), "")
        frames += CanFrame(350, 0x3B3, payload(350, false), "")
        frames += CanFrame(360, 0x3B3, payload(360, true), "")
        frames += CanFrame(370, 0x3B3, payload(370, false), "")
        for (t in 380L..390 step 10) {
            frames += CanFrame(t, 0x3B3, payload(t, false), "")
        }

        val c = correlatePressSequence(frames, stages)
        val hit = c.find { it.canId == 0x3B3 && it.byteIndex == 0 && it.bitIndex == 7 }
        assertTrue("Expected bit 7 candidate inside noisy byte, got: $c", hit != null)
        assertTrue(hit!!.changeScore >= 0.4)
    }

    @Test
    fun correlatePressSequenceAcceptsProportionalCounts() {
        val stages = listOf(
            CaptureStage(0, 0, 99),
            CaptureStage(1, 100, 199),
            CaptureStage(2, 200, 299),
            CaptureStage(3, 300, 399),
        )
        val frames = mutableListOf<CanFrame>()
        frames += CanFrame(10, 0x3B3, byteArrayOf(0x00), "")
        frames += CanFrame(50, 0x3B3, byteArrayOf(0x00), "")

        fun addEdges(start: Long, edges: Int) {
            var v = 0
            var t = start
            frames += CanFrame(t, 0x3B3, byteArrayOf(v.toByte()), "")
            repeat(edges) {
                t += 5
                v = v xor 1
                frames += CanFrame(t, 0x3B3, byteArrayOf(v.toByte()), "")
            }
        }
        // ~10 / 20 / 30 edges — not exact 1/2/3 or 2/4/6
        addEdges(110, 10)
        addEdges(210, 20)
        addEdges(310, 30)

        val c = correlatePressSequence(frames, stages)
        assertFalse("Proportional 10/20/30 should match, got: $c", c.isEmpty())
        assertEquals(0x3B3, c[0].canId)
        assertTrue(c[0].changeScore >= 0.4)
    }

    @Test
    fun sequenceSimilarityPerfectMatch() {
        assertEquals(1.0, sequenceSimilarity(listOf(2.0, 4.0, 6.0), listOf(2.0, 4.0, 6.0)), 1e-9)
    }

    @Test
    fun pearsonCorrelationPerfectProportion() {
        assertEquals(1.0, pearsonCorrelation(listOf(10.0, 20.0, 30.0), listOf(1.0, 2.0, 3.0)), 1e-9)
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

    @Test
    fun monitorOutputEndedDetectsBufferFull() {
        assertTrue(monitorOutputEnded("7E8 08 AA\rBUFFER FULL\r"))
        assertTrue(monitorOutputEnded("STOPPED"))
        assertFalse(monitorOutputEnded("7E8 08 AA BB CC DD EE FF"))
        assertFalse(monitorOutputEnded(""))
    }

    @Test
    fun readSniffChunkRestartsAtmaAfterBufferFull() {
        val t = MockTransport()
        val stn = StnSession(t)
        val session = ScannerSession("AA:BB", t, stn)
        t.enqueueMonitorChunk("7E8 08 41 0C 1A F8\rBUFFER FULL\r", hitPrompt = true)
        val frames = session.readSniffChunk(50)
        assertFalse(frames.isEmpty())
        assertTrue(
            "expected ATMA restart after BUFFER FULL, sent=$t.sent",
            t.sent.count { it.equals("ATMA", ignoreCase = true) } >= 1,
        )
        assertTrue(t.monitoring)
    }

    @Test
    fun pidLabelCoversTorqueCatalog() {
        assertEquals("RPM", pidLabel(0x0C))
        assertEquals("Fuel level", pidLabel(0x2F))
        assertEquals("Engine Fuel Rate", pidLabel(0x9D))
        assertEquals("Cylinder Fuel Rate", pidLabel(0xA2))
        assertEquals(null, pidLabel(0x00))
        assertEquals(null, pidLabel(0xA0))
    }

    @Test
    fun listSupportedPidsContinuesPast60() {
        val t = MockTransport()
        // bit0 of last byte = next range supported for 00/20/40/60/80
        t.expect("0100", "41 00 BE 1F A8 13") // next=1
        t.expect("0120", "41 20 80 00 00 01") // next=1, claims 0x21
        t.expect("0140", "41 40 FE D0 00 01") // next=1
        t.expect("0160", "41 60 00 00 00 01") // next=1
        t.expect("0180", "41 80 00 00 00 01") // next=1
        t.expect("01A0", "41 A0 40 00 00 00") // next=0, bit1 → 0xA2
        val pids = listSupportedPids(t)
        assertTrue(pids.contains(0x0C))
        assertTrue(pids.contains(0x21))
        assertTrue("expected 0xA2 from 01A0 bitmask, got $pids", pids.contains(0xA2))
        assertFalse(pids.contains(0x80))
        assertFalse(pids.contains(0xA0))
    }
}
