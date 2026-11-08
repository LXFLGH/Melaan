package com.melavonmen.an.data

/**
 * Document ReadingProgress contract:
 * - PDF: chapterIndex = 0, pageIndex = zero-based PDF page, percent = whole-book progress.
 * - EPUB/TXT: chapterIndex = spine/text chapter, pageIndex = scroll position in
 *   [0, SCROLL_UNITS] within that chapter, percent = approximate whole-book progress.
 *
 * The existing four-field ReadingProgress JSON remains backward compatible.
 */
internal object DocumentProgress {
    const val SCROLL_UNITS = 10_000

    /** Chapter-weighted book progress; a completed first chapter must not complete the book. */
    fun bookPercent(chapterIndex: Int, chapterCount: Int, chapterScrollUnits: Int): Int {
        val count = chapterCount.coerceAtLeast(1)
        val chapter = chapterIndex.coerceIn(0, count - 1)
        val scroll = chapterScrollUnits.coerceIn(0, SCROLL_UNITS)
        val numerator = (chapter.toLong() * SCROLL_UNITS + scroll) * 100L
        val denominator = count.toLong() * SCROLL_UNITS
        return (numerator / denominator).toInt().coerceIn(0, 100)
    }

    fun pdfPercent(pageIndex: Int, pageCount: Int): Int {
        val count = pageCount.coerceAtLeast(1)
        val page = pageIndex.coerceIn(0, count - 1)
        return (((page.toLong() + 1) * 100L) / count).toInt().coerceIn(0, 100)
    }
}
