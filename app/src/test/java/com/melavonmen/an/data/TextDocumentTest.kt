package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDocumentTest {

    @Test
    fun decodesUtf8WithoutBomAndGb18030Fallback() {
        val utf8 = "中文正文".toByteArray(Charsets.UTF_8)
        assertEquals("中文正文", TextDocument.decode(utf8))
        val gb = "中文正文".toByteArray(charset("GB18030"))
        assertEquals("中文正文", TextDocument.decode(gb))
    }

    @Test
    fun splitsCommonChineseChapterHeadings() {
        val text = """
            开头段落

            第一章 起点
            第一章内容

            第二章 转折
            第二章内容

            尾声
            尾声内容
        """.trimIndent()
        val chapters = TextDocument.splitChapters(text)
        assertEquals(listOf("前言", "第一章 起点", "第二章 转折", "尾声"), chapters.map { it.title })
        assertEquals("开头段落", chapters.first().content)
        assertEquals("第二章内容", chapters[2].content)
    }

    @Test
    fun unheadedTextBecomesOneChapter() {
        val text = "没有章节标题\n直接继续\n结尾"
        val chapters = TextDocument.splitChapters(text)
        assertEquals(1, chapters.size)
        assertEquals("正文", chapters.single().title)
        assertEquals(text, chapters.single().content)
    }
}
