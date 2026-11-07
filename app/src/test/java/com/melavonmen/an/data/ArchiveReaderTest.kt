package com.melavonmen.an.data

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class ArchiveReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildArchive(file: File, entries: List<Pair<String, ByteArray>>) {
        val out = SevenZOutputFile(file)
        try {
            for ((name, bytes) in entries) {
                val entry = SevenZArchiveEntry()
                entry.name = name
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        } finally {
            out.close()
        }
    }

    private fun openArchive(file: File): ArchiveReader =
        ArchiveReader.open(FileChannel.open(file.toPath(), StandardOpenOption.READ))

    private fun bytesOf(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(content)
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    // ---------- acceptance: single volume -> 1 chapter, pages 001..N natural order ----------

    @Test
    fun singleVolume_resolvesOneChapterInNaturalOrder() {
        val file = tmp.newFile("single.7z")
        // deliberately scrambled insert order; names without zero padding make
        // lexicographic order (1,10,2,...) differ from natural order (1,2,...,10)
        buildArchive(
            file,
            listOf(
                "metadata.json" to bytesOf("""{"id":"42","title":"Solo","page_count":10}"""),
                "cover.webp" to bytesOf("cover"),
                "10.webp" to bytesOf("page-10"),
                "9.webp" to bytesOf("page-9"),
                "8.webp" to bytesOf("page-8"),
                "7.webp" to bytesOf("page-7"),
                "6.webp" to bytesOf("page-6"),
                "5.webp" to bytesOf("page-5"),
                "4.webp" to bytesOf("page-4"),
                "3.webp" to bytesOf("page-3"),
                "2.webp" to bytesOf("page-2"),
                "1.webp" to bytesOf("page-1")
            )
        )

        openArchive(file).use { reader ->
            val chapters = reader.resolveChapters()
            assertEquals(1, chapters.size)
            assertEquals(
                (1..10).map { "$it.webp" },
                chapters[0].pageEntryPaths
            )
            assertArrayEquals(bytesOf("page-10"), reader.readEntryBytes("10.webp"))
            assertEquals("cover.webp", reader.coverEntryName())
            assertEquals(false, reader.isSerial())

            val meta = reader.parseMetadata()
            assertEquals("42", meta?.id)
            assertEquals("Solo", meta?.title)
            assertEquals(10, meta?.pageCount)
        }
    }

    @Test
    fun cbzZipArchiveReadsPages() {
        val file = tmp.newFile("comic.cbz")
        ZipArchiveOutputStream(file).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("1.webp"))
            out.write(bytesOf("page"))
            out.closeArchiveEntry()
        }
        openArchive(file).use { reader ->
            assertEquals(listOf("1.webp"), reader.resolveChapters().single().pageEntryPaths)
            assertArrayEquals(bytesOf("page"), reader.readEntryBytes("1.webp"))
        }
    }

    @Test
    fun nestedChapterZipsAreLazyAndReadPagesFromMemory() {
        val file = tmp.newFile("nested.zip")
        val chapter2 = zipBytes(listOf("10.jpg" to bytesOf("p10"), "2.jpg" to bytesOf("p2"), "cover.jpg" to bytesOf("cover")))
        val chapter10 = zipBytes(listOf("1.webp" to bytesOf("last chapter")))
        ZipArchiveOutputStream(file).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("cover.jpg"))
            out.write(bytesOf("outer cover"))
            out.closeArchiveEntry()
            out.putArchiveEntry(ZipArchiveEntry("第10话.zip"))
            out.write(chapter10)
            out.closeArchiveEntry()
            out.putArchiveEntry(ZipArchiveEntry("第2话.zip"))
            out.write(chapter2)
            out.closeArchiveEntry()
        }

        openArchive(file).use { reader ->
            val chapters = reader.resolveChapters()
            assertEquals(listOf("第2话", "第10话"), chapters.map { it.name })
            assertTrue(chapters.all { it.pageEntryPaths.isEmpty() && it.nestedArchiveEntryPath != null })
            assertTrue(!reader.hasNestedChapterOpen())

            val pages = reader.resolveNestedChapter("第2话.zip")
            assertEquals(listOf("2.jpg", "10.jpg"), pages)
            assertArrayEquals(bytesOf("p10"), reader.readNestedPage("第2话.zip", "10.jpg"))
            assertTrue(reader.hasNestedChapterOpen())
            reader.releaseNestedChapter()
            assertTrue(!reader.hasNestedChapterOpen())
        }
    }

    @Test
    fun nestedChapterSizeLimitIsExplicit() {
        validateNestedArchiveSize(MAX_NESTED_ARCHIVE_BYTES)
        assertThrows(ArchiveFormatException::class.java) {
            validateNestedArchiveSize(MAX_NESTED_ARCHIVE_BYTES + 1)
        }
    }

    @Test
    fun corruptedNestedZipFailsOnlyWhenChapterIsOpened() {
        val file = tmp.newFile("corrupted-nested.zip")
        ZipArchiveOutputStream(file).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("chapter1.zip"))
            out.write(bytesOf("not a zip"))
            out.closeArchiveEntry()
        }
        openArchive(file).use { reader ->
            assertEquals("chapter1.zip", reader.resolveChapters().single().nestedArchiveEntryPath)
            assertThrows(ArchiveFormatException::class.java) { reader.resolveNestedChapter("chapter1.zip") }
        }
    }

    @Test
    fun avifIsAReadablePageExtensionButGifIsNotListedWithoutAnimationSupport() {
        val file = tmp.newFile("formats.7z")
        buildArchive(file, listOf("1.avif" to bytesOf("avif"), "2.gif" to bytesOf("gif")))
        openArchive(file).use { reader ->
            assertEquals(listOf("1.avif"), reader.resolveChapters().single().pageEntryPaths)
        }
    }

    @Test
    fun singleVolume_zeroPaddedPagesKeepNaturalOrder() {
        val file = tmp.newFile("padded.7z")
        buildArchive(
            file,
            listOf(
                "003.webp" to bytesOf("3"),
                "001.webp" to bytesOf("1"),
                "010.webp" to bytesOf("10"),
                "002.webp" to bytesOf("2")
            )
        )
        openArchive(file).use { reader ->
            val chapters = reader.resolveChapters()
            assertEquals(1, chapters.size)
            assertEquals(listOf("001.webp", "002.webp", "003.webp", "010.webp"), chapters[0].pageEntryPaths)
        }
    }

    // ---------- acceptance: serial -> chapters = folder count, natural ascending ----------

    @Test
    fun serial_chaptersAndPagesInNaturalOrder() {
        val file = tmp.newFile("serial.7z")
        buildArchive(
            file,
            listOf(
                "metadata.json" to bytesOf("""{"id":"7","title":"Serial"}"""),
                "第10话/1.webp" to bytesOf("c10p1"),
                "第2话/2.webp" to bytesOf("c2p2"),
                "第2话/1.webp" to bytesOf("c2p1"),
                "第2话/10.webp" to bytesOf("c2p10"),
                "第1话/1.webp" to bytesOf("c1p1")
            )
        )

        openArchive(file).use { reader ->
            val chapters = reader.resolveChapters()
            assertEquals(listOf("第1话", "第2话", "第10话"), chapters.map { it.name })
            assertEquals(
                listOf("第2话/1.webp", "第2话/2.webp", "第2话/10.webp"),
                chapters[1].pageEntryPaths
            )
            assertEquals(listOf("第1话/1.webp"), chapters[0].pageEntryPaths)
            assertEquals(listOf("第10话/1.webp"), chapters[2].pageEntryPaths)
            assertEquals(true, reader.isSerial())

            assertArrayEquals(bytesOf("c2p10"), reader.readEntryBytes("第2话/10.webp"))
            val meta = reader.parseMetadata()
            assertEquals("Serial", meta?.title)
        }
    }

    // ---------- errors + read-only guarantees ----------

    @Test
    fun readEntryBytes_missingEntryThrowsEntryNotFound() {
        val file = tmp.newFile("missing.7z")
        buildArchive(file, listOf("1.webp" to bytesOf("x")))
        openArchive(file).use { reader ->
            assertThrows(EntryNotFound::class.java) {
                reader.readEntryBytes("404.webp")
            }
        }
    }

    @Test
    fun open_nonArchiveThrowsArchiveFormatException() {
        val file = tmp.newFile("garbage.7z")
        file.writeBytes("this is not a 7z archive".toByteArray())
        assertThrows(ArchiveFormatException::class.java) {
            openArchive(file)
        }
    }

    @Test
    fun readEntryBytes_producesNoNewFilesOnDisk() {
        val file = tmp.newFile("nodisk.7z")
        buildArchive(
            file,
            listOf(
                "metadata.json" to bytesOf("{}"),
                "1.webp" to bytesOf("one"),
                "2.webp" to bytesOf("two")
            )
        )
        val dir = file.parentFile!!
        val before = dir.walkTopDown().map { it.relativeTo(dir).path }.toSet()

        openArchive(file).use { reader ->
            reader.readEntryBytes("1.webp")
            reader.readEntryBytes("2.webp")
            reader.readEntryBytes("metadata.json")
            reader.parseMetadata()
            reader.resolveChapters()
        }

        val after = dir.walkTopDown().map { it.relativeTo(dir).path }.toSet()
        assertEquals(before, after)
        assertTrue(dir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun parseMetadata_absentReturnsNull() {
        val file = tmp.newFile("nometa.7z")
        buildArchive(file, listOf("1.webp" to bytesOf("x")))
        openArchive(file).use { reader ->
            assertNull(reader.parseMetadata())
        }
    }

    @Test
    fun listEntries_listsFileEntriesOnly() {
        val file = tmp.newFile("list.7z")
        buildArchive(
            file,
            listOf(
                "第1话/1.webp" to bytesOf("a"),
                "1.webp" to bytesOf("b")
            )
        )
        openArchive(file).use { reader ->
            assertEquals(setOf("第1话/1.webp", "1.webp"), reader.listEntries().toSet())
        }
    }
}
