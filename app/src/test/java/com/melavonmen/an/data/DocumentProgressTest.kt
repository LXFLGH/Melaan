package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentProgressTest {
    @Test
    fun firstCompletedChapterDoesNotCompleteBook() {
        assertEquals(1, DocumentProgress.bookPercent(0, 100, 10_000))
        assertEquals(0, DocumentProgress.bookPercent(0, 100, 0))
        assertEquals(50, DocumentProgress.bookPercent(1, 4, 10_000))
        assertEquals(100, DocumentProgress.bookPercent(3, 4, 10_000))
    }

    @Test
    fun singleChapterAndOutOfRangeValuesAreClamped() {
        assertEquals(50, DocumentProgress.bookPercent(0, 1, 5_000))
        assertEquals(0, DocumentProgress.bookPercent(-4, 0, -10))
        assertEquals(100, DocumentProgress.bookPercent(999, 2, 999999))
    }

    @Test
    fun pdfPositionRepresentsWholeDocument() {
        assertEquals(1, DocumentProgress.pdfPercent(0, 100))
        assertEquals(50, DocumentProgress.pdfPercent(49, 100))
        assertEquals(100, DocumentProgress.pdfPercent(99, 100))
        assertEquals(100, DocumentProgress.pdfPercent(0, 1))
    }
}
