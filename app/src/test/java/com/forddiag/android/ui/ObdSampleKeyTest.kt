package com.forddiag.android.ui

import com.forddiag.android.core.LiveSample
import com.forddiag.android.core.LiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdSampleKeyTest {
    @Test
    fun pidSampleWithCosmeticModuleIdMapsToCatalogKey() {
        val sample = LiveSample(
            tsMs = 1L,
            source = LiveSource.PID,
            id = 0x0C,
            rawHex = "410C1AF8",
            decoded = "1750",
            moduleId = "PCM",
            label = "Engine RPM",
            value = 1750.0,
            unit = "rpm",
        )
        val key = obdSampleKey(sample)
        assertEquals(LiveSource.PID, key.source)
        assertEquals(0x0C, key.id)
        assertNull(key.moduleId)
        assertEquals(ObdSignalId(LiveSource.PID, 0x0C, null), key)
    }

    @Test
    fun pidSampleWithNullModuleIdMapsToCatalogKey() {
        val sample = LiveSample(
            tsMs = 1L,
            source = LiveSource.PID,
            id = 0x0D,
            rawHex = "410D32",
            decoded = "50",
            moduleId = null,
            label = "Vehicle Speed",
            value = 50.0,
            unit = "km/h",
        )
        assertEquals(ObdSignalId(LiveSource.PID, 0x0D, null), obdSampleKey(sample))
    }

    @Test
    fun didSampleKeepsModuleId() {
        val sample = LiveSample(
            tsMs = 1L,
            source = LiveSource.DID,
            id = 0xF190,
            rawHex = "62F1904142",
            decoded = "AB",
            moduleId = "PCM",
            label = "VIN",
            value = null,
            unit = null,
        )
        assertEquals(ObdSignalId(LiveSource.DID, 0xF190, "PCM"), obdSampleKey(sample))
    }
}
