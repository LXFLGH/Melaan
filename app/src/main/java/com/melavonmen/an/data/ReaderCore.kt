package com.melavonmen.an.data

/**
 * Shared persistence boundary for comic and document readers.
 * The renderers own format-specific positioning; both delegate the durable
 * reading progress + history transaction here to avoid drifting semantics.
 */
object ReaderCore {
    fun restore(bookId: String): ReadingProgress? = ProgressStore.load(bookId)

    fun persist(
        bookId: String,
        chapterIndex: Int,
        position: Int,
        bookPercent: Int,
        collections: LibraryCollectionsStore,
    ): ReadingProgress {
        val progress = ReadingProgress(
            bookId, chapterIndex.coerceAtLeast(0), position.coerceAtLeast(0), bookPercent.coerceIn(0, 100)
        )
        ProgressStore.save(progress)
        collections.recordProgress(progress)
        return progress
    }
}
