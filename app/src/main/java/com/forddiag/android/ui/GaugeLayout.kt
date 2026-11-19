package com.forddiag.android.ui

/** Default char budget / row weight for unknown or empty units. */
const val DEFAULT_GAUGE_CHAR_BUDGET = 3

/**
 * Stable expected width (character budget) for a gauge value based on its unit.
 * Used as [androidx.compose.foundation.layout.RowScope.weight] so long-range
 * signals (rpm, km) get more horizontal space than short ones (%, °C).
 */
fun expectedGaugeCharBudget(unit: String?): Int {
    val u = unit?.trim()?.lowercase().orEmpty()
    if (u.isEmpty()) return DEFAULT_GAUGE_CHAR_BUDGET
    return when (u) {
        "rpm", "r/min" -> 4
        "km", "min" -> 5
        "v", "volt", "volts" -> 4
        "km/h", "mph", "kpa", "psi", "nm", "g/s", "l/h", "s" -> 4
        "%", "°c", "c", "°f", "f", "°" -> 3
        else -> DEFAULT_GAUGE_CHAR_BUDGET
    }
}
