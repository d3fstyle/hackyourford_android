package com.forddiag.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeLayoutTest {
    @Test
    fun budgetsByUnit() {
        assertEquals(4, expectedGaugeCharBudget("rpm"))
        assertEquals(4, expectedGaugeCharBudget("V"))
        assertEquals(3, expectedGaugeCharBudget("%"))
        assertEquals(3, expectedGaugeCharBudget("°C"))
        assertEquals(5, expectedGaugeCharBudget("km"))
        assertEquals(3, expectedGaugeCharBudget(null))
        assertEquals(3, expectedGaugeCharBudget(""))
        assertEquals(3, expectedGaugeCharBudget("   "))
    }

    @Test
    fun aliasesAndCasing() {
        assertEquals(4, expectedGaugeCharBudget("RPM"))
        assertEquals(4, expectedGaugeCharBudget(" r/min "))
        assertEquals(4, expectedGaugeCharBudget("volts"))
        assertEquals(4, expectedGaugeCharBudget("volt"))
        assertEquals(3, expectedGaugeCharBudget("c"))
        assertEquals(3, expectedGaugeCharBudget("°F"))
    }

    @Test
    fun rpmBudgetAtLeastPercent() {
        assertTrue(expectedGaugeCharBudget("rpm") >= expectedGaugeCharBudget("%"))
    }

    @Test
    fun commonObdUnits() {
        assertEquals(4, expectedGaugeCharBudget("km/h"))
        assertEquals(4, expectedGaugeCharBudget("kPa"))
        assertEquals(4, expectedGaugeCharBudget("Nm"))
        assertEquals(4, expectedGaugeCharBudget("g/s"))
        assertEquals(4, expectedGaugeCharBudget("L/h"))
        assertEquals(4, expectedGaugeCharBudget("s"))
        assertEquals(5, expectedGaugeCharBudget("min"))
        assertEquals(3, expectedGaugeCharBudget("°"))
    }
}
