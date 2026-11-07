package com.melavonmen.an.data

/** Conservative candidates only; matching never removes a source document. */
data class DuplicateGroup(val entries: List<ComicEntry>)

object LibraryMaintenance {
    fun duplicateGroups(entries: List<ComicEntry>): List<DuplicateGroup> = entries
        .groupBy { it.title.trim().lowercase() to it.sizeBytes }
        .values.filter { it.size > 1 }
        .map(::DuplicateGroup)
}
