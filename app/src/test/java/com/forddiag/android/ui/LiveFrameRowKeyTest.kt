package com.forddiag.android.ui

import com.forddiag.android.core.CanFrame
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveFrameRowKeyTest {
    @Test
    fun identicalFramesAtDifferentIndexesHaveDistinctKeys() {
        val first = CanFrame(
            tsMs = 1_700_000_000_000L,
            id = 0x3E8,
            data = byteArrayOf(0x01, 0x02),
            rawLine = "3E8 01 02",
        )
        val second = CanFrame(
            tsMs = 1_700_000_000_000L,
            id = 0x3E8,
            data = byteArrayOf(0x01, 0x02),
            rawLine = "3E8 01 02",
        )
        assertNotEquals(liveFrameRowKey(0, first), liveFrameRowKey(1, second))
    }
}
