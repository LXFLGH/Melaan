package com.melavonmen.an.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One consistent, process-local view of the data shared by the four main tabs. */
data class LibrarySnapshotData(
    val comics: List<ComicEntry>,
    val progressById: Map<String, ReadingProgress?>,
    val latest: ReadingProgress?,
)

/**
 * Coalesces library/progress reads across tab entries. Every mutation entry point invalidates this
 * snapshot; no persistent format or repository API is changed.
 */
object LibrarySnapshot {
    private val loadMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var cached: LibrarySnapshotData? = null
    private var generation = 0L

    fun peek(): LibrarySnapshotData? = if (ExhibitionData.enabled) ExhibitionData.snapshot else cached

    fun invalidate() {
        synchronized(stateLock) {
            generation++
            cached = null
        }
    }

    suspend fun load(): LibrarySnapshotData {
        if (ExhibitionData.enabled) return ExhibitionData.snapshot
        cached?.let { return it }
        return loadMutex.withLock {
            val existing = cached
            if (existing != null) {
                existing
            } else {
                lateinit var result: LibrarySnapshotData
                while (true) {
                    val startedAt = synchronized(stateLock) { generation }
                    val loaded = withContext(Dispatchers.IO) {
                        val entries = LibraryRepository.comics()
                        LibrarySnapshotData(
                            comics = entries,
                            progressById = entries.associate { it.id to ProgressStore.load(it.id) },
                            latest = ProgressStore.latest(),
                        )
                    }
                    val installed = synchronized(stateLock) {
                        if (startedAt == generation) {
                            cached = loaded
                            true
                        } else {
                            false
                        }
                    }
                    if (installed) {
                        result = loaded
                        break
                    }
                }
                result
            }
        }
    }
}
