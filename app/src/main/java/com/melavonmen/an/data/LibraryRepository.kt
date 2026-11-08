package com.melavonmen.an.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ComicEntry(val id:String,val uri:String,val title:String,val authors:List<String>,val tags:List<String>,val format:String,val isSerial:Boolean,val chapters:List<ChapterRef>,val coverEntryName:String?,val sizeBytes:Long)

data class ScanWarning(
    val displayName: String,
    val missingMetadata: Boolean = false,
    val missingCover: Boolean = false,
    val skippedReason: String? = null
)

data class ScanReport(val archiveCount: Int, val importedCount: Int, val warnings: List<ScanWarning>)

/**
 * In-memory index over the user's local 7z/ZIP/RAR comics and EPUB/PDF/TXT books (SAF tree).
 *
 * - [scan] recursively indexes supported files from one or more SAF trees without extracting anything.
 * - Page/document bytes are read on demand straight into memory; only cover images are cached on disk.
 * - A SQLite index restores titles, authors, tags and chapter entry paths without reopening every file.
 * - Open archives are kept in a tiny LRU so sequential page turns stay fast.
 */
object LibraryRepository {

    const val PREF_KEY_TREE_URIS = "tree_uris"
    const val PREF_KEY_CHAPTER_PARSER_VERSION = "chapter_parser_version"
    const val CHAPTER_PARSER_VERSION = 5

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
    private val scanRunLock = Mutex()
    private val scanStartLock = Any()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeScanJob: Job? = null
    private val mutableScanProgress = MutableStateFlow(LibraryScanProgress())
    val scanProgress: StateFlow<LibraryScanProgress> = mutableScanProgress

    /** Runs outside a screen's lifecycle, so tab changes cannot cancel a newly added library. */
    fun startScan(treeUris: List<Uri>, preserveCoverCache: Boolean = false) {
        val requestedUris = treeUris.toList()
        synchronized(scanStartLock) {
            activeScanJob?.cancel()
            mutableScanProgress.value = LibraryScanProgress(phase = LibraryScanPhase.DISCOVERING)
            activeScanJob = scanScope.launch {
                try {
                    scan(requestedUris, preserveCoverCache)
                } catch (cancelled: CoroutineCancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableScanProgress.value = mutableScanProgress.value.copy(
                        phase = LibraryScanPhase.FAILED,
                        error = error.message ?: "书库扫描失败",
                        completedAtMillis = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        ArchiveReader.init(context)
        val store = LibraryIndexStore(context)
        val tagStore = ComicUserTagStore(context)
        BookmarkStore.init(context)
        UserBookMetadataStore.init(context)
        LostBookStore.init(context)
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

    suspend fun scan(treeUris: List<Uri>, preserveCoverCache: Boolean = false): ScanReport = withContext(Dispatchers.IO) {
        scanRunLock.withLock {
        if (ExhibitionData.enabled) {
            val report = ScanReport(0, 0, listOf(ScanWarning("展览模式", skippedReason = "展览模式不会扫描个人书库")))
            mutableScanProgress.value = LibraryScanProgress(
                phase = LibraryScanPhase.COMPLETED,
                report = report,
                completedAtMillis = System.currentTimeMillis(),
            )
            return@withLock report
        }
        val currentJob = currentCoroutineContext()[Job]
        mutableScanProgress.value = LibraryScanProgress(phase = LibraryScanPhase.DISCOVERING)
        var discoveredCount = 0

        val ctx = appContext
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before scan")
        val previousEntries = entries
        val previousByUri = previousEntries.associateBy(ComicEntry::uri)
        val stamps = ctx.getSharedPreferences("melaan_scan_stamps", Context.MODE_PRIVATE)
        val updatedStamps = LinkedHashMap<String, String>()
        val uniqueUris = LinkedHashMap<String, Uri>()
        val discoveredIds = HashSet<String>()
        val warnings = ArrayList<ScanWarning>()
        val failedTrees = ArrayList<Uri>()
        for (treeUri in treeUris.distinctBy(Uri::toString)) {
            try {
                for (uri in LibraryScanner.findArchiveUris(ctx.contentResolver, treeUri) { foundUri, name ->
                    currentJob?.ensureActive()
                    val identity = runCatching {
                        "${foundUri.authority}:${DocumentsContract.getDocumentId(foundUri)}"
                    }.getOrDefault(foundUri.toString())
                    if (discoveredIds.add(identity)) {
                        discoveredCount++
                        if (discoveredCount <= 12 || discoveredCount % 12 == 0) {
                            mutableScanProgress.value = mutableScanProgress.value.copy(
                                discoveredCount = discoveredCount,
                                currentFile = name,
                            )
                        }
                    }
                }) {
                    currentJob?.ensureActive()
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
        mutableScanProgress.value = LibraryScanProgress(
            phase = LibraryScanPhase.INDEXING,
            discoveredCount = uris.size,
        )
        val built = ArrayList<ComicEntry>(uris.size)
        var processedCount = 0
        for (uri in uris) {
            currentJob?.ensureActive()
            val name = displayName(uri)
            try {
                val size = fileLength(ctx, uri)
                val modified = fileModified(ctx, uri)
                val signature = if (size > 0 && modified > 0) "$size:$modified" else null
                val previous = previousByUri[uri.toString()]
                if (previous != null && previous.sizeBytes == size && signature != null &&
                    signature == stamps.getString(uri.toString(), null)
                ) {
                    built.add(previous)
                    updatedStamps[uri.toString()] = signature
                    continue
                }
                val result = buildEntry(uri, name)
                if (result.entry == null) {
                    warnings += result.warning ?: ScanWarning(name, skippedReason = "没有可读取的书页或章节")
                } else {
                    built.add(result.entry)
                    if (signature != null) updatedStamps[uri.toString()] = signature
                    result.warning?.let(warnings::add)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += ScanWarning(name, skippedReason = e.message ?: "归档损坏或无法读取")
            } finally {
                processedCount++
                if (currentJob?.isActive != false &&
                    (processedCount <= 12 || processedCount % 8 == 0 || processedCount == uris.size)) {
                    mutableScanProgress.value = mutableScanProgress.value.copy(
                        processedCount = processedCount,
                        importedCount = built.size,
                        currentFile = name,
                    )
                }
            }
        }
        currentJob?.ensureActive()
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
        previousEntries.filter { it.id !in builtIds }.forEach(LostBookStore::remember)
        built.forEach { LostBookStore.forget(it.id) }
        synchronized(lock) {
            indexStore?.replace(built)
            for (r in readers.values) closeQuietly(r)
            readers.clear()
            activeNestedReader = null
            idIndex.clear()
            for (e in built) idIndex[e.id] = e
            entries = built
        }
        stamps.edit().clear().also { editor ->
            updatedStamps.forEach { (key, value) -> editor.putString(key, value) }
        }.apply()
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
        val report = ScanReport(archiveCount = uris.size, importedCount = built.size, warnings = warnings)
        mutableScanProgress.value = LibraryScanProgress(
            phase = LibraryScanPhase.COMPLETED,
            discoveredCount = uris.size,
            processedCount = uris.size,
            importedCount = built.size,
            report = report,
            completedAtMillis = System.currentTimeMillis(),
        )
        report
        }
    }

    fun comics(): List<ComicEntry> = if (ExhibitionData.enabled) ExhibitionData.snapshot.comics else entries.map(::withUserTags)

    fun byId(id: String): ComicEntry? = if (ExhibitionData.enabled) ExhibitionData.comic(id) else synchronized(lock) { idIndex[id]?.let(::withUserTags) }

    suspend fun removeComic(id: String, deleteLocalFile: Boolean): Boolean {
        if (ExhibitionData.enabled) return false
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
            UserBookMetadataStore.remove(id)
            ProgressStore.remove(id)
            BookmarkStore.removeComic(id)
            appContext?.let { LibraryCollectionsStore(it).removeComic(id) }
            coverFile(id)?.delete()
        }
        return removed
    }

    /** Explicitly rebind reading data when an old SAF URI was replaced by a moved file. */
    fun relinkLostBook(oldId: String, newId: String): Boolean {
        if (ExhibitionData.enabled || oldId == newId) return false
        val target = byId(newId) ?: return false
        val lost = LostBookStore.list().firstOrNull { it.id == oldId } ?: return false
        if (lost.format != target.format) return false
        val context = appContext ?: return false
        val collections = LibraryCollectionsStore(context)
        val originalProgress = ProgressStore.load(oldId)
        if (originalProgress != null && ProgressStore.load(newId) == null) {
            val moved = originalProgress.copy(comicId = newId)
            ProgressStore.save(moved)
            collections.recordProgress(moved)
        }
        val existingBookmarkIds = BookmarkStore.forComic(newId).map(ReaderBookmark::id).toSet()
        BookmarkStore.forComic(oldId).forEach { bookmark ->
            BookmarkStore.add(bookmark.copy(
                id = if (bookmark.id in existingBookmarkIds) java.util.UUID.randomUUID().toString() else bookmark.id,
                comicId = newId,
            ))
        }
        BookmarkStore.removeComic(oldId)
        if (oldId in collections.favorites()) collections.setFavorite(newId, true)
        collections.bookLists().filter { oldId in it.comicIds }.forEach { list ->
            collections.setBookListMembership(list.name, newId, true)
        }
        val oldMeta = UserBookMetadataStore.load(oldId)
        if (UserBookMetadataStore.load(newId) == UserBookMetadata()) UserBookMetadataStore.save(newId, oldMeta)
        UserBookMetadataStore.remove(oldId)
        userTags?.get(oldId).orEmpty().forEach { tag -> userTags?.add(newId, tag) }
        userTags?.remove(oldId)
        collections.removeComic(oldId)
        ProgressStore.remove(oldId)
        LostBookStore.forget(oldId)
        LibrarySnapshot.invalidate()
        return true
    }

    fun addUserTag(id: String, tag: String): List<String> {
        if (ExhibitionData.enabled) return ExhibitionData.comic(id)?.tags.orEmpty()
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
        if (ExhibitionData.enabled) return ExhibitionData.comic(id)?.tags.orEmpty()
        val entry = synchronized(lock) { idIndex[id] } ?: throw EntryNotFound(id)
        val cleaned = tag.trim()
        require(cleaned.isNotEmpty()) { "Tag cannot be blank" }
        val remaining = userTags?.removeTag(id, cleaned)
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before removeUserTag")
        return mergeTags(entry.tags, remaining).also { LibrarySnapshot.invalidate() }
    }

    suspend fun readCover(id: String): ByteArray? {
        if (ExhibitionData.enabled) return null
        coverFile(id)?.takeIf(File::isFile)?.let { cached ->
            runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        }
        val entry = byId(id) ?: return null
        val bytes = (when {
            isPdfFormat(entry.format) -> openPdfSession(id).use { session ->
                PdfPages.coverBytes(session)
            }
            entry.coverEntryName == null -> null
            else -> withReader(id) { it.readEntryBytes(entry.coverEntryName) }
        }) ?: return null
        runCatching {
            coverFile(id)?.let { file ->
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
        return bytes
    }

    suspend fun readAuthors(id: String): List<String> {
        if (ExhibitionData.enabled) return ExhibitionData.comic(id)?.authors.orEmpty()
        val entry = byId(id) ?: return emptyList()
        return when {
            entry.authors.isNotEmpty() -> entry.authors
            isEpubFormat(entry.format) -> withReader(id) { EpubParser.parse(it)?.authors.orEmpty() }
            isPdfFormat(entry.format) || isTxtFormat(entry.format) -> emptyList()
            else -> withReader(id) { it.parseMetadata()?.authors.orEmpty() }
        }
    }

    fun openPdfSession(id: String): PdfDocumentSession {
        if (ExhibitionData.enabled) throw EntryNotFound(id)
        val entry = synchronized(lock) { idIndex[id] } ?: throw EntryNotFound(id)
        require(isPdfFormat(entry.format)) { "not a PDF book: $id" }
        val context = appContext
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before opening PDF")
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")
            ?: throw IOException("cannot open PDF: $id")
        try {
            return PdfDocumentSession(descriptor, PdfRenderer(descriptor))
        } catch (t: Throwable) {
            runCatching { descriptor.close() }
            throw t
        }
    }

    suspend fun readEpubChapter(id: String, chapterIndex: Int): ByteArray {
        val entry = byId(id) ?: throw EntryNotFound(id)
        require(isEpubFormat(entry.format)) { "not an EPUB book: $id" }
        val path = entry.chapters.getOrNull(chapterIndex)?.pageEntryPaths?.firstOrNull()
            ?: throw IndexOutOfBoundsException("chapterIndex $chapterIndex out of range")
        return readResourceBytes(id, path)
    }

    suspend fun readResourceBytes(id: String, path: String): ByteArray {
        if (ExhibitionData.enabled) throw EntryNotFound(id)
        return withContext(Dispatchers.IO) {
            withReader(id) { it.readEntryBytes(path) }
        }
    }

    suspend fun readTextChapter(id: String, chapterIndex: Int): String {
        val entry = byId(id) ?: throw EntryNotFound(id)
        require(isTxtFormat(entry.format)) { "not a TXT book: $id" }
        val chapter = entry.chapters.getOrNull(chapterIndex)
            ?: throw IndexOutOfBoundsException("TXT chapter $chapterIndex is missing")
        val span = chapter.pageEntryPaths.firstOrNull()?.let(TextDocumentIndex::parse)
        return withContext(Dispatchers.IO) {
            val context = appContext ?: error("LibraryRepository not initialized")
            context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { stream ->
                if (span != null) TextDocumentIndex.read(stream, span)
                else TextDocument.splitChapters(TextDocument.decode(stream.readBytes()))
                    .getOrNull(chapterIndex)?.content.orEmpty()
            } ?: throw IOException("cannot open TXT: ${entry.title}")
        }
    }

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
        if (ExhibitionData.enabled) return ExhibitionData.pageBytes(id, chapterIndex, pageIndex)
            ?: throw EntryNotFound("demo page $chapterIndex/$pageIndex")
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
        if (ExhibitionData.enabled) return ExhibitionData.comic(id) ?: throw EntryNotFound(id)
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

    private fun buildEntry(uri: Uri, displayName: String): BuildResult = when (libraryFileKind(displayName)) {
        LibraryFileKind.EPUB -> buildEpubEntry(uri, displayName)
        LibraryFileKind.PDF -> buildPdfEntry(uri, displayName)
        LibraryFileKind.TXT -> buildTextEntry(uri, displayName)
        else -> buildArchiveEntry(uri, displayName)
    }

    private fun fallbackTitle(displayName: String): String =
        displayName.substringBeforeLast('.', displayName).ifBlank { displayName }

    private fun buildArchiveEntry(uri: Uri, displayName: String): BuildResult {
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
                return BuildResult(null, warning?.copy(skippedReason = "没有可读取的书页")
                    ?: ScanWarning(displayName, skippedReason = "没有可读取的书页"))
            }
            val extension = displayName.substringAfterLast('.', "").lowercase()
            return BuildResult(ComicEntry(
                id = uri.toString(),
                uri = uri.toString(),
                title = meta?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(displayName),
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

    private fun buildEpubEntry(uri: Uri, displayName: String): BuildResult {
        val reader = ArchiveReader.open(uri)
        try {
            val info = EpubParser.parse(reader)
                ?: return BuildResult(null, ScanWarning(displayName, skippedReason = "EPUB OPF 或 spine 无法解析"))
            val missingMetadata = info.title.isNullOrBlank() || info.authors.isEmpty()
            val missingCover = info.coverEntryName == null
            val warning = if (missingMetadata || missingCover) {
                ScanWarning(displayName, missingMetadata = missingMetadata, missingCover = missingCover)
            } else null
            return BuildResult(ComicEntry(
                id = uri.toString(),
                uri = uri.toString(),
                title = info.title?.takeIf(String::isNotBlank) ?: fallbackTitle(displayName),
                authors = info.authors,
                tags = info.tags,
                format = "epub",
                isSerial = info.chapters.size > 1,
                chapters = info.chapters,
                coverEntryName = info.coverEntryName,
                sizeBytes = reader.sizeBytes()
            ), warning)
        } finally {
            closeQuietly(reader)
        }
    }

    private fun buildPdfEntry(uri: Uri, displayName: String): BuildResult {
        val context = appContext
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before scan")
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("cannot open PDF: $displayName")
        try {
            val renderer = PdfRenderer(descriptor)
            val result: BuildResult = try {
                val pageCount = renderer.pageCount
                if (pageCount <= 0) {
                    BuildResult(null, ScanWarning(displayName, skippedReason = "PDF 不含可渲染页面"))
                } else {
                    val chapters = listOf(
                        ChapterRef(
                            name = "PDF",
                            pageEntryPaths = (0 until pageCount).map { "pdf:$it" },
                            nestedArchiveEntryPath = null,
                        )
                    )
                    BuildResult(
                        ComicEntry(
                            id = uri.toString(),
                            uri = uri.toString(),
                            title = fallbackTitle(displayName),
                            authors = emptyList(),
                            tags = emptyList(),
                            format = "pdf",
                            isSerial = false,
                            chapters = chapters,
                            coverEntryName = null,
                            sizeBytes = fileLength(context, uri)
                        ),
                        null
                    )
                }
            } finally {
                runCatching { renderer.close() }
            }
            return result
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private fun buildTextEntry(uri: Uri, displayName: String): BuildResult {
        val context = appContext
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before scan")
        val chapters = context.contentResolver.openInputStream(uri)?.use(TextDocumentIndex::index)
            ?: throw IOException("cannot open TXT: $displayName")
        if (chapters.isEmpty()) {
            return BuildResult(null, ScanWarning(displayName, skippedReason = "TXT 文件为空"))
        }
        return BuildResult(
            ComicEntry(
                id = uri.toString(),
                uri = uri.toString(),
                title = fallbackTitle(displayName),
                authors = emptyList(),
                tags = emptyList(),
                format = "txt",
                isSerial = chapters.size > 1,
                chapters = chapters.map { chapter ->
                    ChapterRef(chapter.title, listOf(chapter.path()), null)
                },
                coverEntryName = null,
                sizeBytes = fileLength(context, uri)
            ),
            null
        )
    }

    private fun fileModified(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun fileLength(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun readDocumentBytes(uri: Uri): ByteArray {
        val context = appContext
            ?: throw IllegalStateException("LibraryRepository.init(context) must be called before reading documents")
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("cannot open document: $uri")
    }

    private fun displayName(uri: Uri): String {
        val ctx = appContext ?: return uri.lastPathSegment?.substringAfterLast(':').orEmpty()
        val queried = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf(String::isNotBlank)
            ?: Uri.decode(uri.lastPathSegment.orEmpty().substringAfterLast(':')).ifBlank { "未知文件" }
    }

    private fun withUserTags(entry: ComicEntry): ComicEntry {
        val localTags = userTags?.get(entry.id).orEmpty()
        val combined = mergeTags(entry.tags, localTags)
        return UserBookMetadataStore.apply(if (combined == entry.tags) entry else entry.copy(tags = combined))
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
