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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Locale

@Composable
fun DocumentReaderScreen(
    entry: ComicEntry,
    chapterIndex: Int,
    onExit: () -> Unit,
) {
    require(isDocumentFormat(entry.format)) { "unsupported document format: ${entry.format}" }
    val context = LocalContext.current
    val collections = remember(context) { LibraryCollectionsStore(context) }
    val chapterCount = entry.chapters.size.coerceAtLeast(1)
    var chapter by remember(entry.id) {
        mutableIntStateOf(chapterIndex.coerceIn(0, chapterCount - 1))
    }
    var page by remember(entry.id, chapter) { mutableIntStateOf(0) }
    var scrollPercent by remember(entry.id, chapter) { mutableIntStateOf(0) }
    val latestChapter by rememberUpdatedState(chapter)
    val latestPage by rememberUpdatedState(page)
    val latestPercent by rememberUpdatedState(scrollPercent)

    val saveProgress = {
        val total = when (mediaKind(entry.format)) {
            MediaKind.PDF -> entry.chapters.firstOrNull()?.pageEntryPaths?.size ?: 1
            else -> chapterCount
        }
        val percent = when (mediaKind(entry.format)) {
            MediaKind.PDF -> if (total > 0) ((latestPage + 1) * 100) / total else 0
            else -> latestPercent
        }
        val progress = ReadingProgress(entry.id, latestChapter, latestPage, percent.coerceIn(0, 100))
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

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ReaderTopBar(
            title = entry.title,
            subtitle = chapterSubtitle(entry, chapter),
            onBack = exit,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mediaKind(entry.format)) {
                MediaKind.PDF -> PdfContent(
                    entry = entry,
                    page = page,
                    onPageChange = { page = it },
                    onProgressChange = { scrollPercent = it },
                )
                MediaKind.EPUB -> EpubContent(
                    entry = entry,
                    chapter = chapter,
                    onProgressChange = { scrollPercent = it },
                )
                MediaKind.TXT -> TextContent(
                    entry = entry,
                    chapter = chapter,
                    onProgressChange = { scrollPercent = it },
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
            onChapterChange = { value ->
                chapter = value
                page = 0
                scrollPercent = 0
            },
            onPageChange = { page = it },
            onExit = exit,
        )
    }
}

@Composable
private fun ReaderTopBar(title: String, subtitle: String, onBack: () -> Unit) {
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
    onProgressChange: (Int) -> Unit,
) {
    val sessionState = remember(entry.id) { mutableStateOf<Result<PdfDocumentSession>?>(null) }
    LaunchedEffect(entry.id) {
        sessionState.value = runCatching {
            withContext(Dispatchers.IO) { LibraryRepository.openPdfSession(entry.id) }
        }
    }
    val result = sessionState.value
    val session = result?.getOrNull()
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose { session?.close() }
    }

    when {
        result == null -> DocumentLoading()
        result.isFailure -> DocumentError(result.exceptionOrNull()?.message.orEmpty().ifBlank { "无法打开 PDF" })
        session == null -> DocumentLoading()
        else -> {
            val pageCount = session.pageCount
            LaunchedEffect(pageCount) {
                if (page >= pageCount) onPageChange((pageCount - 1).coerceAtLeast(0))
            }
            val bitmap by produceState<Bitmap?>(initialValue = null, session, page) {
                value = withContext(Dispatchers.IO) {
                    runCatching { PdfPages.renderPage(session, page, targetWidth = 1080) }.getOrNull()
                }
            }
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "PDF 第 ${page + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                DocumentLoading()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubContent(
    entry: ComicEntry,
    chapter: Int,
    onProgressChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val chapterPath = entry.chapters.getOrNull(chapter)?.pageEntryPaths?.firstOrNull().orEmpty()
    val baseUrl = remember(chapterPath) { "https://melaan.epub/$chapterPath" }
    val html by produceState<String?>(initialValue = null, entry.id, chapter) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                String(LibraryRepository.readEpubChapter(entry.id, chapter), Charsets.UTF_8)
            }
        }.getOrNull()
    }
    val webView = remember(context) {
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
                    // Allow in-book links; block everything outside the current archive.
                    view.loadUrl(request.url.toString())
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    if (url.host != "melaan.epub") return emptyResponse()
                    val path = url.path?.trimStart('/') ?: return emptyResponse()
                    val bytes = runBlocking {
                        runCatching { LibraryRepository.readResourceBytes(entry.id, path) }.getOrNull()
                    } ?: return emptyResponse()
                    val mime = resourceMime(path)
                    val charset = if (mime.startsWith("text/") || mime == "application/xhtml+xml") "utf-8" else null
                    return WebResourceResponse(mime, charset, ByteArrayInputStream(bytes))
                }
            }
            setOnScrollChangeListener { view, _, scrollY, _, _ ->
                val web = view as? WebView ?: return@setOnScrollChangeListener
                val contentHeight = web.contentHeight * web.scale
                val max = (contentHeight - web.height).coerceAtLeast(1f)
                onProgressChange(((scrollY / max) * 100f).toInt().coerceIn(0, 100))
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
    LaunchedEffect(html, baseUrl) {
        html?.let { value ->
            webView.loadDataWithBaseURL(baseUrl, value, "application/xhtml+xml", "UTF-8", null)
        }
    }
    val value = html
    if (value == null) DocumentLoading() else AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun TextContent(
    entry: ComicEntry,
    chapter: Int,
    onProgressChange: (Int) -> Unit,
) {
    val text by produceState<String?>(initialValue = null, entry.id, chapter) {
        value = runCatching {
            withContext(Dispatchers.IO) { LibraryRepository.readTextChapter(entry.id, chapter) }
        }.getOrNull()
    }
    val listState = rememberLazyListState()
    val paragraphs = text.orEmpty().split(Regex("\\n\\s*\\n|\\r\\n\\s*\\r\\n"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .ifEmpty { listOf(text.orEmpty()) }
    LaunchedEffect(listState.isScrollInProgress, listState.firstVisibleItemIndex, paragraphs.size) {
        if (paragraphs.isNotEmpty()) {
            val percent = ((listState.firstVisibleItemIndex + 1) * 100) / paragraphs.size
            onProgressChange(percent.coerceIn(0, 100))
        }
    }
    if (text == null) {
        DocumentLoading()
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(paragraphs) { _, paragraph ->
                Text(
                    paragraph,
                    fontSize = 18.sp,
                    lineHeight = 30.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
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
private fun DocumentError(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
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
