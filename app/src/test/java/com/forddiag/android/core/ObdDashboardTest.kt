package com.forddiag.android.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ObdDashboardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun roundTripJson() {
        val dash = ObdDashboard(
            id = "engine",
            name = "Engine",
            signals = listOf(
                ObdDashboardSignalKey("PID", 0x0C, null),
                ObdDashboardSignalKey("DID", 0xF190, "PCM"),
            ),
            updatedAtMs = 1000L,
        )
        val text = json.encodeToString(ObdDashboard.serializer(), dash)
        val back = json.decodeFromString(ObdDashboard.serializer(), text)
        assertEquals(dash, back)
    }

    @Test
    fun rejectsInvalidId() {
        assertFalse(ObdDashboardIds.isValidId(""))
        assertFalse(ObdDashboardIds.isValidId("bad id"))
        assertTrue(ObdDashboardIds.isValidId("engine_01"))
    }

    @Test
    fun saveListDeleteGlobalAndProfile() {
        val store = ObdDashboardStore(tmp.root)
        val dash = ObdDashboard(
            id = "engine",
            name = "Engine",
            signals = listOf(ObdDashboardSignalKey("PID", 0x0C, null)),
            updatedAtMs = 1L,
        )
        store.save(ObdDashboardScope.GLOBAL, profileId = null, dash)
        assertEquals(listOf(dash), store.listGlobal())
        assertTrue(store.listForProfile("focus_2017_eu").isEmpty())

        store.save(
            ObdDashboardScope.PROFILE,
            profileId = "focus_2017_eu",
            dash.copy(id = "fuel", name = "Fuel"),
        )
        assertEquals(1, store.listForProfile("focus_2017_eu").size)

        assertTrue(store.delete(ObdDashboardScope.GLOBAL, null, "engine"))
        assertTrue(store.listGlobal().isEmpty())
    }

    @Test
    fun copyGlobalToProfileCreatesIndependentFile() {
        val store = ObdDashboardStore(tmp.root)
        val dash = ObdDashboard(
            id = "engine",
            name = "Engine",
            signals = listOf(ObdDashboardSignalKey("PID", 0x0D, null)),
            updatedAtMs = 1L,
        )
        store.save(ObdDashboardScope.GLOBAL, null, dash)
        val copy = store.copyGlobalToProfile("engine", "focus_2017_eu")
        assertEquals("Engine", copy.name)
        assertEquals(dash.signals, copy.signals)
        assertTrue(ObdDashboardIds.isValidId(copy.id))
        store.save(
            ObdDashboardScope.PROFILE,
            "focus_2017_eu",
            copy.copy(signals = emptyList(), updatedAtMs = 2L),
        )
        assertEquals(1, store.load(ObdDashboardScope.GLOBAL, null, "engine").signals.size)
    }

    @Test
    fun saveRequiresProfileIdForProfileScope() {
        val store = ObdDashboardStore(tmp.root)
        val dash = ObdDashboard("x", "X", emptyList(), 1L)
        assertThrows(IllegalArgumentException::class.java) {
            store.save(ObdDashboardScope.PROFILE, profileId = null, dash)
        }
    }

    @Test
    fun corruptFileSkippedInList() {
        val store = ObdDashboardStore(tmp.root)
        val globalDir = File(tmp.root, "global").also { it.mkdirs() }
        File(globalDir, "broken.json").writeText("{not json")
        store.save(
            ObdDashboardScope.GLOBAL,
            null,
            ObdDashboard("ok", "OK", listOf(ObdDashboardSignalKey("PID", 1, null)), 1L),
        )
        assertEquals(1, store.listGlobal().size)
    }
}
