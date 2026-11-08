package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TextDocumentIndexTest {
    @Test
    fun preservesCharacterOffsetsForChineseHeadingsAndCrLf() {
        val source = "前言\r\n\r\n第一章 起点\r\n第一章正文\r\n\r\n第二章 转折\r\n第二章正文"
        val bytes = source.toByteArray(charset("GB18030"))
        val chapters = TextDocumentIndex.index(ByteArrayInputStream(bytes))
        assertEquals(3, chapters.size)
        assertEquals("前言", chapters[0].title)
        assertEquals("第一章 起点", chapters[1].title)
        assertEquals("第二章 转折", chapters[2].title)
        assertEquals("第一章正文", TextDocumentIndex.read(ByteArrayInputStream(bytes), chapters[1]))
        assertEquals("第二章正文", TextDocumentIndex.read(ByteArrayInputStream(bytes), chapters[2]))
    }

    @Test
    fun largeUnheadedTextIsSplitIntoBoundedChapters() {
        val text = "甲".repeat(TextDocumentIndex.MAX_CHAPTER_CHARS * 2 + 500)
        val bytes = text.toByteArray()
        val parts = TextDocumentIndex.index(ByteArrayInputStream(bytes))
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.end - it.start <= TextDocumentIndex.MAX_CHAPTER_CHARS })
        assertEquals(text, parts.joinToString("") { TextDocumentIndex.read(ByteArrayInputStream(bytes), it) })
    }

    @Test
    fun rejectsInvalidSpanEncoding() {
        assertEquals(null, TextDocumentIndex.parse("txt:-1:2"))
        assertEquals(null, TextDocumentIndex.parse("txt:100:5"))
        assertEquals(TextDocumentIndex.Span("", 0L, 42L), TextDocumentIndex.parse("txt:0:42"))
    }
}
