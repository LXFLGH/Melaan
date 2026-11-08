package com.melavonmen.an.data

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class TextChapter(
    val title: String,
    val content: String,
)

/** Decoding and lightweight chapter splitting for local plain-text novels. */
internal object TextDocument {

    private val CHAPTER_HEAD = Regex(
        """^\s*(?:第\s*[0-9零一二三四五六七八九十百千万]+\s*[章节卷回部集](?:[\s:：\-—·,.，、].{0,80})?|序章|序言|楔子|引子|后记|尾声|终章)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            // Fall through to common Chinese encodings.
        }
        return runCatching { String(bytes, charset("GB18030")) }
            .getOrElse { String(bytes, StandardCharsets.ISO_8859_1) }
    }

    fun isChapterHeading(line: String): Boolean = CHAPTER_HEAD.matches(line)

    fun splitChapters(text: String): List<TextChapter> {
        val lines = text.lineSequence().toList()
        val headings = lines.withIndex().filter { (_, line) -> CHAPTER_HEAD.matches(line) }
        if (headings.isEmpty()) {
            listOf(TextChapter("正文", text.trim())).let { return it }
        }
        val chapters = ArrayList<TextChapter>()
        if (headings.first().index > 0) {
            val intro = lines.subList(0, headings.first().index).joinToString("\n").trim()
            if (intro.isNotEmpty()) chapters += TextChapter("前言", intro)
        }
        headings.forEachIndexed { index, (lineIndex, line) ->
            val end = headings.getOrNull(index + 1)?.index ?: lines.size
            val content = lines.subList(lineIndex + 1, end).joinToString("\n").trim()
            chapters += TextChapter(line.trim(), content)
        }
        return chapters.ifEmpty { listOf(TextChapter("正文", text.trim())) }
    }
}
