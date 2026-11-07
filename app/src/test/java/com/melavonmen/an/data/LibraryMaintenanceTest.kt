package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMaintenanceTest {
    @Test fun onlySameNameAndExactSizeAreGrouped() {
        fun comic(id: String, title: String, bytes: Long) = ComicEntry(id, id, title, emptyList(), emptyList(), "ZIP", false, emptyList(), null, bytes)
        val groups = LibraryMaintenance.duplicateGroups(listOf(
            comic("a", "Book", 100), comic("b", " book ", 100), comic("c", "Book", 101), comic("d", "Other", 100)
        ))
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups.single().entries.map { it.id }.toSet())
    }
}
