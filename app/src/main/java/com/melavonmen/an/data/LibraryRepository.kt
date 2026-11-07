package com.melavonmen.an.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

data class ComicEntry(val id:String,val uri:String,val title:String,val authors:List<String>,val tags:List<String>,val format:String,val isSerial:Boolean,val chapters:List<ChapterRef>,val coverEntryName:String?,val sizeBytes:Long)

data class ScanWarning(
    val displayName: String,
    val missingMetadata: Boolean = false,
    val missingCover: Boolean = false,
    val skippedReason: String? = null
)

data class ScanReport(val archiveCount: Int, val importedCount: Int, val warnings: List<ScanWarning>)

/**
 * In-memory index over the user's 7z/ZIP comic library (SAF tree).
 *
 * - [scan] recursively indexes 7z/ZIP archives from one or more SAF trees without extracting anything.
 * - Page bytes are read on demand straight into memory; only cover images are cached on disk.
 * - A SQLite index restores titles, tags and chapter entry paths without reopening every archive.
 * - Open archives are kept in a tiny LRU so sequential page turns stay fast.
 */
object LibraryRepository {

    const val PREF_KEY_TREE_URIS = "tree_uris"
    const val PREF_KEY_CHAPTER_PARSER_VERSION = "chapter_parser_version"
    const val CHAPTER_PARSER_VERSION = 3

    private const val MAX_OPEN_READERS = 4

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var indexStore: LibraryIndexStore? = null

    @Volatile
    private var userTags: ComicUserTagStore? = null

    @Volatile
    private var entries: List<ComicEntry> = emptyList()

    private val idIndex = HashMap<String, ComicEntry>()
    private val readers = LinkedHashMap<String, ArchiveReader>(8, 0.75f, true)
    private var activeNestedReader: Pair<String, ArchiveReader>? = null
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        ArchiveReader.init(context)
        val store = LibraryIndexStore(context)
        val tagStore = ComicUserTagStore(context)
        BookmarkStore.init(context)
        val restored = runCatching { store.load() }.getOrDefault(emptyList())
        synchronized(lock) {
            indexStore = store
            userTags = tagStore
            activeNestedReader = null
            idIndex.clear()
            restored.forEach { idIndex[it.id] = it }
            entries = restored
        }
        LibrarySnapshot.invalidate()
    }

    suspend fun scan(treeUri: Uri): ScanReport = scan(listOf(treeUri))

    suspend fun scan(treeUris: List<Uri>, preserveCoverCache: Boolean = false): ScanReport {
        val ctx = appContext
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before scan")
        val previousEntries = entries
        val uniqueUris = LinkedHashMap<String, Uri>()
        val warnings = ArrayList<ScanWarning>()
        val failedTrees = ArrayList<Uri>()
        for (treeUri in treeUris.distinctBy(Uri::toString)) {
            try {
                for (uri in LibraryScanner.findArchiveUris(ctx.contentResolver, treeUri)) {
                    val key = runCatching {
                        "${uri.authority}:${android.provider.DocumentsContract.getDocumentId(uri)}"
                    }.getOrDefault(uri.toString())
                    uniqueUris.putIfAbsent(key, uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedTrees += treeUri
                warnings += ScanWarning(
                    displayName(treeUri),
                    skippedReason = "文件夹无访问权限或无法扫描"
                )
            }
        }
        val uris = uniqueUris.values.toList()
        val built = ArrayList<ComicEntry>(uris.size)
        for (uri in uris) {
            val name = displayName(uri)
            try {
                val result = buildEntry(uri, name)
                if (result.entry == null) {
                    warnings += result.warning ?: ScanWarning(name, skippedReason = "没有可读取的漫画页面")
                } else {
                    built.add(result.entry)
                    result.warning?.let(warnings::add)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += ScanWarning(name, skippedReason = e.message ?: "归档损坏或无法读取")
            }
        }
        val builtIds = built.mapTo(HashSet()) { it.id }
        previousEntries.forEach { entry ->
            val belongsToFailedTree = failedTrees.any { treeUri ->
                runCatching {
                    val archiveUri = Uri.parse(entry.uri)
                    archiveUri.authority == treeUri.authority &&
                        DocumentsContract.getTreeDocumentId(archiveUri) == DocumentsContract.getTreeDocumentId(treeUri)
                }.getOrDefault(false)
            }
            if (belongsToFailedTree && builtIds.add(entry.id)) built += entry
        }
        synchronized(lock) {
            indexStore?.replace(built)
            for (r in readers.values) closeQuietly(r)
            readers.clear()
            activeNestedReader = null
            idIndex.clear()
            for (e in built) idIndex[e.id] = e
            entries = built
        }
        if (preserveCoverCache) {
            val refreshed = built.associateBy { it.id }
            previousEntries.forEach { old ->
                val current = refreshed[old.id]
                if (current == null || current.coverEntryName != old.coverEntryName || current.sizeBytes != old.sizeBytes) {
                    coverFile(old.id)?.delete()
                }
            }
        } else clearCoverCache()
        LibrarySnapshot.invalidate()
        return ScanReport(archiveCount = uris.size, importedCount = built.size, warnings = warnings)
    }

    fun comics(): List<ComicEntry> = entries.map(::withUserTags)

    fun byId(id: String): ComicEntry? = synchronized(lock) { idIndex[id]?.let(::withUserTags) }

    suspend fun removeComic(id: String, deleteLocalFile: Boolean): Boolean {
        val context = appContext ?: return false
        val removed = synchronized(lock) {
            val entry = idIndex[id] ?: return false
            readers.remove(id)?.let { reader -> synchronized(reader) { closeQuietly(reader) } }
            if (activeNestedReader?.first == id) activeNestedReader = null
            if (deleteLocalFile && !runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(entry.uri))
                }.getOrDefault(false)
            ) return false
            val remaining = entries.filterNot { it.id == id }
            indexStore?.replace(remaining)
            idIndex.remove(id)
            entries = remaining
            true
        }
        if (removed) {
            userTags?.remove(id)
            ProgressStore.remove(id)
            BookmarkStore.removeComic(id)
            appContext?.let { LibraryCollectionsStore(it).removeComic(id) }
            coverFile(id)?.delete()
        }
        return removed
    }

    fun addUserTag(id: String, tag: String): List<String> {
        val entry = synchronized(lock) { idIndex[id] } ?: throw EntryNotFound(id)
        val cleaned = tag.trim()
        require(cleaned.isNotEmpty()) { "Tag cannot be blank" }
        if (entry.tags.any { it.trim().equals(cleaned, ignoreCase = true) }) {
            return withUserTags(entry).tags
        }
        val added = userTags?.add(id, cleaned) ?: throw IllegalStateException("LibraryRepository.init(context) must be called before addUserTag")
        return mergeTags(entry.tags, added).also { LibrarySnapshot.invalidate() }
    }

    fun removeUserTag(id: String, tag: String): List<String> {
        val entry = synchronized(lock) { idIndex[id] } ?: throw EntryNotFound(id)
        val cleaned = tag.trim()
        require(cleaned.isNotEmpty()) { "Tag cannot be blank" }
        val remaining = userTags?.removeTag(id, cleaned)
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before removeUserTag")
        return mergeTags(entry.tags, remaining).also { LibrarySnapshot.invalidate() }
    }

    suspend fun readCover(id: String): ByteArray? {
        coverFile(id)?.takeIf(File::isFile)?.let { cached ->
            runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        }
        val entry = byId(id) ?: return null
        val cover = entry.coverEntryName ?: return null
        val bytes = withReader(id) { it.readEntryBytes(cover) }
        runCatching {
            coverFile(id)?.let { file ->
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
        return bytes
    }

    suspend fun readAuthors(id: String): List<String> =
        withReader(id) { it.parseMetadata()?.authors.orEmpty() }

    fun cacheSizeBytes(): Long = runCatching {
        val coverBytes = coverDirectory()?.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
        coverBytes + (appContext?.getDatabasePath("library_index.db")?.length() ?: 0L)
    }.getOrDefault(0L)

    private fun coverDirectory(): File? = appContext?.let { File(it.filesDir, "parsed-covers") }

    private fun coverFile(id: String): File? {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) } + ".img"
        return coverDirectory()?.let { File(it, name) }
    }

    private fun clearCoverCache() {
        coverDirectory()?.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    suspend fun readPage(id: String, chapterIndex: Int, pageIndex: Int): ByteArray {
        val entry = byId(id) ?: throw EntryNotFound(id)
        val chapter = entry.chapters.getOrNull(chapterIndex)
            ?: throw IndexOutOfBoundsException("chapterIndex $chapterIndex out of range")
        val path = chapter.pageEntryPaths.getOrNull(pageIndex)
            ?: throw IndexOutOfBoundsException("pageIndex $pageIndex out of range")
        val nestedPath = chapter.nestedArchiveEntryPath
        if (nestedPath == null) return withReader(id) { it.readEntryBytes(path) }
        releaseActiveNested(id)
        val reader = obtainReader(id)
        val bytes = synchronized(reader) { reader.readNestedPage(nestedPath, path) }
        synchronized(lock) { activeNestedReader = id to reader }
        return bytes
    }

    /** Resolves only the selected nested chapter and updates the in-memory index. */
    fun resolveChapterForReading(id: String, chapterIndex: Int): ComicEntry {
        val entry = synchronized(lock) { idIndex[id] } ?: throw EntryNotFound(id)
        val chapter = entry.chapters.getOrNull(chapterIndex)
            ?: throw IndexOutOfBoundsException("chapterIndex $chapterIndex out of range")
        val nestedPath = chapter.nestedArchiveEntryPath
        val reader = obtainReader(id)
        val pagePaths = if (nestedPath == null) {
            releaseActiveNested()
            synchronized(reader) { reader.releaseNestedChapter() }
            chapter.pageEntryPaths
        } else {
            releaseActiveNested(id)
            synchronized(reader) { reader.resolveNestedChapter(nestedPath) }
                .also { synchronized(lock) { activeNestedReader = id to reader } }
        }
        val updated = entry.copy(chapters = entry.chapters.mapIndexed { index, current ->
            if (index == chapterIndex) current.copy(pageEntryPaths = pagePaths) else current
        })
        synchronized(lock) {
            if (idIndex.containsKey(id)) {
                idIndex[id] = updated
                entries = entries.map { if (it.id == id) updated else it }
            }
        }
        return withUserTags(updated)
    }

    fun releaseNestedChapter(id: String) {
        val active = synchronized(lock) {
            activeNestedReader?.takeIf { it.first == id }?.also { activeNestedReader = null }
        }
        active?.second?.let { reader -> synchronized(reader) { reader.releaseNestedChapter() } }
    }

    private fun releaseActiveNested(exceptComicId: String? = null) {
        val active = synchronized(lock) {
            activeNestedReader?.takeIf { it.first != exceptComicId }?.also { activeNestedReader = null }
        }
        active?.second?.let { reader -> synchronized(reader) { reader.releaseNestedChapter() } }
    }

    private data class BuildResult(val entry: ComicEntry?, val warning: ScanWarning?)

    private fun buildEntry(uri: Uri, displayName: String): BuildResult {
        val reader = ArchiveReader.open(uri)
        try {
            val meta = reader.parseMetadata()
            val chapters = reader.resolveChapters()
            val missingMetadata = meta == null
            val missingCover = reader.coverEntryName() == null
            val warning = if (missingMetadata || missingCover) {
                ScanWarning(displayName, missingMetadata = missingMetadata, missingCover = missingCover)
            } else null
            if (chapters.isEmpty()) {
                return BuildResult(null, warning?.copy(skippedReason = "没有可读取的漫画页面")
                    ?: ScanWarning(displayName, skippedReason = "没有可读取的漫画页面"))
            }
            val extension = displayName.substringAfterLast('.', "").lowercase()
            return BuildResult(ComicEntry(
                id = uri.toString(),
                uri = uri.toString(),
                title = meta?.title?.takeIf { it.isNotBlank() }
                    ?: displayName.substringBeforeLast('.', displayName).ifBlank { displayName },
                authors = meta?.authors ?: emptyList(),
                tags = meta?.tags ?: emptyList(),
                format = when (extension) { "cbz" -> "cbz"; "zip" -> "zip"; "rar" -> "rar"; "cbr" -> "cbr"; else -> "7z" },
                isSerial = reader.isSerial(),
                chapters = chapters,
                coverEntryName = reader.coverEntryName(),
                sizeBytes = reader.sizeBytes()
            ), warning)
        } finally {
            closeQuietly(reader)
        }
    }

    private fun displayName(uri: Uri): String {
        val ctx = appContext ?: return uri.lastPathSegment?.substringAfterLast(':').orEmpty()
        val queried = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf(String::isNotBlank)
            ?: Uri.decode(uri.lastPathSegment.orEmpty().substringAfterLast(':')).ifBlank { "未知归档" }
    }

    private fun withUserTags(entry: ComicEntry): ComicEntry {
        val localTags = userTags?.get(entry.id).orEmpty()
        val combined = mergeTags(entry.tags, localTags)
        return if (combined == entry.tags) entry else entry.copy(tags = combined)
    }

    private fun mergeTags(base: List<String>, additions: List<String>): List<String> {
        val seen = HashSet<String>()
        return (base + additions).filter { it.isNotBlank() && seen.add(it.trim().lowercase(Locale.ROOT)) }
    }

    private inline fun <T> withReader(id: String, block: (ArchiveReader) -> T): T {
        val reader = obtainReader(id)
        synchronized(reader) {
            return block(reader)
        }
    }

    private fun obtainReader(id: String): ArchiveReader {
        synchronized(lock) {
            readers[id]?.let { return it }
            val entry = idIndex[id] ?: throw EntryNotFound(id)
            val reader = ArchiveReader.open(Uri.parse(entry.uri))
            readers[id] = reader
            while (readers.size > MAX_OPEN_READERS) {
                val eldestKey = readers.entries.first().key
                val eldest = readers.remove(eldestKey) ?: break
                if (activeNestedReader?.second === eldest) activeNestedReader = null
                synchronized(eldest) {
                    closeQuietly(eldest)
                }
            }
            return reader
        }
    }

    private fun closeQuietly(reader: ArchiveReader) {
        try {
            reader.close()
        } catch (_: Exception) {
        }
    }
}
