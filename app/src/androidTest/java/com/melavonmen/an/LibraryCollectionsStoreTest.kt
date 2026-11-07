package com.melavonmen.an

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melavonmen.an.data.LibraryCollectionsStore
import com.melavonmen.an.data.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryCollectionsStoreTest {
    @Test
    fun favoritesPersistAndHistoryKeepsLatestPositionPerComic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryCollectionsStore(context)
        val id = "test:${UUID.randomUUID()}"
        val listName = "test-${UUID.randomUUID()}"
        try {
            store.setFavorite(id, true)
            assertTrue(id in store.favorites())
            store.recordProgress(ReadingProgress(id, 0, 1, 10))
            store.recordProgress(ReadingProgress(id, 1, 2, 20))
            val latest = store.recent().filter { it.progress.comicId == id }
            assertEquals(1, latest.size)
            assertEquals(ReadingProgress(id, 1, 2, 20), latest.single().progress)
            assertTrue(latest.single().timestampMillis > 0)
            store.setFavorite(id, false)
            assertFalse(id in store.favorites())
            store.createBookList(listName)
            store.setBookListMembership(listName, id, true)
            assertTrue(LibraryCollectionsStore(context).bookLists().single { it.name == listName }.comicIds.contains(id))
            store.removeComic(id)
            assertTrue(store.bookLists().single { it.name == listName }.comicIds.isEmpty())
        } finally {
            store.removeComic(id)
            store.removeBookList(listName)
        }
    }
}
