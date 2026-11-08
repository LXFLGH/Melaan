package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryScannerTest {
    @Test
    fun libraryFileKindCoversComicsAndDocuments() {
        assertEquals(LibraryFileKind.ARCHIVE, libraryFileKind("book.7z"))
        assertEquals(LibraryFileKind.ARCHIVE, libraryFileKind("book.CBZ"))
        assertEquals(LibraryFileKind.EPUB, libraryFileKind("book.epub"))
        assertEquals(LibraryFileKind.PDF, libraryFileKind("book.PDF"))
        assertEquals(LibraryFileKind.TXT, libraryFileKind("book.txt"))
        assertNull(libraryFileKind("book.docx"))
        assertNull(libraryFileKind("cover"))
    }

    @Test
    fun archiveKindRemainsComicArchiveOnly() {
        assertNull(archiveKind("book.epub"))
        assertNull(archiveKind("book.pdf"))
        assertNull(archiveKind("book.txt"))
    }
}
