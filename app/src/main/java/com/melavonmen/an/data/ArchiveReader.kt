package com.melavonmen.an.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.ContentResolver
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import com.github.junrar.Archive as RarArchive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Not a supported archive, or the archive is corrupt. */
class ArchiveFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The requested entry does not exist inside the archive. */
class EntryNotFound(val entryPath: String) : Exception("entry not found: $entryPath")

data class ChapterRef(val name:String,val pageEntryPaths:List<String>,val nestedArchiveEntryPath:String? = null)

/**
 * Read-only 7z/ZIP reader. Entries are decoded into memory on demand; archives
 * and comic pages are never extracted or cached on disk.
 */
class ArchiveReader private constructor(
    private val channel: SeekableByteChannel,
    private val pfd: ParcelFileDescriptor?,
    private val rarUri: Uri?,
    val name: String,
    private val format: Format,
) : Closeable {

    private data class EntryRef(
        val path: String,
        val sizeBytes: Long = -1,
        val sevenZ: SevenZArchiveEntry? = null,
        val zip: ZipArchiveEntry? = null,
        val rar: Boolean = false,
    )

    private data class Backend(
        val sevenZ: SevenZFile?,
        val zip: ZipFile?,
        val entries: List<EntryRef>,
    )

    private enum class Format { SEVEN_Z, ZIP, RAR }

    private val backend = openBackend(channel, format, name)
    private val fileEntries = backend.entries
    private val byName = fileEntries.associateBy { it.path }
    private var activeNestedChapter: NestedChapterArchive? = null

    private fun normalizeEntryName(raw: String): String = normalizeArchivePath(raw)

    /** All file entry paths inside the archive (directories excluded). */
    fun listEntries(): List<String> = fileEntries.map { it.path }

    /** Decompresses one entry fully into memory. No temporary files are created. */
    fun readEntryBytes(entryPath: String): ByteArray {
        val entry = byName[normalizeEntryName(entryPath)] ?: throw EntryNotFound(entryPath)
        synchronized(this) {
            try {
                return openEntryStream(entry).use { it.readBytes() }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("failed to read entry: $entryPath", e)
            }
        }
    }

    /** Parses metadata.json or the legacy meta.json alias; null when absent or invalid. */
    fun parseMetadata(): ComicMeta? {
        val candidates = fileEntries
            .filter { it.path.substringAfterLast('/').equals("metadata.json", true) || it.path.substringAfterLast('/').equals("meta.json", true) }
            .sortedWith(compareBy<EntryRef> { '/' in it.path }.thenBy { !it.path.substringAfterLast('/').equals("metadata.json", true) })
        for (entry in candidates) {
            val parsed = runCatching { MetadataParser.parse(readEntryBytes(entry.path)) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** Serial: group by the first directory level that branches after common wrapper paths. */
    fun resolveChapters(): List<ChapterRef> {
        val nestedArchives = fileEntries.filter { isNestedZipName(it.path) }
        if (nestedArchives.isNotEmpty()) {
            val nestedChapters = nestedArchives.map { entry ->
                val filename = entry.path.substringAfterLast('/')
                val baseName = filename.substringBeforeLast('.')
                val parent = entry.path.substringBeforeLast('/', "")
                ChapterRef(
                    name = (parent.takeIf(String::isNotBlank)?.let { "$it/$baseName" } ?: baseName),
                    pageEntryPaths = emptyList(),
                    nestedArchiveEntryPath = entry.path
                )
            }
            val directPages = fileEntries.filter { isImageName(it.path) && !isCoverName(it.path) }.map { it.path }
            return (nestedChapters + resolveImageChapters(directPages)).sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        }
        val pages = fileEntries.filter { isImageName(it.path) && !isCoverName(it.path) }.map { it.path }
        return resolveImageChapters(pages)
    }

    private fun resolveImageChapters(pages: List<String>): List<ChapterRef> {
        val rootPages = ArrayList<String>()
        val nestedPages = ArrayList<String>()
        for (path in pages) {
            if ('/' !in path) rootPages.add(path) else nestedPages.add(path)
        }

        if (nestedPages.isNotEmpty()) {
            val directories = nestedPages.map { it.substringBeforeLast('/') }
            val levels = directories.map { it.split('/') }
            val commonDepth = levels.first().indices.takeWhile { depth ->
                levels.all { it.getOrNull(depth) == levels.first()[depth] }
            }.count()
            val minDepth = levels.minOf { it.size }
            val chapterDepth = if (commonDepth < minDepth) commonDepth else (minDepth - 1).coerceAtLeast(0)
            val chapters = LinkedHashMap<String, ArrayList<String>>()
            nestedPages.forEachIndexed { index, path ->
                val name = levels[index].getOrNull(chapterDepth).orEmpty()
                chapters.getOrPut(name) { ArrayList() }.add(path)
            }
            return chapters.keys.sortedWith(NaturalOrder).map { chapter ->
                ChapterRef(chapter, chapters.getValue(chapter).sortedWith(NaturalOrder))
            }
        }
        return rootPages.takeIf { it.isNotEmpty() }
            ?.let { listOf(ChapterRef("单行本", it.sortedWith(NaturalOrder))) }
            .orEmpty()
    }

    /** True when image pages are grouped in chapter folders. */
    fun isSerial(): Boolean = fileEntries.any {
        isNestedZipName(it.path) || ('/' in it.path && isImageName(it.path) && !isCoverName(it.path))
    }

    /** Loads one child ZIP into memory for random page access; replaced on the next chapter. */
    fun resolveNestedChapter(entryPath: String): List<String> = synchronized(this) {
        val normalized = normalizeEntryName(entryPath)
        activeNestedChapter?.takeIf { it.entryPath == normalized }?.let { return@synchronized it.pagePaths }
        releaseNestedChapter()
        val entry = byName[normalized] ?: throw EntryNotFound(entryPath)
        if (!isNestedZipName(entry.path)) throw ArchiveFormatException("内层章节仅支持 ZIP/CBZ")
        validateNestedArchiveSize(entry.sizeBytes)
        val bytes = openEntryStream(entry).use { input ->
            val output = ByteArrayOutputStream(if (entry.sizeBytes in 1L..65536L) entry.sizeBytes.toInt() else 8192)
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_NESTED_ARCHIVE_BYTES) throw nestedTooLarge()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val nested = try {
            NestedChapterArchive(normalized, bytes)
        } catch (error: Exception) {
            throw ArchiveFormatException("内层章节 ZIP 损坏或不受支持", error)
        }
        if (nested.pagePaths.isEmpty()) {
            nested.close()
            throw ArchiveFormatException("内层章节 ZIP 不含可读书页")
        }
        activeNestedChapter = nested
        nested.pagePaths
    }

    fun readNestedPage(entryPath: String, pagePath: String): ByteArray = synchronized(this) {
        val normalized = normalizeEntryName(entryPath)
        if (activeNestedChapter?.entryPath != normalized) resolveNestedChapter(normalized)
        activeNestedChapter?.readPage(pagePath) ?: throw EntryNotFound(pagePath)
    }

    fun releaseNestedChapter() = synchronized(this) {
        activeNestedChapter?.close()
        activeNestedChapter = null
    }

    internal fun hasNestedChapterOpen(): Boolean = activeNestedChapter != null

    private fun openEntryStream(entry: EntryRef): InputStream =
        entry.sevenZ?.let { backend.sevenZ!!.getInputStream(it) }
            ?: if (entry.rar) readRarEntry(entry.path) else backend.zip!!.getInputStream(entry.zip!!)

    /** Named cover entry, preferring root-level and conventional cover names. */
    fun coverEntryName(): String? {
        val covers = fileEntries.map { it.path }
            .filter { isImageName(it) && isCoverName(it) }
        return covers.firstOrNull { '/' !in it } ?: covers.firstOrNull()
    }

    /** Archive size in bytes, or -1 when unknown. */
    fun sizeBytes(): Long = runCatching { channel.size() }.getOrDefault(-1L)

    override fun close() {
        releaseNestedChapter()
        runCatching { backend.sevenZ?.close() }
        runCatching { backend.zip?.close() }
        runCatching { channel.close() }
        runCatching { pfd?.close() }
    }

    private fun openBackend(channel: SeekableByteChannel, format: Format, name: String): Backend {
        var sevenZ: SevenZFile? = null
        var zip: ZipFile? = null
        return try {
            channel.position(0)
            when (format) {
            Format.SEVEN_Z -> {
                val archive = SevenZFile(channel).also { sevenZ = it }
                val entries = ArrayList<EntryRef>()
                for (entry in archive.entries) {
                    if (!entry.isDirectory) entries.add(EntryRef(normalizeEntryName(entry.name), entry.size, sevenZ = entry))
                }
                Backend(archive, null, entries)
            }
            Format.ZIP -> {
                val archive = ZipFile.builder().setSeekableByteChannel(channel).get().also { zip = it }
                val entries = ArrayList<EntryRef>()
                val iterator = archive.entries
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (!entry.isDirectory) entries.add(EntryRef(normalizeEntryName(entry.name), entry.size, zip = entry))
                }
                Backend(null, archive, entries)
            }
            Format.RAR -> {
                val uri = rarUri ?: throw IOException("missing RAR URI")
                val resolver = contentResolver ?: throw IOException("content resolver unavailable")
                val entries = resolver.openInputStream(uri)?.use { input ->
                    RarArchive(input).use { archive ->
                        if (archive.isPasswordProtected) throw ArchiveFormatException("加密 RAR 暂不支持")
                        archive.fileHeaders.mapNotNull { header ->
                            if (header.isSplitBefore || header.isSplitAfter) throw ArchiveFormatException("多卷 RAR 暂不支持")
                            if (header.isEncrypted) throw ArchiveFormatException("加密 RAR 暂不支持")
                            if (header.isDirectory) null else EntryRef(normalizeEntryName(header.fileName), header.fullUnpackSize, rar = true)
                        }
                    }
                } ?: throw IOException("cannot open RAR")
                Backend(null, null, entries)
            }
            }
        } catch (t: Throwable) {
            runCatching { sevenZ?.close() }
            runCatching { zip?.close() }
            runCatching { channel.close() }
            runCatching { pfd?.close() }
            if (t is ArchiveFormatException) throw t
            throw ArchiveFormatException("not a valid ${if (format == Format.ZIP) "ZIP" else if (format == Format.RAR) "RAR" else "7z"} archive: $name", t)
        }
    }

    private fun readRarEntry(path: String): java.io.InputStream {
        val resolver = contentResolver ?: throw IOException("content resolver unavailable")
        val uri = rarUri ?: throw IOException("missing RAR URI")
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot reopen RAR")
        try {
            val archive = RarArchive(input)
            if (archive.isPasswordProtected) throw ArchiveFormatException("加密 RAR 暂不支持")
            val header = archive.fileHeaders.firstOrNull { normalizeEntryName(it.fileName) == path }
                ?: throw EntryNotFound(path)
            if (header.isSplitBefore || header.isSplitAfter) throw ArchiveFormatException("多卷 RAR 暂不支持")
            // shortcut: rescans headers from SAF for each page; replace with seekable SAF backend if large RAR libraries make this slow.
            return archive.getInputStream(header).let { stream ->
                object : java.io.FilterInputStream(stream) {
                    override fun close() { try { super.close() } finally { archive.close() } }
                }
            }
        } catch (t: Throwable) {
            runCatching { input.close() }
            throw t
        }
    }

    companion object {

        @Volatile
        private var contentResolver: ContentResolver? = null

        fun init(context: Context) {
            contentResolver = context.applicationContext.contentResolver
        }

        /** Opens a content:// archive using its name or signature to choose 7z/ZIP. */
        fun open(uri: Uri): ArchiveReader = open(uri, null)

        /** Format hint is the filename/extension from the SAF scanner when the URI hides its name. */
        fun open(uri: Uri, formatHint: String?): ArchiveReader {
            val resolver = contentResolver
                ?: throw IllegalStateException("ArchiveReader.init(context) must be called before open(uri)")
            val pfd = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("cannot open archive: $uri", e)
            } ?: throw IOException("cannot open archive: $uri")
            try {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val name = formatHint?.takeIf(String::isNotBlank) ?: displayName(resolver, uri)
                val format = detectFormat(channel, name)
                return ArchiveReader(channel, pfd, if (format == Format.RAR) uri else null, name.ifBlank { uri.lastPathSegment ?: uri.toString() }, format)
            } catch (e: ArchiveFormatException) {
                runCatching { pfd.close() }
                throw e
            } catch (e: Throwable) {
                runCatching { pfd.close() }
                throw ArchiveFormatException("cannot read archive: $uri", e)
            }
        }

        /** Opens an already-open read-only channel; signature detection supports both formats. */
        fun open(channel: SeekableByteChannel): ArchiveReader {
            val format = detectFormat(channel, null)
            return ArchiveReader(channel, null, null, "", format)
        }

        private fun displayName(resolver: ContentResolver, uri: Uri): String = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")

        private fun detectFormat(channel: SeekableByteChannel, hint: String?): Format {
            val extension = hint?.substringAfterLast('.', hint)?.lowercase()
            if (extension == "zip" || extension == "cbz") return Format.ZIP
            if (extension == "7z") return Format.SEVEN_Z
            if (extension == "rar" || extension == "cbr") return Format.RAR

            val originalPosition = channel.position()
            val header = ByteBuffer.allocate(6)
            try {
                channel.position(0)
                while (header.hasRemaining()) {
                    val count = channel.read(header)
                    if (count <= 0) break
                }
            } finally {
                channel.position(originalPosition)
            }
            val bytes = header.array()
            val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                    (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) ||
                    (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))
            return if (isZip) Format.ZIP else Format.SEVEN_Z
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "bmp", "avif")
internal const val MAX_NESTED_ARCHIVE_BYTES = 64L * 1024 * 1024

private fun nestedTooLarge() = ArchiveFormatException("内层章节 ZIP 超过 64 MiB，暂不支持")
internal fun validateNestedArchiveSize(sizeBytes: Long) {
    if (sizeBytes > MAX_NESTED_ARCHIVE_BYTES) throw nestedTooLarge()
}

private fun isNestedZipName(path: String): Boolean = when (path.substringAfterLast('.', "").lowercase()) {
    "zip", "cbz" -> true
    else -> false
}

private fun normalizeArchivePath(raw: String): String =
    raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

private class NestedChapterArchive(val entryPath: String, bytes: ByteArray) : Closeable {
    private val channel = SeekableInMemoryByteChannel(bytes)
    private val zip = try {
        ZipFile.builder().setSeekableByteChannel(channel).get()
    } catch (error: Exception) {
        runCatching { channel.close() }
        throw error
    }
    private val entries: Map<String, ZipArchiveEntry> = try {
        val result = LinkedHashMap<String, ZipArchiveEntry>()
        val iterator = zip.entries
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            if (!entry.isDirectory) {
                if (entry.generalPurposeBit.usesEncryption()) throw ArchiveFormatException("加密内层 ZIP 暂不支持")
                result[normalizeArchivePath(entry.name)] = entry
            }
        }
        result
    } catch (error: Exception) {
        runCatching { zip.close() }
        runCatching { channel.close() }
        throw error
    }
    val pagePaths = entries.keys.filter { isImageName(it) && !isCoverName(it) }.sortedWith(NaturalOrder)

    fun readPage(path: String): ByteArray {
        val entry = entries[normalizeArchivePath(path)] ?: throw EntryNotFound(path)
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() {
        runCatching { zip.close() }
        runCatching { channel.close() }
    }
}

private fun isImageName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun isCoverName(name: String): Boolean {
    val base = name.substringAfterLast('/').substringBeforeLast('.').lowercase()
    return base == "cover" || base == "folder" || base == "front"
}

/** Natural (human) order: digit runs compare numerically ("2" < "10"). */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                var si = i
                while (si < ei - 1 && a[si] == '0') si++
                var sj = j
                while (sj < ej - 1 && b[sj] == '0') sj++
                val li = ei - si
                val lj = ej - sj
                if (li != lj) return li - lj
                for (k in 0 until li) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                val dr = (ei - i) - (ej - j)
                if (dr != 0) return dr
                i = ei
                j = ej
            } else {
                val la = Character.toLowerCase(ca)
                val lb = Character.toLowerCase(cb)
                if (la != lb) return la - lb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
