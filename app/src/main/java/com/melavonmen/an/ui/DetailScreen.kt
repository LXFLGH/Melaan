package com.melavonmen.an.ui

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.ProgressStore
import com.melavonmen.an.data.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T3b 详情页：模糊头图 + 封面卡 + 标题/作者/话数/大小/格式 + 继续阅读/从头开始 +
 * 阅读进度条 + 标签 chips + 章节宫格（已读✓/当前高亮）。
 * 共享契约（签名禁改）：
 *   @Composable fun DetailScreen(comicId:String, onBack:()->Unit, onRead:(String,Int,Int)->Unit)
 * 依赖（只引用勿改）：
 *   LibraryRepository.byId(id)/suspend readCover(id)（ComicEntry/ChapterRef）
 *   ProgressStore.load(comicId)（ReadingProgress）
 * 视觉参照 comic-app-preview.html 详情浮层：主色 #FF4B2B、浅色 #F6F7FA、圆角 11-14dp。
 */

private val Accent = Color(0xFFFF4B2B)
private val Accent2 = Color(0xFFFF8A50)
private val Ok = Color(0xFF2BB673)
private val TrackGray: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
@Composable
fun DetailScreen(
    comicId: String,
    onBack: () -> Unit,
    onRead: (String, Int, Int) -> Unit,
    onDelete: (String, Boolean) -> Unit
) {
    val cachedEntry = remember(comicId) { LibraryRepository.byId(comicId) }
    var authors by remember(comicId) { mutableStateOf<List<String>?>(null) }
    val progress = remember(comicId) { ProgressStore.load(comicId) }
    var reversed by remember(comicId) { mutableStateOf(false) }
    var showDeleteDialog by remember(comicId) { mutableStateOf(false) }

    LaunchedEffect(comicId) {
        if (cachedEntry != null && cachedEntry.authors.isEmpty()) {
            authors = withContext(Dispatchers.IO) {
                runCatching { LibraryRepository.readAuthors(comicId) }.getOrDefault(emptyList())
            }
        }
    }
    val entry = cachedEntry?.let { if (authors.isNullOrEmpty()) it else it.copy(authors = authors.orEmpty()) }

    if (entry == null) {
        DetailNotFound(onBack)
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = 920.dp).fillMaxSize()) {
            item { DetailHero(entry, onBack, onRequestDelete = { showDeleteDialog = true }) }
            item { ActionRow(entry, progress, onRead) }
            item { ProgressBlock(entry, progress) }
            item {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 3.dp)) {
                    ComicTagEditor(entry.id, entry.tags)
                }
            }
            item {
                ChapterBlock(
                    entry = entry,
                    progress = progress,
                    reversed = reversed,
                    onToggleOrder = { reversed = it },
                    onRead = onRead
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除漫画？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("选择删除范围。删除本地文件后，归档将从设备中移除。")
                    TextButton(onClick = {
                        showDeleteDialog = false
                        onDelete(comicId, false)
                    }) { Text("仅从书架移除") }
                    TextButton(onClick = {
                        showDeleteDialog = false
                        onDelete(comicId, true)
                    }) { Text("删除本地文件并移出书架", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DetailNotFound(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("未找到该作品", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        Text(
            "返回",
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Accent)
                .clickable { onBack() }
                .padding(horizontal = 22.dp, vertical = 10.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
    }
}

// ------------------------------------------------------------------ hero + cover

@Composable
private fun DetailHero(entry: ComicEntry, onBack: () -> Unit, onRequestDelete: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        // 模糊头图 + 渐隐遮罩（上暗下融底）
        Box(
            Modifier
                .fillMaxWidth()
                .height(290.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            val cover = rememberCoverBitmap(entry.id, 720)
            if (CoverImageCache.r18CoversBlocked) {
                Box(Modifier.fillMaxSize().background(R18_COVER_MASK_COLOR))
            } else if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = null,
                    Modifier
                        .fillMaxSize()
                        .scale(1.12f)
                        .blur(12.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFE8DCD4))
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0x6B0C0E16),
                            0.35f to Color(0x00F6F7FA),
                            0.88f to Color(0xEBF6F7FA),
                            1.0f to MaterialTheme.colorScheme.background
                        )
                    )
            )
        }

        // 返回与删除按钮
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GlassSurface(modifier = Modifier.size(38.dp), shape = RoundedCornerShape(13.dp)) {
                Box(Modifier.fillMaxSize().clickable { onBack() }, contentAlignment = Alignment.Center) {
                    BackChevron(MaterialTheme.colorScheme.onSurface)
                }
            }
            GlassSurface(modifier = Modifier.size(38.dp), shape = RoundedCornerShape(13.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onRequestDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除漫画", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // 封面卡 + 标题区（下压 144dp，与头图交叠 86dp）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 144.dp)
        ) {
            Box(
                Modifier
                    .size(width = 108.dp, height = 146.dp)
                    .shadow(14.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
            ) {
                CoverImage(entry.id, Modifier.fillMaxSize(), targetW = 480)
                FormatBadge(entry.format, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 30.dp)
            ) {
                Text(
                    entry.title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 0.4.sp,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${entry.chapters.size} 话 · ${formatSize(entry.sizeBytes)} · ${entry.format.uppercase()}",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Text(
                    entry.authors.joinToString("、").ifBlank { "佚名" },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ----------------------------------------------------------------------- actions

@Composable
private fun ActionRow(
    entry: ComicEntry,
    progress: ReadingProgress?,
    onRead: (String, Int, Int) -> Unit
) {
    val resumeChapter = (progress?.chapterIndex ?: 0).coerceIn(0, (entry.chapters.size - 1).coerceAtLeast(0))
    val resumePage = (progress?.pageIndex ?: 0).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 主按钮：继续阅读（有进度定位书签页，否则从第 1 话起）
        Row(
            Modifier
                .weight(2f)
                .height(44.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x52FF4B2B), spotColor = Color(0x52FF4B2B))
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                .clickable {
                    if (progress == null) {
                        onRead(entry.id, 0, 0)
                    } else {
                        onRead(entry.id, resumeChapter, resumePage)
                    }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("▶", fontSize = 10.sp, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(
                "继续阅读 · ${chapterLabel(resumeChapter)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }
        // 次按钮：从头开始
        GlassSurface(modifier = Modifier.weight(1.2f).height(44.dp), shape = RoundedCornerShape(14.dp)) {
            Row(
                Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                    .clickable { onRead(entry.id, 0, 0) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("↺", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text("从头开始", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ------------------------------------------------------------- reading progress

@Composable
private fun ProgressBlock(entry: ComicEntry, progress: ReadingProgress?) {
    GlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(17.dp)) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        BlockTitle("阅读进度")
        if (progress == null) {
            Text("尚未开始阅读", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val percent = progress.percent.coerceIn(0, 100)
            Bar((percent / 100f).coerceIn(0f, 1f))
            Spacer(Modifier.height(10.dp))
            Text(
                "已读 $percent% · 读至${chapterLabel(progress.chapterIndex)} / 共 ${entry.chapters.size} 话 · 第 ${progress.pageIndex + 1} 页",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    }
}

// ------------------------------------------------------------------------ tags

// --------------------------------------------------------------------- chapters

@Composable
private fun ChapterBlock(
    entry: ComicEntry,
    progress: ReadingProgress?,
    reversed: Boolean,
    onToggleOrder: (Boolean) -> Unit,
    onRead: (String, Int, Int) -> Unit
) {
    GlassSurface(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(17.dp)) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        BlockTitle("章节", side = if (reversed) "正序 ↑" else "倒序 ↓") {
            onToggleOrder(!reversed)
        }
        val indices = entry.chapters.indices.toList().let { if (reversed) it.reversed() else it }
        val nowIndex = progress?.chapterIndex ?: -1
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            for (row in indices.chunked(3)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    for (idx in row) {
                        val isNow = idx == nowIndex
                        val isRead = nowIndex >= 0 && idx < nowIndex
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (isNow) Accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.26f))
                                .border(1.dp, if (isNow) Accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(11.dp))
                                .clickable {
                                    val page =
                                        if (isNow) (progress?.pageIndex ?: 0).coerceAtLeast(0) else 0
                                    onRead(entry.id, idx, page)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    chapterLabel(idx),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        isNow -> Accent
                                        isRead -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isRead) {
                                    Spacer(Modifier.width(4.dp))
                                    Text("✓", fontSize = 10.sp, color = Ok)
                                }
                            }
                        }
                    }
                    repeat(3 - row.size) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                        )
                    }
                }
            }
        }
    }
    }
}

// -------------------------------------------------------------------- building blocks

@Composable
private fun BlockTitle(title: String, side: String? = null, onSideClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        if (side != null) {
            Text(
                side,
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSideClick?.invoke() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Accent
            )
        }
    }
}

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
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 8.5.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White
    )
}

@Composable
private fun BackChevron(color: Color) {
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
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 简单换行流式布局：按可用宽度自动折行，间距固定。 */
@Composable
private fun WrapFlow(spacing: Dp, content: @Composable () -> Unit) {
    val gapPx = with(LocalDensity.current) { spacing.roundToPx() }
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(Constraints(maxWidth = constraints.maxWidth))
        }
        var x = 0
        var y = 0
        var rowH = 0
        val positions = ArrayList<Triple<Placeable, Int, Int>>(placeables.size)
        for (p in placeables) {
            if (x > 0 && x + p.width > constraints.maxWidth) {
                x = 0
                y += rowH + gapPx
                rowH = 0
            }
            positions.add(Triple(p, x, y))
            x += p.width + gapPx
            if (p.height > rowH) rowH = p.height
        }
        val totalH = if (placeables.isEmpty()) 0 else y + rowH
        layout(constraints.maxWidth, totalH) {
            for ((p, px, py) in positions) p.place(px, py)
        }
    }
}

// ----------------------------------------------------------------- shared pieces

/** 共享封面 LRU；miss 时在 IO 线程读取与降采样，失败显示灰底占位。 */
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

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "${bytes} B"
}

private fun chapterLabel(index: Int): String = "第${index.coerceAtLeast(0) + 1}话"

