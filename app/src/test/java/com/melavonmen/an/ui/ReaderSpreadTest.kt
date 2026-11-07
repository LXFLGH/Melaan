package com.melavonmen.an.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSpreadTest {
    @Test fun restoredScrollPageClampsSavedPageToCurrentChapter() {
        assertEquals(0, restoredScrollPage(9, 0))
        assertEquals(0, restoredScrollPage(-2, 8))
        assertEquals(7, restoredScrollPage(99, 8))
        assertEquals(4, restoredScrollPage(4, 8))
    }

    @Test fun autoNextScrollRequiresUserAtEndAndAnotherChapter() {
        assertEquals(false, shouldAutoAdvanceOnScroll(9, 10, false, true, true))
        assertEquals(false, shouldAutoAdvanceOnScroll(8, 10, true, true, true))
        assertEquals(false, shouldAutoAdvanceOnScroll(9, 10, true, false, true))
        assertEquals(false, shouldAutoAdvanceOnScroll(9, 10, true, true, false))
        assertEquals(true, shouldAutoAdvanceOnScroll(9, 10, true, true, true))
    }

    @Test
    fun firstComicPageIsSoloAndOtherPagesPairInOrder() {
        assertEquals(0, spreadStart(0, 0))
        assertEquals(1, spreadStart(0, 1))
        assertEquals(1, spreadStart(0, 2))
        assertEquals(3, spreadStart(0, 3))
        assertEquals(0, spreadStart(1, 0))
        assertEquals(0, spreadStart(1, 1))
        assertEquals(2, spreadStart(1, 2))
    }
}
