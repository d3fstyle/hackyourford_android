package com.forddiag.android.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FordCatalogTest {
    private val sampleProfile = VehicleProfile(
        id = "focus_2017_eu",
        name = "Focus",
        years = "2017",
        modules = listOf(
            ModuleDef("PCM", "PCM", 0x7E0, bus = "hs"),
            ModuleDef("ABS", "ABS", 0x760, bus = "hs"),
        ),
    )

    @Test
    fun parseMode22ModeAndPid() {
        val pid = FordExtendedPid(
            name = "Accel",
            shortName = "Accel",
            modeAndPid = "221340",
            equation = "A*0.3921",
            unit = "%",
            header = "Auto",
        )
        assertTrue(pid.isMode22)
        assertEquals(0x22, pid.mode)
        assertEquals(0x1340, pid.pidOrDid)
    }

    @Test
    fun evaluateAccelPedal() {
        // A=128 → ~50.19%
        val v = TorqueEquation.evaluate("A*0.3921", byteArrayOf(0x80.toByte()))
        assertNotNull(v)
        assertEquals(50.19, v!!, 0.02)
    }

    @Test
    fun evaluateEngineOilTemp() {
        // (((A*256)+B)/100)-40 with A=0x17 B=0x70 → 6000/100-40 = 20
        val v = TorqueEquation.evaluate(
            "(((A*256)+B)/100)-40",
            byteArrayOf(0x17, 0x70),
        )
        assertEquals(20.0, v!!, 1e-6)
    }

    @Test
    fun evaluateFuelLevel() {
        // ((A*256)+B)*(1/328) with A=0 B=164 → 0.5
        val v = TorqueEquation.evaluate(
            "((A*256)+B)*(1/328)",
            byteArrayOf(0x00, 0xA4.toByte()),
        )
        assertEquals(0.5, v!!, 0.01)
    }

    @Test
    fun evaluateMapPsi() {
        val v = TorqueEquation.evaluate(
            "(((A*256)+B)*0.03625)",
            byteArrayOf(0x01, 0x00),
        )
        assertEquals(256 * 0.03625, v!!, 1e-9)
    }

    @Test
    fun evaluateBoostNeedsBaro() {
        val data = byteArrayOf(0x01, 0x00)
        assertNull(TorqueEquation.evaluate("(((A*256)+B)*0.03625)-BARO()", data, baro = null))
        val v = TorqueEquation.evaluate("(((A*256)+B)*0.03625)-BARO()", data, baro = 14.5)
        assertEquals(256 * 0.03625 - 14.5, v!!, 1e-9)
    }

    @Test
    fun evaluateSignedByte() {
        val v = TorqueEquation.evaluate("signed(A)", byteArrayOf(0xFF.toByte()))
        assertEquals(-1.0, v!!, 1e-9)
    }

    @Test
    fun evaluateBitShift() {
        val v = TorqueEquation.evaluate("(A<8)+B", byteArrayOf(0x01, 0x02))
        assertEquals((1 shl 8) + 2.0, v!!, 1e-9)
    }

    @Test
    fun resolveHeaderAutoToPcm() {
        val m = resolveFordHeader("Auto", sampleProfile)
        assertEquals("PCM", m?.id)
    }

    @Test
    fun resolveHeaderAbs() {
        assertEquals("ABS", resolveFordHeader("ABS", sampleProfile)?.id)
    }

    @Test
    fun resolveHeaderTcmMissing() {
        assertNull(resolveFordHeader("TCM", sampleProfile))
    }

    @Test
    fun resolveHeaderJ1850Skipped() {
        assertNull(resolveFordHeader("C410F1", sampleProfile))
    }

    @Test
    fun resolveHeaderHexSynthetic() {
        val m = resolveFordHeader("726", sampleProfile)
        assertEquals("HDR_726", m?.id)
        assertEquals(0x726, m?.txId)
    }

    @Test
    fun resolveCatalogSkipsTcmAndJ1850() {
        val catalog = listOf(
            FordExtendedPid("A", "A", "221340", "A", unit = "%", header = "Auto"),
            FordExtendedPid("T", "T", "0105", "A-40", unit = "°C", header = "TCM"),
            FordExtendedPid("J", "J", "2211BD", "A", unit = "°F", header = "C410F1"),
            FordExtendedPid("W", "W", "223987", "A", unit = "km/h", header = "ABS"),
            FordExtendedPid("P", "P", "222813", "A", unit = "psi", header = "726"),
        )
        val resolved = resolveFordExtendedPids(catalog, sampleProfile)
        val ids = resolved.map { it.pid.shortName }.toSet()
        assertTrue(ids.contains("A"))
        assertTrue(ids.contains("W"))
        assertTrue(ids.contains("P"))
        assertFalse(ids.contains("T"))
        assertFalse(ids.contains("J"))
    }

    @Test
    fun assetJsonLoadsAndParses() {
        val root = File("src/main/assets/ford/extended_pids.json")
        assertTrue("missing ${root.absolutePath}", root.isFile)
        val list = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<FordExtendedPid>>(root.readText())
        assertTrue(list.size >= 55)
        assertTrue(list.any { it.modeAndPid.equals("221340", true) })
        assertTrue(list.any { it.header.equals("ABS", true) })
    }

    @Test
    fun resolvePollModuleSynthetic() {
        val m = ScannerSession.resolvePollModule(sampleProfile, "HDR_726")
        assertEquals(0x726, m?.txId)
        assertEquals("PCM", ScannerSession.resolvePollModule(sampleProfile, "PCM")?.id)
        assertNull(ScannerSession.resolvePollModule(sampleProfile, "NOPE"))
    }
}
