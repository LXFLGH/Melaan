package com.melavonmen.an.ui

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.LibrarySnapshot
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.ReadingProgress
import com.melavonmen.an.data.chapterNoun
import com.melavonmen.an.data.DocumentProgress
import com.melavonmen.an.data.MediaKind
import com.melavonmen.an.data.mediaKind
import com.melavonmen.an.data.TagAliasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T3b 首页：继续阅读大卡 + 标签/状态筛选 chips + 统计条 + 最近导入横滑 + 书架预览网格。
 * 共享契约（签名禁改）：
 *   @Composable fun HomeScreen(onOpenComic:(String)->Unit, onRead:(String)->Unit)
 * 依赖（只引用勿改）：
 *   LibraryRepository.comics()/byId(id)/suspend readCover(id)
 *   ProgressStore.load(comicId)/latest()
 *   TagAliasStore.canonicalOf(tag)（并行实现，别名归一用于筛选 chips）
 * 视觉参照 comic-app-preview.html 首页：主色 #FF4B2B、浅色 #F6F7FA、卡片圆角 14-20dp。
 * 封面加载：produceState + BitmapFactory.decodeByteArray(inSampleSize) -> ImageBitmap；原始封面字节由仓库持久缓存。
 */

private val Accent = Color(0xFFFF4B2B)
private val Accent2 = Color(0xFFFF8A50)
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val Sub: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val Ok = Color(0xFF2BB673)
private val Gold = Color(0xFFF5A623)
private val CardWhite: Color @Composable get() = MaterialTheme.colorScheme.surface
private val TrackGray: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val BoldGray: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val TagDots = listOf(
    Accent, Gold, Ok, Color(0xFF7C6CFF), Color(0xFF4C9AFF), Color(0xFFE85D9E)
)

@Composable
fun HomeScreen(onOpenComic: (String) -> Unit, onRead: (String) -> Unit) {
    val retained = remember { LibrarySnapshot.peek() }
    var all by remember { mutableStateOf(retained?.comics.orEmpty()) }
    var progressById by remember { mutableStateOf(retained?.progressById.orEmpty()) }
    var latest by remember { mutableStateOf(retained?.latest) }
    val scanProgress by LibraryRepository.scanProgress.collectAsState()
    LaunchedEffect(scanProgress.completedAtMillis) {
        val data = LibrarySnapshot.load()
        progressById = data.progressById
        latest = data.latest
        all = data.comics
    }
    val continueEntry = latest?.let { p -> all.firstOrNull { it.id == p.comicId } }
    val latestProgress = latest
    var selectedKey by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }

    val chips by produceState(initialValue = emptyList<FilterChip>(), all, progressById) {
        value = withContext(Dispatchers.Default) { buildChips(all, progressById) }
    }
    val filtered by produceState(
        initialValue = if (selectedKey == "all") all else emptyList(),
        selectedKey,
        all,
        progressById,
    ) {
        value = withContext(Dispatchers.Default) { filterComics(all, progressById, selectedKey) }
    }
    val searchResults = remember(searchQuery, all) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList() else all.filter { entry ->
            entry.title.contains(q, true) || entry.authors.any { it.contains(q, true) } || entry.tags.any { it.contains(q, true) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val gridColumns = when {
        maxWidth >= 1100.dp -> 5
        maxWidth >= 760.dp -> 4
        maxWidth >= 520.dp -> 3
        else -> 2
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Color.Transparent),
        contentPadding = PaddingValues(bottom = if (maxWidth >= 600.dp) 28.dp else 96.dp)
    ) {
        item { HomeHeader(all) }

        item { HomeSearchField(searchQuery, onValueChange = { searchQuery = it }) }

        if (searchQuery.isNotBlank()) {
            item { SectionHead("搜索结果", trailing = "${searchResults.size} 部") }
            if (searchResults.isEmpty()) {
                item {
                    Text("没有找到匹配的作品", Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp), color = Sub, fontSize = 12.sp)
                }
            } else {
                items(items = searchResults.chunked(gridColumns), key = { "search-${it.first().id}" }) { rowEntries ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        rowEntries.forEach { entry ->
                            ShelfCard(entry, progressById[entry.id], Modifier.weight(1f), onOpenComic)
                        }
                        if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        } else {

        item {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 0.dp).padding(bottom = 16.dp)) {
                if (continueEntry != null && latestProgress != null) {
                    ResumeCard(continueEntry, latestProgress, onRead)
                } else {
                    EmptyResumeCard()
                }
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                items(items = chips, key = { it.key }) { chip ->
                    FilterChipPill(
                        chip = chip,
                        selected = chip.key == selectedKey
                    ) { selectedKey = chip.key }
                }
            }
        }

        item { StatsStrip(all, progressById) }

        if (all.isEmpty()) {
            item { EmptyLibrary() }
        } else {
            item { SectionHead("最近导入", trailing = "按导入时间") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    modifier = Modifier.padding(bottom = 22.dp)
                ) {
                    items(items = filtered, key = { it.id }) { entry ->
                        RecentCard(entry, onOpenComic)
                    }
                }
            }

            item { SectionHead("我的书架", trailing = "共 ${filtered.size} 部") }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        "没有符合筛选的作品",
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 24.dp),
                        color = Sub,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(items = filtered.chunked(gridColumns), key = { it.first().id }) { rowEntries ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        for (entry in rowEntries) {
                            ShelfCard(
                                entry = entry,
                                progress = progressById[entry.id],
                                modifier = Modifier.weight(1f),
                                onOpenComic = onOpenComic
                            )
                        }
                        if (rowEntries.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
        }
    }
    }
}

@Composable
private fun HomeSearchField(value: String, onValueChange: (String) -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp), shape = RoundedCornerShape(16.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⌕", color = Sub, fontSize = 19.sp, modifier = Modifier.padding(end = 8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 12.sp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("搜索作品名、作者或标签", color = Sub, fontSize = 12.sp)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    }
}

// ---------------------------------------------------------------- home header

@Composable
private fun HomeHeader(all: List<ComicEntry>) {
    val totalBytes = all.sumOf { it.sizeBytes }
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Melaan 阅读", fontSize = 21.sp, fontWeight = FontWeight.Black, color = Ink, letterSpacing = 1.sp)
            Box(
                Modifier
                    .padding(start = 3.dp, bottom = 4.dp)
                    .size(7.dp)
                    .background(Accent, CircleShape)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(Ok, CircleShape)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "本地书库已同步 · ${all.size} 部 · ${formatSize(totalBytes)}",
                fontSize = 10.5.sp,
                color = Sub
            )
        }
    }
}

// --------------------------------------------------------- continue-reading card

@Composable
private fun ResumeCard(entry: ComicEntry, progress: ReadingProgress, onRead: (String) -> Unit) {
    val chapterIndex = progress.chapterIndex.coerceIn(0, (entry.chapters.size - 1).coerceAtLeast(0))
    val kind = mediaKind(entry.format)
    val chapterName = if (kind == MediaKind.PDF) "第${progress.pageIndex + 1}页" else chapterLabel(chapterIndex, entry.format)
    val remain = (entry.chapters.size - chapterIndex - 1).coerceAtLeast(0)
    val totalPages = entry.chapters.firstOrNull()?.pageEntryPaths?.size ?: 1
    val subtitle = if (kind == MediaKind.PDF) "上次阅读 PDF · 共 ${totalPages} 页"
        else "上次阅读 $chapterName · 剩 $remain ${chapterNoun(entry.format)}"
    val positionText = when (kind) {
        MediaKind.PDF -> "读至第 ${progress.pageIndex + 1} 页 · 自动保存位置"
        MediaKind.EPUB, MediaKind.TXT -> "章内进度 ${progress.pageIndex.coerceIn(0, DocumentProgress.SCROLL_UNITS) / 100}% · 自动保存位置"
        MediaKind.COMIC -> "读至第 ${progress.pageIndex + 1} 页 · 自动保存书签"
    }
    val totalText = if (kind == MediaKind.PDF) "全 $totalPages 页" else "全 ${entry.chapters.size} ${chapterNoun(entry.format)}"
    val percent = progress.percent.coerceIn(0, 100)
    val frac = (percent / 100f).coerceIn(0f, 1f)

    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
    Column(Modifier.fillMaxWidth().padding(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 12.dp)
                    .background(Accent, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "继续阅读",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Accent,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(Modifier.height(10.dp))

        Row {
            Box(
                Modifier
                    .size(width = 78.dp, height = 104.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                CoverImage(entry.id, Modifier.fillMaxSize(), targetW = 320)
                FormatBadge(entry.format, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .height(104.dp)
            ) {
                Text(
                    entry.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    positionText,
                    fontSize = 11.sp,
                    color = Sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Bar(frac)
                Spacer(Modifier.height(5.dp))
                Row {
                    Text(totalText, fontSize = 10.sp, color = Sub)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$percent%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Accent
                    )
                }
            }
        }

        Spacer(Modifier.height(11.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x4DFF4B2B), spotColor = Color(0x4DFF4B2B))
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                .clickable { onRead(entry.id) },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▶", fontSize = 11.sp, color = Color.White)
                Spacer(Modifier.width(7.dp))
                Text(
                    "继续阅读 · $chapterName",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }
    }
    }
}

@Composable
private fun EmptyResumeCard() {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 12.dp)
                    .background(Accent, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "继续阅读",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Accent,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("还没有阅读记录", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text("去书架挑一本，回来这里一键续读", fontSize = 11.sp, color = Sub)
    }
    }
}

// ------------------------------------------------------------------- filter chips

private data class FilterChip(val key: String, val label: String, val count: Int, val dot: Color?)

private fun buildChips(
    all: List<ComicEntry>,
    progressById: Map<String, ReadingProgress?>
): List<FilterChip> {
    val unread = all.count { statusOf(progressById[it.id]) == 0 }
    val reading = all.count { statusOf(progressById[it.id]) == 1 }
    val done = all.count { statusOf(progressById[it.id]) == 2 }

    // canonicalOf 归一后按作品数排序，取前 12 个标签
    val canonToIds = LinkedHashMap<String, MutableSet<String>>()
    val canonToLabel = LinkedHashMap<String, String>()
    for (c in all) {
        for (raw in c.tags) {
            val canon = canonicalTag(raw)
            if (canon.isBlank()) continue
            canonToIds.getOrPut(canon) { LinkedHashSet() }.add(c.id)
            if (canon !in canonToLabel) canonToLabel[canon] = raw
        }
    }
    val tagChips = canonToIds.entries
        .sortedByDescending { it.value.size }
        .take(12)
        .mapIndexed { i, e ->
            FilterChip(
                key = "t:${e.key}",
                label = "${canonToLabel[e.key]} ${e.value.size}",
                count = e.value.size,
                dot = TagDots[i % TagDots.size]
            )
        }

    return buildList {
        add(FilterChip("all", "全部 ${all.size}", all.size, null))
        add(FilterChip("s:0", "未读 $unread", unread, Accent))
        add(FilterChip("s:1", "在读 $reading", reading, Gold))
        add(FilterChip("s:2", "已读完 $done", done, Ok))
        addAll(tagChips)
    }
}

private fun filterComics(
    all: List<ComicEntry>,
    progressById: Map<String, ReadingProgress?>,
    selectedKey: String
): List<ComicEntry> = when {
    selectedKey == "all" -> all
    selectedKey.startsWith("s:") -> {
        val s = selectedKey.removePrefix("s:").toIntOrNull() ?: 0
        all.filter { statusOf(progressById[it.id]) == s }
    }
    else -> {
        val canon = selectedKey.removePrefix("t:")
        all.filter { c -> c.tags.any { canonicalTag(it) == canon } }
    }
}

/** 0=未读 1=在读 2=已读完 */
private fun statusOf(p: ReadingProgress?): Int = when {
    p == null -> 0
    p.percent >= 100 -> 2
    else -> 1
}

/**
 * 别名归一入口（TagAliasStore 并行实现）。
 * 若 data/TagAliasStore 尚未就绪，兜底返回原标签，保证筛选链路不中断。
 */
private fun canonicalTag(tag: String): String = try {
    TagAliasStore.canonicalOf(tag).ifBlank { tag }
} catch (t: Throwable) {
    tag
}

@Composable
private fun FilterChipPill(chip: FilterChip, selected: Boolean, onClick: () -> Unit) {
    GlassSurface(shape = RoundedCornerShape(999.dp)) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, Line.copy(alpha = 0.55f), RoundedCornerShape(999.dp)))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chip.dot?.let {
            Box(
                Modifier
                    .size(7.dp)
                    .background(it, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            chip.label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else BoldGray
        )
    }
    }
}

// ------------------------------------------------------------------- stats strip

@Composable
private fun StatsStrip(all: List<ComicEntry>, progressById: Map<String, ReadingProgress?>) {
    val reading = all.count { statusOf(progressById[it.id]) == 1 }
    val totalBytes = all.sumOf { it.sizeBytes }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        StatBox("${all.size}", "本地藏书", Ink, Modifier.weight(1f))
        StatBox("$reading", "正在阅读", Accent, Modifier.weight(1f))
        StatBox(formatSize(totalBytes), "占用空间", Ink, Modifier.weight(1f))
    }
}

@Composable
private fun StatBox(number: String, label: String, numberColor: Color, modifier: Modifier = Modifier) {
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .padding(vertical = 11.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(number, fontSize = 16.sp, fontWeight = FontWeight.Black, color = numberColor, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 9.5.sp, color = Sub, maxLines = 1)
    }
    }
}

// ---------------------------------------------------------------- section header

@Composable
private fun SectionHead(title: String, trailing: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 15.dp)
                    .background(Brush.verticalGradient(listOf(Accent, Accent2)), RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
                letterSpacing = 0.3.sp
            )
        }
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, color = Sub)
        }
    }
}

// --------------------------------------------------------------- recent imports

@Composable
private fun RecentCard(entry: ComicEntry, onOpenComic: (String) -> Unit) {
    GlassSurface(modifier = Modifier.width(112.dp), shape = RoundedCornerShape(16.dp)) {
    Column(Modifier.fillMaxWidth().clickable { onOpenComic(entry.id) }.padding(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
        ) {
            CoverImage(entry.id, Modifier.fillMaxSize(), targetW = 320)
            FormatBadge(entry.format, Modifier.align(Alignment.TopStart).padding(5.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            entry.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${entry.chapters.size} ${chapterNoun(entry.format)} · ${formatSize(entry.sizeBytes)}",
            fontSize = 9.5.sp,
            color = Sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    }
}

// -------------------------------------------------------------- bookshelf grid

@Composable
private fun ShelfCard(
    entry: ComicEntry,
    progress: ReadingProgress?,
    modifier: Modifier = Modifier,
    onOpenComic: (String) -> Unit
) {
    val percent = progress?.percent ?: 0
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
    Column(Modifier.fillMaxWidth().clickable { onOpenComic(entry.id) }.padding(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .shadow(4.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
        ) {
            CoverImage(entry.id, Modifier.fillMaxSize(), targetW = 480)
            FormatBadge(entry.format, Modifier.align(Alignment.TopStart).padding(7.dp))
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x000C0E16),
                            1f to Color(0xB80C0E16)
                        )
                    )
            )
            Text(
                "$percent%",
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 5.dp),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "${entry.chapters.size} ${chapterNoun(entry.format)} · ${formatSize(entry.sizeBytes)}",
            fontSize = 10.5.sp,
            color = Sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entry.tags.take(5).forEach { t ->
                Box(
                    Modifier
                        .size(6.dp)
                        .background(tagColor(t), CircleShape)
                )
            }
        }
    }
    }
}

// -------------------------------------------------------------------- empty state

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("书库为空", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("在「我的」页选择文件夹扫描本地书库", fontSize = 11.sp, color = Sub)
    }
}

// ----------------------------------------------------------------- shared pieces

@Composable
private fun Bar(frac: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(TrackGray)
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(5.dp)
                .background(Brush.horizontalGradient(listOf(Accent, Accent2)), RoundedCornerShape(99.dp))
        )
    }
}

@Composable
private fun FormatBadge(format: String, modifier: Modifier = Modifier) {
    Text(
        format.uppercase(),
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xA80C0E16))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        fontSize = 8.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White,
        letterSpacing = 0.3.sp
    )
}

/** 共享封面 LRU； miss 时在 IO 线程读取与降采样，失败显示灰底占位。 */
@Composable
private fun CoverImage(id: String, modifier: Modifier, targetW: Int) {
    val bmp = rememberCoverBitmap(id, targetW)
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier.background(if (CoverImageCache.r18CoversBlocked) R18_COVER_MASK_COLOR else TrackGray))
    }
}

private fun formatSize(bytes: Long): String = when {    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "${bytes} B"
}

private fun chapterLabel(index: Int, format: String): String =
    "第${index.coerceAtLeast(0) + 1}${chapterNoun(format)}"

private fun tagColor(tag: String): Color =
    TagDots[(tag.hashCode() and Int.MAX_VALUE) % TagDots.size]
