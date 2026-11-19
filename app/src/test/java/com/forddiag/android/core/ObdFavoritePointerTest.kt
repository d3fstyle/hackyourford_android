package com.forddiag.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdFavoritePointerTest {
    @Test
    fun globalAlwaysApplicable() {
        val fav = ObdFavoritePointer(ObdDashboardScope.GLOBAL, "engine", profileId = null)
        assertTrue(fav.isApplicable(null))
        assertTrue(fav.isApplicable("focus_2017_eu"))
    }

    @Test
    fun profileOnlyWhenIdsMatch() {
        val fav = ObdFavoritePointer(ObdDashboardScope.PROFILE, "fuel", profileId = "focus_2017_eu")
        assertTrue(fav.isApplicable("focus_2017_eu"))
        assertFalse(fav.isApplicable("other"))
        assertFalse(fav.isApplicable(null))
    }
}
