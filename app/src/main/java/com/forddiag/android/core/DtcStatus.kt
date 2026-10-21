package com.forddiag.android.core

/** ISO 14229-1 DTCStatus byte (one byte per DTC). */
data class DtcStatusFlags(
    val testFailed: Boolean,
    val testFailedThisMonitoringCycle: Boolean,
    val pendingDtc: Boolean,
    val confirmedDtc: Boolean,
    val testNotCompletedSinceLastClear: Boolean,
    val testFailedSinceLastClear: Boolean,
    val testNotCompletedThisMonitoringCycle: Boolean,
    val warningIndicatorRequested: Boolean,
)

fun decodeDtcStatus(status: Int): DtcStatusFlags {
    val s = status and 0xFF
    return DtcStatusFlags(
        testFailed = s and 0x01 != 0,
        testFailedThisMonitoringCycle = s and 0x02 != 0,
        pendingDtc = s and 0x04 != 0,
        confirmedDtc = s and 0x08 != 0,
        testNotCompletedSinceLastClear = s and 0x10 != 0,
        testFailedSinceLastClear = s and 0x20 != 0,
        testNotCompletedThisMonitoringCycle = s and 0x40 != 0,
        warningIndicatorRequested = s and 0x80 != 0,
    )
}

fun formatDtcStatus(status: Int): String {
    val f = decodeDtcStatus(status)
    val parts = buildList {
        if (f.testFailed) add("testFailed")
        if (f.testFailedThisMonitoringCycle) add("testFailedThisMonitoringCycle")
        if (f.pendingDtc) add("pending")
        if (f.confirmedDtc) add("confirmed")
        if (f.testNotCompletedSinceLastClear) add("testNotCompletedSinceLastClear")
        if (f.testFailedSinceLastClear) add("testFailedSinceLastClear")
        if (f.testNotCompletedThisMonitoringCycle) add("testNotCompletedThisMonitoringCycle")
        if (f.warningIndicatorRequested) add("warningIndicatorRequested")
    }
    return if (parts.isEmpty()) "none" else parts.joinToString(", ")
}
