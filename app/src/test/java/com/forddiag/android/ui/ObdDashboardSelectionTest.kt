package com.forddiag.android.ui

import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ObdDashboardSignalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDashboardSelectionTest {
    private fun row(
        source: LiveSource,
        id: Int,
        moduleId: String? = null,
        selected: Boolean = false,
    ) = ObdSignalRow(
        key = ObdSignalId(source, id, moduleId),
        label = "L",
        unit = null,
        selected = selected,
    )

    @Test
    fun replaceClearsPreviousSelection() {
        val rows = listOf(
            row(LiveSource.PID, 0x0C, selected = true),
            row(LiveSource.PID, 0x0D, selected = true),
            row(LiveSource.PID, 0x05, selected = false),
        )
        val keys = setOf(ObdSignalId(LiveSource.PID, 0x05, null))
        val result = applyDashboardSelection(rows, keys)
        assertEquals(0, result.skipped)
        assertFalse(result.rows[0].selected)
        assertFalse(result.rows[1].selected)
        assertTrue(result.rows[2].selected)
    }

    @Test
    fun missingKeysCountedAsSkipped() {
        val rows = listOf(row(LiveSource.PID, 0x0C))
        val keys = setOf(
            ObdSignalId(LiveSource.PID, 0x0C, null),
            ObdSignalId(LiveSource.PID, 0x0D, null),
            ObdSignalId(LiveSource.DID, 0xF190, "PCM"),
        )
        val result = applyDashboardSelection(rows, keys)
        assertEquals(2, result.skipped)
        assertTrue(result.rows.single().selected)
    }

    @Test
    fun keyMappingRoundTrip() {
        val pid = ObdSignalId(LiveSource.PID, 0x0C, null)
        assertEquals(ObdDashboardSignalKey("PID", 0x0C, null), pid.toDashboardKey())
        assertEquals(pid, ObdDashboardSignalKey("PID", 0x0C, null).toObdSignalId())

        val did = ObdSignalId(LiveSource.DID, 0xF190, "PCM")
        assertEquals(did, did.toDashboardKey().toObdSignalId())
        assertNull(ObdDashboardSignalKey("NOPE", 1, null).toObdSignalId())
    }
}
