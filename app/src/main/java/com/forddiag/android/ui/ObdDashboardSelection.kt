package com.forddiag.android.ui

import com.forddiag.android.core.LiveSource
import com.forddiag.android.core.ObdDashboardSignalKey

data class ObdDashboardApplyResult(
    val rows: List<ObdSignalRow>,
    val skipped: Int,
)

fun ObdSignalId.toDashboardKey(): ObdDashboardSignalKey =
    ObdDashboardSignalKey(source = source.name, id = id, moduleId = moduleId)

fun ObdDashboardSignalKey.toObdSignalId(): ObdSignalId? {
    val src = runCatching { LiveSource.valueOf(source) }.getOrNull() ?: return null
    return ObdSignalId(source = src, id = id, moduleId = moduleId)
}

fun applyDashboardSelection(
    rows: List<ObdSignalRow>,
    keys: Set<ObdSignalId>,
): ObdDashboardApplyResult {
    val present = rows.map { it.key }.toSet()
    val skipped = keys.count { it !in present }
    val next = rows.map { row -> row.copy(selected = row.key in keys) }
    return ObdDashboardApplyResult(rows = next, skipped = skipped)
}
