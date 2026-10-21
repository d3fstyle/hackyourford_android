package com.forddiag.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsBuiltParserTest {
    private fun fixture(): String =
        javaClass.classLoader!!
            .getResourceAsStream("sample_as_built.ab")!!
            .bufferedReader()
            .readText()

    @Test
    fun parseExtractsVinNodesAndBlocks() {
        val parsed = AsBuiltParser.parse(fixture())
        assertEquals("WF05XXGCC5HU30679", parsed.vin)
        assertTrue(parsed.nodeIds.contains(0x7E0))
        assertTrue(parsed.nodeIds.contains(0x726))
        assertTrue(parsed.nodeIds.contains(0x760))
        // PCM 1..3 → blocks on 7E0
        assertEquals(setOf(1, 2, 3), parsed.blocksByAddr[0x7E0])
        assertEquals(setOf(1, 2), parsed.blocksByAddr[0x726])
        assertEquals(setOf(1, 2), parsed.blocksByAddr[0x7D0])
        assertEquals(setOf(1), parsed.blocksByAddr[0x720])
    }

    @Test
    fun applyMergesAsBuiltDidsIntoSeedProfile() {
        val seed = VehicleProfile(
            id = "focus_test",
            name = "Focus Test",
            years = "2017",
            notes = "seed",
            modules = listOf(
                ModuleDef(
                    id = "BCM",
                    name = "Body Control Module",
                    txId = 0x726,
                    bus = "ms",
                    dids = listOf(
                        DidDef(0xF188, "Software number"),
                        DidDef(0xDE00, "As-Built DE00", asBuilt = true),
                    ),
                ),
            ),
        )
        val parsed = AsBuiltParser.parse(fixture())
        val updated = AsBuiltParser.applyAsBuilt(seed, parsed)

        assertTrue(updated.notes!!.contains("WF05XXGCC5HU30679"))
        val bcm = updated.module("BCM")!!
        assertTrue(bcm.dids.any { it.did == 0xF188 && !it.asBuilt })
        assertTrue(bcm.dids.any { it.did == 0xDE00 && it.asBuilt })
        assertTrue(bcm.dids.any { it.did == 0xDE01 && it.asBuilt })

        val pcm = updated.modules.find { it.txId == 0x7E0 }
        assertNotNull(pcm)
        assertEquals("PCM", pcm!!.id)
        assertEquals("hs", pcm.bus)
        assertEquals(setOf(0xDE00, 0xDE01, 0xDE02), pcm.dids.filter { it.asBuilt }.map { it.did }.toSet())

        val abs = updated.modules.find { it.txId == 0x760 }
        assertNotNull(abs)
        assertEquals("ABS", abs!!.id)
    }

    @Test
    fun blockMapsToDeDid() {
        // block 1 → DE00, block 18 → DE11
        val did = 0xDE00 + (18 - 1)
        assertEquals(0xDE11, did)
    }
}
