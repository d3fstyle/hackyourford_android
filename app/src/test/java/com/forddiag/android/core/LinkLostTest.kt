package com.forddiag.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LinkLostTest {
    @Test
    fun ioExceptionIsLinkLost() {
        assertTrue(isLinkLost(IOException("broken pipe")))
    }

    @Test
    fun timeoutIsLinkLost() {
        assertTrue(isLinkLost(FordException.Timeout()))
    }

    @Test
    fun brokenPipeMessageOnWrappedExceptionIsLinkLost() {
        assertTrue(isLinkLost(RuntimeException("Write failed: Broken pipe")))
    }

    @Test
    fun socketClosedInCauseChainIsLinkLost() {
        assertTrue(isLinkLost(Exception("op failed", IOException("Socket closed"))))
    }

    @Test
    fun adapterErrorIsNotLinkLost() {
        assertFalse(isLinkLost(FordException.Adapter("NO DATA")))
    }

    @Test
    fun illegalStateIsNotLinkLost() {
        assertFalse(isLinkLost(IllegalStateException("Not connected")))
    }
}
