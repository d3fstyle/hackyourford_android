package com.forddiag.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DtcCatalogTest {
    @Before
    fun setUp() {
        DtcCatalog.loadFromJson(
            """
            {
              "P0101": {
                "name": "MAF Circuit Range/Performance",
                "description": "Mass or Volume Air Flow Circuit Range/Performance Problem"
              }
            }
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        DtcCatalog.clear()
    }

    @Test
    fun decodeStatusBits() {
        val flags = decodeDtcStatus(0x2F)
        assertTrue(flags.testFailed)
        assertTrue(flags.testFailedThisMonitoringCycle)
        assertTrue(flags.pendingDtc)
        assertTrue(flags.confirmedDtc)
        assertFalse(flags.testNotCompletedSinceLastClear)
        assertTrue(flags.testFailedSinceLastClear)
        assertFalse(flags.testNotCompletedThisMonitoringCycle)
        assertFalse(flags.warningIndicatorRequested)
    }

    @Test
    fun formatStatusListsActiveFlags() {
        assertEquals(
            "testFailed, testFailedThisMonitoringCycle, pending, confirmed, testFailedSinceLastClear",
            formatDtcStatus(0x2F),
        )
    }

    @Test
    fun formatStatusEmptyIsNone() {
        assertEquals("none", formatDtcStatus(0x00))
    }

    @Test
    fun formatDisplayKnownCode() {
        val line = DtcCatalog.formatDisplay(Dtc(code = "P0101", status = 0x08, raw = 0x010101))
        assertEquals(
            "MAF Circuit Range/Performance (P0101): confirmed — " +
                "Mass or Volume Air Flow Circuit Range/Performance Problem",
            line,
        )
    }

    @Test
    fun formatDisplayUnknownCodeOmitsDescription() {
        assertNull(DtcCatalog.lookup("P1A23"))
        val line = DtcCatalog.formatDisplay(Dtc(code = "P1A23", status = 0x06, raw = 0x1A23))
        assertEquals(
            "Unknown (P1A23): testFailedThisMonitoringCycle, pending",
            line,
        )
    }

    @Test
    fun lookupIsCaseInsensitive() {
        assertEquals("MAF Circuit Range/Performance", DtcCatalog.lookup("p0101")?.name)
    }
}
