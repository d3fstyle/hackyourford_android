package com.forddiag.android.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Mode01ScanFixtureTest {
    @Serializable
    private data class ScanRow(val pid: Int, val raw: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixtures(): List<ScanRow> {
        val path = File("src/test/resources/fixtures/mode01_scan_export.json")
        assertTrue("missing ${path.absolutePath}", path.isFile)
        return json.decodeFromString(path.readText())
    }

    private fun payloadForPid(pid: Int, raw: String): ByteArray {
        val bytes = normalizeElmPayload(parseHexResponse(raw))
        var i = 0
        while (i + 1 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0x41 &&
                (bytes[i + 1].toInt() and 0xFF) == pid
            ) {
                return bytes.copyOfRange(i + 2, bytes.size)
            }
            i++
        }
        error("no 41 %02X payload in $raw".format(pid))
    }

    @Test
    fun fixtureParsesAllMode01Raws() {
        val rows = loadFixtures()
        assertTrue(rows.size >= 50)
        for (row in rows) {
            val bytes = normalizeElmPayload(parseHexResponse(row.raw))
            assertTrue("empty payload for PID %02X".format(row.pid), bytes.isNotEmpty())
            val has41 = bytes.toList().windowed(2).any { (a, b) ->
                (a.toInt() and 0xFF) == 0x41 && (b.toInt() and 0xFF) == row.pid
            }
            assertTrue("missing 41 %02X in ${row.raw}".format(row.pid), has41)
        }
    }

    @Test
    fun decodedPidsHaveValueAndLabel() {
        val rows = loadFixtures()
        val withDecoder = rows.filter { pidLabel(it.pid) != null && it.pid in DECODED_PIDS }
        assertTrue("expected several decoded PIDs in scan", withDecoder.size >= 5)
        for (row in withDecoder) {
            val data = payloadForPid(row.pid, row.raw)
            val decoded = decodePidFull(row.pid, data)
            assertEquals(pidLabel(row.pid), decoded.label)
            assertNotNull("PID %02X should decode numeric".format(row.pid), decoded.value)
            assertFalse(decoded.label.startsWith("PID "))
        }
    }

    @Test
    fun speedPidZeroFromScan() {
        val row = loadFixtures().first { it.pid == 0x0D }
        val data = payloadForPid(0x0D, row.raw)
        assertEquals(0.0, decodePidFull(0x0D, data).value!!, 1e-9)
        assertEquals("Speed", decodePidFull(0x0D, data).label)
    }

    @Test
    fun coolantTempFromScan() {
        // raw 7e803410573 → 41 05 73 → 0x73-40 = 75°C
        val row = loadFixtures().first { it.pid == 0x05 }
        val data = payloadForPid(0x05, row.raw)
        assertEquals(0x73 - 40.0, decodePidFull(0x05, data).value!!, 1e-9)
    }

    @Test
    fun supportedAssetOmitsBitmasks() {
        val path = File("src/main/assets/ford/mode01_scan_supported.json")
        assertTrue(path.isFile)
        val pids = json.decodeFromString<List<Int>>(path.readText())
        assertFalse(pids.contains(0x00))
        assertFalse(pids.contains(0x20))
        assertFalse(pids.contains(0x40))
        assertFalse(pids.contains(0x60))
        assertFalse(pids.contains(0x80))
        assertTrue(pids.contains(0x0C) || pids.contains(12))
        assertTrue(pids.contains(0x0D) || pids.contains(13))
        assertTrue(pids.size >= 45)
    }

    companion object {
        /** PIDs in Obd.kt PID_DECODERS that appear in the scan and should decode. */
        private val DECODED_PIDS = setOf(
            0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1F,
            0x21, 0x2C, 0x2D, 0x2F, 0x30, 0x31, 0x3C, 0x3E,
            0x42, 0x45, 0x46, 0x49, 0x4A, 0x4C, 0x4D, 0x4E,
            0x5A, 0x5D, 0x5E, 0x61, 0x62, 0x63, 0x8E,
        )
    }
}
