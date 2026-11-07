package com.melavonmen.an

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melavonmen.an.data.LibraryCollectionsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCollectionsInstrumentedTest {
    @Test
    fun bookListMembersPersistAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("library_collections", 0).edit().clear().commit()
        val store = LibraryCollectionsStore(context)
        store.createBookList("稍后阅读")
        store.setBookListMembership("稍后阅读", "comic://one", true)
        assertEquals(setOf("comic://one"), store.bookLists().single().comicIds)
        store.setBookListMembership("稍后阅读", "comic://one", false)
        assertEquals(emptySet<String>(), store.bookLists().single().comicIds)
    }
}
