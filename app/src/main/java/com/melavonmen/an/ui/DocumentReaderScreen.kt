package com.melavonmen.an.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.melavonmen.an.data.ChapterRef
import com.melavonmen.an.data.DocumentProgress
import com.melavonmen.an.data.DocumentReaderPreferences
import com.melavonmen.an.data.DocumentReaderPreferencesStore
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.LibraryCollectionsStore
import com.melavonmen.an.data.MediaKind
import com.melavonmen.an.data.PdfDocumentSession
import com.melavonmen.an.data.PdfPages
import com.melavonmen.an.data.ProgressStore
import com.melavonmen.an.data.ReadingProgress
import com.melavonmen.an.data.isDocumentFormat
import com.melavonmen.an.data.isPdfFormat
import com.melavonmen.an.data.mediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Locale

@OptIn(FlowPreview::class)
@Composable
fun DocumentReaderScreen(
    entry: ComicEntry,
    chapterIndex: Int,
    pageIndex: Int,
    onExit: () -> Unit,
) {
    require(isDocumentFormat(entry.format)) { "unsupported document format: ${entry.format}" }
    val context = LocalContext.current
    val collections = remember(context) { LibraryCollectionsStore(context) }
    var preferences by remember(entry.id) { mutableStateOf(DocumentReaderPreferencesStore.load(context)) }
    var showOptions by remember(entry.id) { mutableStateOf(false) }
    var seekToken by remember(entry.id) { mutableIntStateOf(0) }
    val chapterCount = entry.chapters.size.coerceAtLeast(1)
    var chapter by remember(entry.id) {
        mutableIntStateOf(chapterIndex.coerceIn(0, chapterCount - 1))
    }
    val isPdf = isPdfFormat(entry.format)
    // EPUB/TXT use pageIndex as chapter scroll position (0..10_000); PDF uses a page number.
    var page by remember(entry.id) { mutableIntStateOf(if (isPdf) pageIndex.coerceAtLeast(0) else 0) }
    var scrollUnits by remember(entry.id) {
        mutableIntStateOf(if (isPdf) 0 else pageIndex.coerceIn(0, DocumentProgress.SCROLL_UNITS))
    }
    var restoreScrollUnits by remember(entry.id) {
        mutableIntStateOf(if (isPdf) 0 else pageIndex.coerceIn(0, DocumentProgress.SCROLL_UNITS))
    }
    val latestChapter by rememberUpdatedState(chapter)
    val latestPage by rememberUpdatedState(page)
    val latestScroll by rememberUpdatedState(scrollUnits)
    fun seekChapter(target: Int) {
        chapter = target.coerceIn(0, chapterCount - 1)
        page = 0
        scrollUnits = 0
        restoreScrollUnits = 0
        seekToken++
    }

    val saveProgress = {
        val total = when (mediaKind(entry.format)) {
            MediaKind.PDF -> entry.chapters.firstOrNull()?.pageEntryPaths?.size ?: 1
            else -> chapterCount
        }
        val percent = when (mediaKind(entry.format)) {
            MediaKind.PDF -> DocumentProgress.pdfPercent(latestPage, total)
            else -> DocumentProgress.bookPercent(latestChapter, chapterCount, latestScroll)
        }
        val position = if (isPdf) latestPage else latestScroll
        val progress = ReadingProgress(entry.id, latestChapter, position, percent)
        runCatching { ProgressStore.save(progress) }
        runCatching { collections.recordProgress(progress) }
    }
    val exit = {
        saveProgress()
        onExit()
    }
    BackHandler(onBack = exit)
    androidx.compose.runtime.DisposableEffect(entry.id) {
        onDispose { saveProgress() }
    }
    // Persist debounced updates as well as exit: an OS process kill need not trigger onDispose.
    LaunchedEffect(entry.id) {
        snapshotFlow { Triple(chapter, page, scrollUnits) }
            .drop(1)
            .debounce(800L)
            .collect { saveProgress() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ReaderTopBar(
            title = entry.title,
            subtitle = if (isPdf) "PDF · 第 ${page + 1} 页" else chapterSubtitle(entry, chapter),
            onBack = exit,
            onOptions = { showOptions = true },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mediaKind(entry.format)) {
                MediaKind.PDF -> PdfContent(
                    entry = entry,
                    page = page,
                    onPageChange = { page = it },
                )
                MediaKind.EPUB -> if (preferences.seamless) {
                    SeamlessEpubReader(
                        entry = entry,
                        initialChapter = chapterIndex,
                        initialUnits = pageIndex,
                        requestedChapter = chapter,
                        seekToken = seekToken,
                        preferences = preferences,
                        onPositionChange = { current, units ->
                            chapter = current
                            scrollUnits = units
                        },
                        onLinkChapter = ::seekChapter,
                    )
                } else EpubContent(
                    entry = entry,
                    chapter = chapter,
                    restoreScrollUnits = restoreScrollUnits,
                    onChapterChange = ::seekChapter,
                    onProgressChange = { scrollUnits = it },
                )
                MediaKind.TXT -> if (preferences.seamless) {
                    SeamlessTextReader(
                        entry = entry,
                        initialChapter = chapterIndex,
                        initialUnits = pageIndex,
                        requestedChapter = chapter,
                        seekToken = seekToken,
                        preferences = preferences,
                        onPositionChange = { current, units ->
                            chapter = current
                            scrollUnits = units
                        },
                    )
                } else TextContent(
                    entry = entry,
                    chapter = chapter,
                    restoreScrollUnits = restoreScrollUnits,
                    preferences = preferences,
                    onProgressChange = { scrollUnits = it },
                )
                else -> DocumentError("这不是电子书文件")
            }
        }
        ReaderBottomBar(
            format = entry.format,
            chapter = chapter,
            chapterCount = chapterCount,
            pdfPageCount = entry.chapters.firstOrNull()?.pageEntryPaths?.size ?: 1,
            page = page,
            onChapterChange = ::seekChapter,
            onPageChange = { page = it },
            onExit = exit,
        )
    }
    if (showOptions) DocumentReaderOptions(
        current = preferences,
        isPdf = isPdf,
        onChange = { value ->
            preferences = value
            DocumentReaderPreferencesStore.save(context, value)
        },
        onDismiss = { showOptions = false },
    )
}

@Composable
private fun ReaderTopBar(title: String, subtitle: String, onBack: () -> Unit, onOptions: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassSurface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(
                Modifier.fillMaxSize().clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onOptions) { Text("Aa / ⚙") }
    }
}

@Composable
private fun ReaderBottomBar(
    format: String,
    chapter: Int,
    chapterCount: Int,
    pdfPageCount: Int,
    page: Int,
    onChapterChange: (Int) -> Unit,
    onPageChange: (Int) -> Unit,
    onExit: () -> Unit,
) {
    val isPdf = isPdfFormat(format)
    val position = if (isPdf) page else chapter
    val count = if (isPdf) pdfPageCount.coerceAtLeast(1) else chapterCount
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Slider(
            value = position.toFloat(),
            onValueChange = { value ->
                val next = value.toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
                if (isPdf) onPageChange(next) else onChapterChange(next)
            },
            valueRange = 0f..(count - 1).coerceAtLeast(0).toFloat(),
            enabled = count > 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            DocumentNavButton(Icons.AutoMirrored.Filled.NavigateBefore, "上一${if (isPdf) "页" else "章"}", position > 0) {
                if (isPdf) onPageChange(position - 1) else onChapterChange(chapter - 1)
            }
            Text(
                if (isPdf) "第 ${position + 1} / $count 页" else "第 ${position + 1} / $count 章",
                Modifier.weight(1f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DocumentNavButton(Icons.AutoMirrored.Filled.NavigateNext, "下一${if (isPdf) "页" else "章"}", position < count - 1) {
                if (isPdf) onPageChange(position + 1) else onChapterChange(chapter + 1)
            }
        }
    }
}

@Composable
private fun DocumentNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PdfContent(
    entry: ComicEntry,
    page: Int,
    onPageChange: (Int) -> Unit,
) {
    var retry by remember(entry.id) { mutableIntStateOf(0) }
    val sessionResult by produceState<Result<PdfDocumentSession>?>(initialValue = null, entry.id, retry) {
        value = runCatching {
            withContext(Dispatchers.IO) { LibraryRepository.openPdfSession(entry.id) }
        }
    }
    val session = sessionResult?.getOrNull()
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose { session?.close() }
    }

    when {
        sessionResult == null -> DocumentLoading()
        sessionResult!!.isFailure -> DocumentError(
            sessionResult!!.exceptionOrNull()?.message ?: "无法打开 PDF",
            onRetry = { retry++ },
        )
        session == null -> DocumentError("无法打开 PDF", onRetry = { retry++ })
        else -> {
            LaunchedEffect(session.pageCount, page) {
                if (page !in 0 until session.pageCount) {
                    onPageChange(page.coerceIn(0, (session.pageCount - 1).coerceAtLeast(0)))
                }
            }
            val bitmapResult by produceState<Result<Bitmap>?>(initialValue = null, session, page) {
                value = runCatching {
                    withContext(Dispatchers.IO) {
                        PdfPages.renderPage(session, page, targetWidth = 1080)
                            ?: error("PDF 页码不在有效范围内")
                    }
                }
            }
            val bitmap = bitmapResult?.getOrNull()
            androidx.compose.runtime.DisposableEffect(bitmap) {
                onDispose { bitmap?.recycle() }
            }
            when {
                bitmapResult == null -> DocumentLoading()
                bitmapResult!!.isFailure -> DocumentError(
                    bitmapResult!!.exceptionOrNull()?.message ?: "无法渲染 PDF 页面",
                    onRetry = { retry++ },
                )
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF 第 ${page + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                else -> DocumentError("PDF 页面为空", onRetry = { retry++ })
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubContent(
    entry: ComicEntry,
    chapter: Int,
    restoreScrollUnits: Int,
    onChapterChange: (Int) -> Unit,
    onProgressChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val chapterPath = entry.chapters.getOrNull(chapter)?.pageEntryPaths?.firstOrNull().orEmpty()
    val baseUrl = remember(chapterPath) {
        "https://melaan.epub/" + chapterPath.split('/').joinToString("/") { Uri.encode(it) }
    }
    var retry by remember(entry.id, chapter) { mutableIntStateOf(0) }
    val htmlResult by produceState<Result<String>?>(initialValue = null, entry.id, chapter, retry) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                String(LibraryRepository.readEpubChapter(entry.id, chapter), Charsets.UTF_8)
            }
        }
    }
    when {
        htmlResult == null -> {
            DocumentLoading()
            return
        }
        htmlResult!!.isFailure -> {
            DocumentError(htmlResult!!.exceptionOrNull()?.message ?: "EPUB 章节读取失败", onRetry = { retry++ })
            return
        }
    }
    val html = htmlResult!!.getOrThrow()
    val webView = remember(context, entry.id, chapter, retry) {
        var pendingRestore = true
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Only book-local navigation is allowed; never navigate to an external URL.
                    val uri = request.url
                    if (!isSafeEpubUri(uri)) return true
                    if (request.isForMainFrame) {
                        val targetPath = uri.path?.trimStart('/')
                        val targetIndex = entry.chapters.indexOfFirst {
                            it.pageEntryPaths.firstOrNull() == targetPath
                        }
                        if (targetIndex >= 0 && targetIndex != chapter) {
                            onChapterChange(targetIndex)
                            return true
                        }
                        // Unknown spine targets may not silently desynchronize the chapter index.
                        if (targetIndex < 0 && targetPath != chapterPath) return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (!isSafeEpubUri(url)) return emptyResponse()
                    val path = url.path?.trimStart('/') ?: return emptyResponse()
                    val bytes = runBlocking {
                        runCatching { LibraryRepository.readResourceBytes(entry.id, path) }.getOrNull()
                    } ?: return emptyResponse()
                    val mime = resourceMime(path)
                    val charset = if (mime.startsWith("text/") || mime == "application/xhtml+xml") "utf-8" else null
                    return WebResourceResponse(mime, charset, ByteArrayInputStream(bytes))
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!pendingRestore) return
                    view.post {
                        if (!pendingRestore) return@post
                        val scrollable = (view.contentHeight * view.scale - view.height).coerceAtLeast(0f)
                        val fraction = restoreScrollUnits.coerceIn(0, DocumentProgress.SCROLL_UNITS)
                        view.scrollTo(0, (scrollable * fraction / DocumentProgress.SCROLL_UNITS).toInt())
                        pendingRestore = false
                        onProgressChange(fraction)
                    }
                }
            }
            setOnScrollChangeListener { view, _, scrollY, _, _ ->
                if (pendingRestore) return@setOnScrollChangeListener
                val web = view as? WebView ?: return@setOnScrollChangeListener
                val scrollable = (web.contentHeight * web.scale - web.height).coerceAtLeast(1f)
                onProgressChange(
                    ((scrollY / scrollable) * DocumentProgress.SCROLL_UNITS)
                        .toInt().coerceIn(0, DocumentProgress.SCROLL_UNITS)
                )
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(html, baseUrl, webView) {
        webView.loadDataWithBaseURL(baseUrl, html, "application/xhtml+xml", "UTF-8", null)
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

/** External URLs and path traversal must never reach a book's local resource loader. */
internal fun isSafeEpubUri(uri: Uri): Boolean {
    if (uri.scheme != "https" || uri.host != "melaan.epub") return false
    val path = uri.path ?: return false
    return path.startsWith("/") && path.split('/').none { it == ".." || it == "." || it.contains('\\') }
}

@Composable
private fun TextContent(
    entry: ComicEntry,
    chapter: Int,
    restoreScrollUnits: Int,
    preferences: DocumentReaderPreferences,
    onProgressChange: (Int) -> Unit,
) {
    var retry by remember(entry.id, chapter) { mutableIntStateOf(0) }
    val textResult by produceState<Result<String>?>(initialValue = null, entry.id, chapter, retry) {
        value = runCatching {
            withContext(Dispatchers.IO) { LibraryRepository.readTextChapter(entry.id, chapter) }
        }
    }
    when {
        textResult == null -> {
            DocumentLoading()
            return
        }
        textResult!!.isFailure -> {
            DocumentError(textResult!!.exceptionOrNull()?.message ?: "TXT 章节读取失败", onRetry = { retry++ })
            return
        }
    }
    val text = textResult!!.getOrThrow()
    val paragraphs = remember(text) {
        text.split(Regex("\\n\\s*\\n|\\r\\n\\s*\\r\\n"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .ifEmpty { listOf(text) }
    }
    val listState = remember(entry.id, chapter, retry) { LazyListState() }
    var restored by remember(entry.id, chapter, retry) { mutableStateOf(false) }

    LaunchedEffect(listState, paragraphs) {
        val units = restoreScrollUnits.coerceIn(0, DocumentProgress.SCROLL_UNITS)
        val location = units.toFloat() / DocumentProgress.SCROLL_UNITS * paragraphs.size
        val itemIndex = location.toInt().coerceIn(0, paragraphs.lastIndex)
        listState.scrollToItem(itemIndex)
        val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }?.size ?: 0
        listState.scrollToItem(itemIndex, ((location - itemIndex) * size).toInt())
        restored = true
    }

    LaunchedEffect(listState, paragraphs.size, restored) {
        if (restored) {
            snapshotFlow {
                val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                if (visible == null) 0
                else if (!listState.canScrollForward) DocumentProgress.SCROLL_UNITS
                else {
                    val fraction = if (visible.size > 0) {
                        listState.firstVisibleItemScrollOffset.toFloat() / visible.size
                    } else 0f
                    (((listState.firstVisibleItemIndex + fraction) / paragraphs.size) * DocumentProgress.SCROLL_UNITS)
                        .toInt().coerceIn(0, DocumentProgress.SCROLL_UNITS)
                }
            }.distinctUntilChanged().collect { onProgressChange(it) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = preferences.horizontalMarginDp.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(paragraphs) { _, paragraph ->
            Text(
                paragraph,
                fontSize = preferences.fontSizeSp.sp,
                lineHeight = preferences.lineHeightSp.sp,
                color = if (preferences.nightMode) androidx.compose.ui.graphics.Color(0xFFE9E1D5) else MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun DocumentLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DocumentError(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun chapterSubtitle(entry: ComicEntry, chapter: Int): String {
    val name = entry.chapters.getOrNull(chapter)?.name.orEmpty()
    return when (mediaKind(entry.format)) {
        MediaKind.PDF -> "PDF · 第 ${chapter + 1} 页"
        else -> name.ifBlank { "第 ${chapter + 1} 章" }
    }
}

private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private fun resourceMime(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "xhtml", "html", "htm" -> "application/xhtml+xml"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
