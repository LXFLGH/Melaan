package com.melavonmen.an.data

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 真机兼容性回归：真实库的两个坑——
 * 1) metadata.json 带 UTF-8 BOM（抓取脚本常见）导致整段解析失败
 * 2) Windows 7-Zip 打包时条目名用反斜杠，章节/封面识别失效
 */
class ArchiveCompatTest {

    private fun build7z(entries: List<Pair<String, ByteArray>>): File {
        val f = File.createTempFile("compat", ".7z").apply { deleteOnExit() }
        SevenZOutputFile(f).use { out ->
            for ((name, bytes) in entries) {
                val e = SevenZArchiveEntry().apply {
                    this.name = name
                    size = bytes.size.toLong()
                }
                out.putArchiveEntry(e)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return f
    }

    @Test
    fun archiveExtensionsClassifyCbzAndRar() {
        assertEquals(ArchiveKind.ZIP, archiveKind("comic.CBZ"))
        assertEquals(ArchiveKind.RAR, archiveKind("comic.cbr"))
        assertEquals(ArchiveKind.RAR, archiveKind("comic.rar"))
        assertEquals(ArchiveKind.SEVEN_Z, archiveKind("comic.7z"))
        assertEquals(null, archiveKind("comic.pdf"))
    }

    @Test
    fun parsesMetadataWithUtf8Bom() {
        val json = "\uFEFF{\"id\":\"1\",\"title\":\"BOM作品\",\"authors\":[\"甲\"],\"tags\":[\"科幻\",\"悬疑\"],\"page_count\":3}"
        val meta = MetadataParser.parse(json)
        assertNotNull(meta)
        assertEquals("BOM作品", meta!!.title)
        assertEquals(listOf("科幻", "悬疑"), meta.tags)
    }

    @Test
    fun backslashEntryNamesStillResolveAsChapters() {
        val meta = "{\"id\":\"2\",\"title\":\"连载测试\",\"authors\":[],\"tags\":[],\"page_count\":2}".toByteArray()
        val f = build7z(
            listOf(
                "metadata.json" to meta,
                "01\\001.webp" to byteArrayOf(1),
                "01\\002.webp" to byteArrayOf(2),
                "02\\001.webp" to byteArrayOf(3),
            )
        )
        FileChannel.open(f.toPath(), StandardOpenOption.READ).use { ch ->
            ArchiveReader.open(ch).use { reader ->
                assertEquals("连载测试", reader.parseMetadata()?.title)
                assertTrue(reader.isSerial())
                val chapters = reader.resolveChapters()
                assertEquals(listOf("01", "02"), chapters.map { it.name })
                assertEquals(2, chapters[0].pageEntryPaths.size)
                assertEquals(1, chapters[1].pageEntryPaths.size)
            }
        }
    }
}
