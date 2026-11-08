package com.melavonmen.an.data

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded-memory TXT scanning: persist logical character offsets in the existing chapter paths. */
internal object TextDocumentIndex {
    const val MAX_CHAPTER_CHARS = 120_000
    data class Span(val title: String, val start: Long, val end: Long) {
        fun path(): String = "txt:$start:$end"
    }

    fun parse(path: String): Span? {
        val parts = path.split(':')
        if (parts.size != 3 || parts[0] != "txt") return null
        val start = parts[1].toLongOrNull() ?: return null
        val end = parts[2].toLongOrNull() ?: return null
        return if (start >= 0 && end >= start && end - start <= MAX_CHAPTER_CHARS + 4096) {
            Span("", start, end)
        } else null
    }

    /** Reader creation and BOM removal must be identical for indexing and chapter reads. */
    private fun openReader(source: InputStream): BufferedReader {
        val input = BufferedInputStream(source, 65536)
        input.mark(65536)
        val probe = ByteArray(16384)
        val amount = input.read(probe).coerceAtLeast(0)
        input.reset()
        val bomLength: Int
        val encoding = when {
            amount >= 3 && probe[0] == 0xEF.toByte() &&
                probe[1] == 0xBB.toByte() && probe[2] == 0xBF.toByte() -> {
                bomLength = 3
                StandardCharsets.UTF_8
            }
            amount >= 2 && probe[0] == 0xFF.toByte() && probe[1] == 0xFE.toByte() -> {
                bomLength = 2
                StandardCharsets.UTF_16LE
            }
            amount >= 2 && probe[0] == 0xFE.toByte() && probe[1] == 0xFF.toByte() -> {
                bomLength = 2
                StandardCharsets.UTF_16BE
            }
            else -> {
                bomLength = 0
                try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(probe, 0, amount))
                    StandardCharsets.UTF_8
                } catch (_: CharacterCodingException) {
                    charset("GB18030")
                }
            }
        }
        repeat(bomLength) { input.read() }
        return BufferedReader(InputStreamReader(input, encoding), 16 * 1024)
    }

    /** Scans character offsets, never materializing the complete book or a giant unbroken line. */
    fun index(source: InputStream): List<Span> = openReader(source).use { reader ->
        val spans = ArrayList<Span>()
        var currentStart = 0L
        var currentTitle = "正文"
        var position = 0L
        var lineStart = 0L
        var part = 1
        var seenText = false
        val line = StringBuilder()

        fun finish(at: Long) {
            if (at > currentStart) spans += Span(currentTitle, currentStart, at)
        }
        fun flushLine(endExclusive: Long, nextStart: Long) {
            val normalized = line.toString().trim()
            if (TextDocument.isChapterHeading(normalized)) {
                val hadIntro = currentStart == 0L && spans.isEmpty() && seenText
                if (hadIntro) currentTitle = "前言"
                finish(lineStart)
                currentTitle = normalized
                currentStart = nextStart
            } else if (normalized.isNotBlank()) {
                seenText = true
            }
            line.clear()
            lineStart = nextStart
        }

        val buffer = CharArray(8192)
        while (true) {
            val size = reader.read(buffer)
            if (size < 0) break
            for (i in 0 until size) {
                val c = buffer[i]
                if (c == '\n') {
                    flushLine(position, position + 1)
                } else if (line.length < 4096) {
                    line.append(c)
                }
                position++
                // Split even heading-free novels, including single-line huge files.
                if (position - currentStart >= MAX_CHAPTER_CHARS && c != '\n') {
                    finish(position)
                    part++
                    currentStart = position
                    currentTitle = "正文 · 片段 $part"
                    line.clear()
                    lineStart = position
                }
            }
        }
        if (line.isNotEmpty()) flushLine(position, position)
        finish(position)
        if (spans.isEmpty() && position > 0) spans += Span("正文", 0, position)
        spans
    }

    fun read(source: InputStream, span: Span): String = openReader(source).use { reader ->
        require(span.start >= 0 && span.end >= span.start) { "Invalid TXT chapter" }
        var skip = span.start
        while (skip > 0) {
            val amount = reader.skip(skip)
            if (amount <= 0) {
                if (reader.read() < 0) error("TXT file was truncated")
                skip--
            } else skip -= amount
        }
        var remaining = span.end - span.start
        val output = StringBuilder(remaining.coerceAtMost(MAX_CHAPTER_CHARS.toLong()).toInt())
        val buffer = CharArray(8192)
        while (remaining > 0) {
            val count = reader.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) error("TXT file was truncated")
            output.append(buffer, 0, count)
            remaining -= count
        }
        output.toString().trim()
    }
}
