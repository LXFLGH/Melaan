package com.melavonmen.an.data

/**
 * UI-facing progress for a SAF library scan. Discovery and indexing are separate,
 * because enumerating many nested folders may take much longer than opening books.
 */
enum class LibraryScanPhase { IDLE, DISCOVERING, INDEXING, COMPLETED, FAILED }

data class LibraryScanProgress(
    val phase: LibraryScanPhase = LibraryScanPhase.IDLE,
    val discoveredCount: Int = 0,
    val processedCount: Int = 0,
    val importedCount: Int = 0,
    val currentFile: String? = null,
    val report: ScanReport? = null,
    val error: String? = null,
    val completedAtMillis: Long = 0L,
) {
    val isRunning: Boolean
        get() = phase == LibraryScanPhase.DISCOVERING || phase == LibraryScanPhase.INDEXING

    val processingFraction: Float
        get() = if (discoveredCount <= 0) 0f
        else (processedCount.toFloat() / discoveredCount).coerceIn(0f, 1f)
}
