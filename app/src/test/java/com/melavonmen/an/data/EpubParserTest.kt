package com.melavonmen.an.data

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class EpubParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildEpub(file: File) {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent().toByteArray()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>测试之书</dc:title>
                <dc:creator>测试作者</dc:creator>
                <dc:subject>科幻</dc:subject>
                <meta name="cover" content="cover-image"/>
              </metadata>
              <manifest>
                <item id="cover-image" href="cover.jpg" media-type="image/jpeg"/>
                <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent().toByteArray()
        ZipArchiveOutputStream(file).use { out ->
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/cover.jpg" to byteArrayOf(1),
                "OEBPS/text/ch1.xhtml" to "<html><body>one</body></html>".toByteArray(),
                "OEBPS/text/ch2.xhtml" to "<html><body>two</body></html>".toByteArray(),
            ).forEach { (name, bytes) ->
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    private fun open(file: File): ArchiveReader =
        ArchiveReader.open(FileChannel.open(file.toPath(), StandardOpenOption.READ))

    @Test
    fun parsesContainerOpfSpineMetadataAndCover() {
        val file = tmp.newFile("sample.epub")
        buildEpub(file)
        open(file).use { reader ->
            val info = EpubParser.parse(reader)
            assertNotNull(info)
            assertEquals("测试之书", info!!.title)
            assertEquals(listOf("测试作者"), info.authors)
            assertEquals(listOf("科幻"), info.tags)
            assertEquals(listOf("ch1", "ch2"), info.chapters.map { it.name })
            assertEquals(
                listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
                info.chapters.map { it.pageEntryPaths.single() }
            )
            assertEquals("OEBPS/cover.jpg", info.coverEntryName)
        }
    }

    @Test
    fun invalidContainerReturnsNull() {
        val file = tmp.newFile("broken.epub")
        ZipArchiveOutputStream(file).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("not-container.txt"))
            out.write(byteArrayOf(1))
            out.closeArchiveEntry()
        }
        open(file).use { reader ->
            assertNull(EpubParser.parse(reader))
        }
    }

    @Test
    fun percentEncodedManifestHrefsResolveToZipEntryNames() {
        val file = tmp.newFile("encoded.epub")
        val container = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles>
            </container>
        """.trimIndent().toByteArray()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Encoded</dc:title></metadata>
              <manifest><item id="c1" href="text/My%20Chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent().toByteArray()
        ZipArchiveOutputStream(file).use { out ->
            listOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/text/My Chapter.xhtml" to "<html><body>text</body></html>".toByteArray(),
            ).forEach { (name, bytes) ->
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        open(file).use { reader ->
            val parsed = EpubParser.parse(reader)
            assertNotNull(parsed)
            assertEquals("OEBPS/text/My Chapter.xhtml", parsed!!.chapters.single().pageEntryPaths.single())
            assertEquals("My Chapter", parsed.chapters.single().name)
            assertEquals("<html><body>text</body></html>", String(reader.readEntryBytes(parsed.chapters.single().pageEntryPaths.single())))
        }
    }

    @Test
    fun epub3NavProvidesHumanReadableChapterLabels() {
        val file = tmp.newFile("nav-toc.epub")
        val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""".toByteArray()
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>目录测试</dc:title></metadata>
              <manifest>
                <item id="toc" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="one" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="one"/><itemref idref="two"/></spine>
            </package>
        """.trimIndent().toByteArray()
        val nav = """
          <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body><nav epub:type="toc"><ol>
              <li><a href="text/ch1.xhtml">序幕</a></li>
              <li><a href="text/ch2.xhtml#start">第二章·旅行</a></li>
            </ol></nav></body>
          </html>
        """.trimIndent().toByteArray()
        ZipArchiveOutputStream(file).use { out ->
            listOf(
                "META-INF/container.xml" to container,
                "OPS/book.opf" to opf,
                "OPS/nav.xhtml" to nav,
                "OPS/text/ch1.xhtml" to "<html/>".toByteArray(),
                "OPS/text/ch2.xhtml" to "<html/>".toByteArray(),
            ).forEach { (name, bytes) ->
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        open(file).use { reader ->
            val info = EpubParser.parse(reader)
            assertNotNull(info)
            assertEquals(listOf("序幕", "第二章·旅行"), info!!.chapters.map { it.name })
        }
    }

    private fun String.toByteArray(): ByteArray = toByteArray(Charsets.UTF_8)
}
