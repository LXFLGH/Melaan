package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryIndexCodecTest {
    @Test
    fun nestedChapterPathSurvivesIndexRoundTrip() {
        val chapters = listOf(
            ChapterRef("第1话", emptyList(), "第1话.zip"),
            ChapterRef("单行本", listOf("001.webp", "002.webp"))
        )

        assertEquals(chapters, LibraryIndexCodec.decodeChapters(LibraryIndexCodec.encodeChapters(chapters)))
    }

    @Test
    fun olderIndexRowsDecodeWithoutNestedPath() {
        val chapter = LibraryIndexCodec.decodeChapters("[{\"name\":\"单行本\",\"pages\":[\"1.webp\"]}]").single()

        assertEquals(listOf("1.webp"), chapter.pageEntryPaths)
        assertNull(chapter.nestedArchiveEntryPath)
    }
}
