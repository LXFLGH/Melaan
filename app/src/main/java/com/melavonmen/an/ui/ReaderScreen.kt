package com.melavonmen.an.ui

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.view.WindowManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.Settings
import android.view.KeyEvent
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.ArchiveFormatException
import com.melavonmen.an.data.EntryNotFound
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.ProgressStore
import com.melavonmen.an.data.BookmarkStore
import com.melavonmen.an.data.ReaderBookmark
import com.melavonmen.an.data.ReaderSettingsStore
import com.melavonmen.an.data.ReaderOverrides
import com.melavonmen.an.data.LibraryCollectionsStore
import com.melavonmen.an.data.ReadingProgress
import com.melavonmen.an.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.UUID

/**
 * T4 阅读器：暗色沉浸流式阅读。
 * 共享契约（签名禁改）：
 *   @Composable fun ReaderScreen(comicId:String, chapterIndex:Int, pageIndex:Int, onExit:()->Unit)
 * 依赖（T1 实现，只引用）：
 *   LibraryRepository.readPage(id, chapterIndex, pageIndex): ByteArray（suspend）
 *   LibraryRepository.byId(id): ComicEntry?（章数/页数 = entry.chapters[i].pageEntryPaths.size）
 *   ProgressStore.save(ReadingProgress(comicId, chapterIndex, pageIndex, percent))
 * 约束：仅内存预读当前 ±2 页（LruCache ≤ 6 页），禁止解压落盘；翻页节流保存进度；onExit 保存书签。
 */

private val ReaderBg = Color(0xFF0E1015)
private val Accent = Color(0xFFFF4B2B)
private val Accent2 = Color(0xFFFF8A50)
private val White = Color(0xFFFFFFFF)
private val White45 = Color(0x73FFFFFF)
private val White12 = Color(0x1FFFFFFF)
private val White16 = Color(0x29FFFFFF)
private const val KEY_READER_SCROLL_MODE = "reader_scroll_mode"
private const val KEY_READER_RTL = "reader_rtl"
private const val KEY_READER_FIT_WIDTH = "reader_fit_width"
private const val KEY_READER_SEAMLESS = "reader_seamless"
private const val KEY_DARK_READING = "dark_reading"
private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
private const val KEY_READER_DOUBLE_PAGE = "reader_double_page"

internal fun restoredScrollPage(page: Int, totalPages: Int): Int =
    page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

internal fun shouldAutoAdvanceOnScroll(index: Int, totalPages: Int, scrolling: Boolean, enabled: Boolean, hasNext: Boolean): Boolean =
    scrolling && enabled && hasNext && totalPages > 0 && index >= totalPages - 1

internal fun spreadStart(chapterIndex: Int, pageIndex: Int): Int = when {
    chapterIndex == 0 && pageIndex == 0 -> 0
    chapterIndex == 0 -> 1 + ((pageIndex - 1) / 2) * 2
    else -> (pageIndex / 2) * 2
}

/** Decode formats supported by Android 12, including AVIF, at a reduced in-memory size. */
private fun decodeSampled(bytes: ByteArray, targetW: Int = 1440): Bitmap? =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
        var sample = 1
        while (info.size.width / (sample * 2) >= targetW) sample *= 2
        decoder.setTargetSampleSize(sample)
    }

/** 内存页缓存：key = "chapter:page"，上限 6 页 */
private suspend fun loadPageBitmap(
    cache: LruCache<String, Bitmap>,
    comicId: String,
    chapter: Int,
    page: Int
): Bitmap? {
    val key = "$chapter:$page"
    cache.get(key)?.let { return it }
    val bytes = LibraryRepository.readPage(comicId, chapter, page)
    val bmp = decodeSampled(bytes) ?: return null
    cache.put(key, bmp)
    return bmp
}

@Composable
fun ReaderScreen(comicId: String, chapterIndex: Int, pageIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("melaan_settings", Context.MODE_PRIVATE) }
    val collections = remember(context) { LibraryCollectionsStore(context) }
    var entry by remember(comicId) { mutableStateOf<ComicEntry?>(null) }
    var loadFailed by remember(comicId) { mutableStateOf(false) }
    var loadError by remember(comicId) { mutableStateOf<String?>(null) }
    var chapter by remember(comicId) { mutableStateOf(chapterIndex) }
    var page by remember(comicId) { mutableStateOf(pageIndex) }
    var overrides by remember(comicId) { mutableStateOf(ReaderSettingsStore.load(preferences, comicId)) }
    var rtl by remember(comicId) { mutableStateOf(overrides.rtl ?: preferences.getBoolean(KEY_READER_RTL, true)) }
    var fitWidth by remember(comicId) { mutableStateOf(overrides.fitWidth ?: preferences.getBoolean(KEY_READER_FIT_WIDTH, false)) }
    var doublePage by remember(comicId) { mutableStateOf(overrides.doublePage ?: preferences.getBoolean(KEY_READER_DOUBLE_PAGE, false)) }
    val darkReading = remember(comicId) { preferences.getBoolean(KEY_DARK_READING, true) }
    val keepScreenOn = remember(comicId) { preferences.getBoolean(KEY_KEEP_SCREEN_ON, false) }
    var scrollMode by remember(comicId) {
        mutableStateOf(overrides.scrollMode ?: preferences.getBoolean(KEY_READER_SCROLL_MODE, false))
    }
    val seamless = remember(comicId) { preferences.getBoolean(KEY_READER_SEAMLESS, true) }
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var bookmarks by remember(comicId) { mutableStateOf(emptyList<ReaderBookmark>()) }
    var autoAdvancedChapter by remember(comicId) { mutableStateOf(-1) }
    BookmarkStore.init(context)
    var retryTick by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val dualPages = doublePage && landscape && !scrollMode
    LaunchedEffect(dualPages, page) {
        if (dualPages) page = spreadStart(chapter, page)
    }
    val cache = remember(comicId) { LruCache<String, Bitmap>(6) }
    val activity = remember(context) { context.findActivity() }
    val originalBrightness = remember(activity) { activity?.window?.attributes?.screenBrightness ?: -1f }
    var brightness by remember { mutableFloatStateOf(activityBrightness(activity)) }
    var zoom by remember(comicId, chapter, page) { mutableFloatStateOf(1f) }
    var panX by remember(comicId, chapter, page) { mutableFloatStateOf(0f) }
    var panY by remember(comicId, chapter, page) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val pageBackground = if (darkReading) ReaderBg else Color(0xFFF4F5F8)
    val primaryText = if (darkReading) White else Color(0xFF16181F)
    val secondaryText = if (darkReading) White45 else Color(0xFF747B8A)
    val panel = if (darkReading) White12 else Color(0xE6FFFFFF)
    val panelBorder = if (darkReading) White16 else Color(0x330E1015)

    val e = entry
    val chapters = e?.chapters.orEmpty()
    val totalPages = chapters.getOrNull(chapter)?.pageEntryPaths?.size ?: 0
    val chapterName = chapters.getOrNull(chapter)?.name?.takeIf { it.isNotBlank() } ?: "第${chapter + 1}话"
    val titleText = if (e == null) "…" else "${e.title.orEmpty()} · $chapterName"
    val fileText = if (e == null) "" else {
        "${e.title.orEmpty()}_${"%03d".format(chapter + 1)}.${e.format.orEmpty().lowercase()}"
    }
    val percent = if (totalPages <= 0) 0 else ((page + 1) * 100) / totalPages

    DisposableEffect(comicId) {
        onDispose { cache.evictAll() }
    }
    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        onDispose { activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = originalBrightness } } }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    LaunchedEffect(brightness, menuOpen) {
        if (menuOpen) activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = brightness } }
    }

    // 载入元数据并把入参夹到合法区间
    LaunchedEffect(comicId, chapter) {
        loadFailed = false
        loadError = null
        entry = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val current = LibraryRepository.byId(comicId) ?: throw EntryNotFound(comicId)
                if (current.chapters.isEmpty()) throw ArchiveFormatException("没有可读取的漫画章节")
                val target = chapter.coerceIn(0, current.chapters.lastIndex)
                LibraryRepository.resolveChapterForReading(comicId, target)
            }
            entry = loaded
            chapter = chapter.coerceIn(0, loaded.chapters.lastIndex)
            val total = loaded.chapters[chapter].pageEntryPaths.size
            page = if (total > 0) page.coerceIn(0, total - 1) else 0
        } catch (error: Exception) {
            loadError = error.message?.takeIf(String::isNotBlank) ?: "无法读取该话"
            loadFailed = true
        }
    }

    DisposableEffect(comicId) {
        onDispose { LibraryRepository.releaseNestedChapter(comicId) }
    }

    // 当前页解码 + 内存预读 ±2 页（顺序、近邻优先）
    val key = "$chapter:$page"
    var bitmap by remember(key) { mutableStateOf(cache.get(key)) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, entry, retryTick) {
        val cur = entry ?: return@LaunchedEffect
        failed = false
        if (bitmap == null) {
            try {
                val bmp = withContext(Dispatchers.IO) { loadPageBitmap(cache, comicId, chapter, page) }
                bitmap = bmp
                if (bmp == null) failed = true
            } catch (t: Throwable) {
                failed = true
            }
        }
        for (d in intArrayOf(-1, 1, -2, 2)) {
            val p = page + d
            if (p < 0 || p >= totalPages) continue
            try {
                withContext(Dispatchers.IO) { loadPageBitmap(cache, comicId, chapter, p) }
            } catch (t: Throwable) {
                // 预读失败静默
            }
        }
    }

    // 翻页节流保存进度（停 600ms 落一次）
    LaunchedEffect(chapter, page, entry) {
        if (entry == null || totalPages <= 0) return@LaunchedEffect
        delay(600)
        withContext(Dispatchers.IO) {
            val progress = ReadingProgress(comicId, chapter, page, ((page + 1) * 100) / totalPages)
            ProgressStore.save(progress)
            collections.recordProgress(progress)
        }
    }

    // 上下滚动模式：进入时定位当前页；滚动时反写页码
    LaunchedEffect(listState, scrollMode, comicId, chapter, totalPages) {
        if (!scrollMode || totalPages <= 0) return@LaunchedEffect
        listState.scrollToItem(restoredScrollPage(page, totalPages))
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }.collect { (idx, scrolling) ->
            if (idx in 0 until totalPages) page = idx
            if (shouldAutoAdvanceOnScroll(idx, totalPages, scrolling, ReaderSettingsStore.loadAutoNext(preferences), chapter < chapters.lastIndex) && autoAdvancedChapter != chapter) {
                autoAdvancedChapter = chapter
                chapter += 1
                page = 0
            }
        }
    }

    // SharedPreferences.apply() 更新内存值后异步落盘，退出时直接保存即可。
    val exit: () -> Unit = {
        if (entry != null && totalPages > 0) {
            val progress = ReadingProgress(comicId, chapter, page, ((page + 1) * 100) / totalPages)
            ProgressStore.save(progress)
            collections.recordProgress(progress)
        }
        LibraryRepository.releaseNestedChapter(comicId)
        onExit()
    }
    BackHandler(onBack = exit)

    val flip: (Int) -> Unit = { d ->
        val target = if (dualPages) {
            val start = spreadStart(chapter, page)
            if (d > 0) if (chapter == 0 && start == 0) 1 else start + 2
            else if (chapter == 0 && start == 1) 0 else (start - 2).coerceAtLeast(0)
        } else page + d
        if (target in 0 until totalPages) page = target
        else if (d > 0 && page >= totalPages - 1 && chapter < chapters.lastIndex && ReaderSettingsStore.loadAutoNext(preferences)) {
            chapter += 1
            page = 0
        }
    }
    val seekPage: (Int) -> Unit = { value ->
        val target = value.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        page = if (dualPages) spreadStart(chapter, target) else target
    }
    val updateOverrides: (ReaderOverrides) -> Unit = { next ->
        overrides = next
        ReaderSettingsStore.save(preferences, comicId, next)
    }
    // 从右向左 = 点击区对调（左区=下一页，右区=上一页）
    val leftTap: () -> Unit = { if (zoom <= 1f) { if (rtl) flip(1) else flip(-1) } }
    val rightTap: () -> Unit = { if (zoom <= 1f) { if (rtl) flip(-1) else flip(1) } }
    var swipeX by remember(comicId, chapter) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 5f)
        if (zoom > 1f || nextZoom > 1f) {
            panX += panChange.x
            panY += panChange.y
            swipeX = 0f
        } else {
            swipeX += panChange.x
            if (abs(swipeX) >= with(density) { 56.dp.toPx() }) {
                flip(if ((swipeX < 0f) == rtl) 1 else -1)
                swipeX = 0f
            }
        }
        zoom = nextZoom
        if (nextZoom == 1f) { panX = 0f; panY = 0f }
    }
    val switchChapter: (Int) -> Unit = { d ->
        val target = chapter + d
        if (target in chapters.indices) {
            chapter = target
            page = 0
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(pageBackground)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { flip(1); true }
                    KeyEvent.KEYCODE_VOLUME_UP -> { flip(-1); true }
                    else -> false
                } else false
            }
            .then(if (scrollMode) Modifier else Modifier.transformable(transformState))
    ) {
        // ---- 页面：左右翻页 / 上下滚动 ----
        if (scrollMode) {
            if (loadFailed) {
                Text(loadError ?: "无法打开该作品", Modifier.align(Alignment.Center), color = secondaryText, fontSize = 13.sp)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(top = 66.dp, bottom = 96.dp),
                ) {
                    items(totalPages) { idx ->
                        ScrollPage(comicId, chapter, idx, cache, seamless)
                    }
                }
            }
        } else {
            var secondBitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
            val shownPage = if (dualPages) spreadStart(chapter, page) else page
            LaunchedEffect(comicId, chapter, shownPage, dualPages) {
                secondBitmap = if (dualPages && shownPage + 1 < totalPages) {
                    runCatching { withContext(Dispatchers.IO) { loadPageBitmap(cache, comicId, chapter, shownPage + 1) } }.getOrNull()
                } else null
            }
            var spreadBitmap by remember(comicId, chapter, shownPage) { mutableStateOf(cache.get("$chapter:$shownPage")) }
            LaunchedEffect(comicId, chapter, shownPage, dualPages) {
                if (dualPages && spreadBitmap == null) spreadBitmap = runCatching {
                    withContext(Dispatchers.IO) { loadPageBitmap(cache, comicId, chapter, shownPage) }
                }.getOrNull()
            }
            val primaryBitmap = if (dualPages) spreadBitmap else bitmap
            val imgMod = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 78.dp, bottom = 100.dp)
            when {
                loadFailed -> Text(loadError ?: "无法打开该作品", Modifier.align(Alignment.Center), color = secondaryText, fontSize = 13.sp)
                primaryBitmap != null -> Row(
                    imgMod.graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY)
    
                ) {
                    Image(primaryBitmap!!.asImageBitmap(), null, Modifier.weight(1f).fillMaxHeight(), contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit)
                    if (dualPages && !(chapter == 0 && shownPage == 0) && shownPage + 1 < totalPages) {
                        secondBitmap?.let { Image(it.asImageBitmap(), null, Modifier.weight(1f).fillMaxHeight(), contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit) }
                    }
                }
                failed -> Text("加载失败", Modifier.align(Alignment.Center).clickable { retryTick++ }, color = secondaryText, fontSize = 13.sp)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
        }

        // ---- 左右点击区（仅翻页模式；上下留出顶/底栏）----
        if (!scrollMode) {
            Row(Modifier.fillMaxSize().padding(top = 88.dp, bottom = 100.dp)) {
                Box(
                    Modifier.weight(0.34f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { leftTap() })
                Spacer(Modifier.weight(0.32f))
                Box(
                    Modifier.weight(0.34f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { rightTap() })
            }
        }

        // ---- 顶栏：返回 + 标题/文件名 + 章节菜单 ----
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(pageBackground.copy(alpha = 0.95f), pageBackground.copy(alpha = 0f))))
                .statusBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(34.dp).background(panel, RoundedCornerShape(11.dp)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { exit() },
                    contentAlignment = Alignment.Center
                ) {
                    ChevronLeftIcon(primaryText)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        titleText, Modifier.fillMaxWidth(), color = primaryText,
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (fileText.isNotEmpty()) {
                        Text(
                            fileText, Modifier.fillMaxWidth(), color = secondaryText,
                            fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box {
                    Box(
                        Modifier.size(34.dp).background(panel, RoundedCornerShape(11.dp)).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { menuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        MenuDotsIcon(primaryText)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("保存此页书签") }, onClick = {
                            menuOpen = false
                            BookmarkStore.add(ReaderBookmark(UUID.randomUUID().toString(), comicId, chapter, page, "第${chapter + 1}话 · 第${page + 1}页", System.currentTimeMillis()))
                        })
                        DropdownMenuItem(text = { Text("查看书签") }, onClick = {
                            menuOpen = false
                            bookmarks = BookmarkStore.forComic(comicId)
                            bookmarksOpen = true
                        })
                        DropdownMenuItem(
                            text = { Text(stringResource(if (scrollMode) R.string.reader_switch_scroll_mode else R.string.reader_switch_page_mode)) },
                            onClick = {
                                scrollMode = !scrollMode
                                updateOverrides(overrides.copy(scrollMode = scrollMode))
                            }
                        )
                        DropdownMenuItem(text = { Text(stringResource(if (rtl) R.string.reader_switch_ltr else R.string.reader_switch_rtl)) }, onClick = {
                            rtl = !rtl
                            updateOverrides(overrides.copy(rtl = rtl))
                        })
                        DropdownMenuItem(text = { Text(stringResource(if (fitWidth) R.string.reader_switch_fit else R.string.reader_switch_width)) }, onClick = {
                            fitWidth = !fitWidth
                            updateOverrides(overrides.copy(fitWidth = fitWidth))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_toggle_double_page, stringResource(if (doublePage) R.string.reader_disabled else R.string.reader_enabled))) }, onClick = {
                            doublePage = !doublePage
                            updateOverrides(overrides.copy(doublePage = doublePage))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_reset_overrides)) }, onClick = {
                            scrollMode = preferences.getBoolean(KEY_READER_SCROLL_MODE, false)
                            rtl = preferences.getBoolean(KEY_READER_RTL, true)
                            fitWidth = preferences.getBoolean(KEY_READER_FIT_WIDTH, false)
                            doublePage = preferences.getBoolean(KEY_READER_DOUBLE_PAGE, false)
                            updateOverrides(ReaderOverrides())
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.reader_brightness)) }, onClick = {}, enabled = false)
                        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.05f..1f, modifier = Modifier.width(220.dp).padding(horizontal = 14.dp))
                        DropdownMenuItem(
                            text = { Text("上一话") },
                            enabled = chapter > 0,
                            onClick = { menuOpen = false; switchChapter(-1) }
                        )
                        DropdownMenuItem(
                            text = { Text("下一话") },
                            enabled = chapter < chapters.lastIndex,
                            onClick = { menuOpen = false; switchChapter(1) }
                        )
                    }
                }
            }
        }

        if (bookmarksOpen) AlertDialog(
            onDismissRequest = { bookmarksOpen = false },
            title = { Text("书签") },
            text = {
                Column {
                    if (bookmarks.isEmpty()) Text("暂无书签")
                    bookmarks.forEach { mark ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mark.title, Modifier.weight(1f).clickable {
                                chapter = mark.chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                                page = mark.pageIndex.coerceAtLeast(0)
                                bookmarksOpen = false
                            })
                            TextButton(onClick = {
                                BookmarkStore.remove(mark.id)
                                bookmarks = BookmarkStore.forComic(comicId)
                            }) { Text("删除") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bookmarksOpen = false }) { Text("关闭") } }
        )

        // ---- 底栏：页码 + 进度条 + 模式钮 ----
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                .height(70.dp)
                .background(panel, RoundedCornerShape(20.dp))
                .border(1.dp, panelBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${page + 1}", color = primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(" / $totalPages", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            val seekLabel = stringResource(R.string.reader_seek_page)
            Slider(
                value = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0)).toFloat(),
                modifier = Modifier.weight(1f).height(32.dp).semantics {
                    contentDescription = seekLabel
                },
                enabled = totalPages > 1,
                onValueChangeFinished = {},
                valueRange = 0f..(totalPages - 1).coerceAtLeast(1).toFloat(),
                onValueChange = { seekPage(it.roundToInt()) },
            )
            Spacer(Modifier.width(10.dp))
            ModePill(if (rtl) "从右向左" else "从左向右", rtl, primaryText, panel) {
                rtl = !rtl
                updateOverrides(overrides.copy(rtl = rtl))
            }
            Spacer(Modifier.width(8.dp))
            ModePill(if (fitWidth) "适宽" else "整页", fitWidth, primaryText, panel) {
                fitWidth = !fitWidth
                updateOverrides(overrides.copy(fitWidth = fitWidth))
            }
        }
    }
}

@Composable
private fun ModePill(label: String, active: Boolean, textColor: Color, panelColor: Color, onClick: () -> Unit) {
    Text(
        label,
        Modifier.background(if (active) Accent else panelColor, CircleShape).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClick() }.padding(horizontal = 9.dp, vertical = 5.dp),
        color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ChevronLeftIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.62f, h * 0.18f)
            lineTo(w * 0.30f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.82f)
        }
        drawPath(
            p, color,
            style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun MenuDotsIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val r = 1.7.dp.toPx()
        listOf(0.22f, 0.5f, 0.78f).forEach { fy ->
            drawCircle(color, r, Offset(size.width / 2f, size.height * fy))
        }
    }
}

private fun activityBrightness(activity: Activity?): Float {
    val brightness = activity?.window?.attributes?.screenBrightness ?: -1f
    if (brightness >= 0f) return brightness.coerceIn(0.05f, 1f)
    val system = runCatching { Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    return system.coerceIn(0.05f, 1f)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 上下滚动模式单页：按需解码，宽度拉满，占位保持滚动节奏 */
@Composable
private fun ScrollPage(comicId: String, chapter: Int, index: Int, cache: LruCache<String, Bitmap>, seamless: Boolean) {
    val key = "$chapter:$index"
    var bmp by remember(key) { mutableStateOf(cache.get(key)) }
    LaunchedEffect(key) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { loadPageBitmap(cache, comicId, chapter, index) }
        }
    }
    val b = bmp
    if (b == null) {
        Box(
            Modifier.fillMaxWidth().height(520.dp).then(if (seamless) Modifier else Modifier.padding(horizontal = 14.dp)),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) }
    } else {
        Image(
            b.asImageBitmap(), null,
            Modifier.fillMaxWidth().then(if (seamless) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 3.dp)),
            contentScale = ContentScale.FillWidth
        )
    }
}
