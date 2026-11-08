package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanProgressTest {
    @Test
    fun discoveryAndIndexingAreBothRunningStates() {
        assertFalse(LibraryScanProgress().isRunning)
        assertTrue(LibraryScanProgress(phase = LibraryScanPhase.DISCOVERING, discoveredCount = 4).isRunning)
        assertTrue(LibraryScanProgress(phase = LibraryScanPhase.INDEXING, discoveredCount = 4).isRunning)
        assertFalse(LibraryScanProgress(phase = LibraryScanPhase.COMPLETED).isRunning)
        assertFalse(LibraryScanProgress(phase = LibraryScanPhase.FAILED).isRunning)
    }

    @Test
    fun progressFractionIsSafeBeforeDiscoveryFinishes() {
        assertEquals(0f, LibraryScanProgress().processingFraction, 0.001f)
        assertEquals(0.25f,
            LibraryScanProgress(discoveredCount = 20, processedCount = 5).processingFraction, 0.001f)
        assertEquals(1f,
            LibraryScanProgress(discoveredCount = 1, processedCount = 99).processingFraction, 0.001f)
    }
}
