package com.melavonmen.an.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.LibrarySnapshot
import com.melavonmen.an.data.ReadingProgress
import com.melavonmen.an.data.TagCatalog
import com.melavonmen.an.data.TagNormalizer
import com.melavonmen.an.data.chapterNoun
import com.melavonmen.an.data.TagStat
import com.melavonmen.an.data.VariantGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- 阅读状态（progress.percent 映射） ----------

private enum class ReadingStatus(val label: String, val color: Color) {
    UNREAD("未读", Color(0xFFE0453C)),
    READING("在读", Color(0xFFF5A623)),
    FINISHED("已读完", Color(0xFF2BB673)),
}

private fun statusOf(progress: ReadingProgress?): ReadingStatus {
    val pct = progress?.percent ?: 0
    return when {
        pct <= 0 -> ReadingStatus.UNREAD
        pct >= 100 -> ReadingStatus.FINISHED
        else -> ReadingStatus.READING
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

private fun tagColor(tag: String): Color {
    val hue = ((tag.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.5f, 0.88f)
}

// ---------- 标签页：双段（筛选 / 清洗） ----------

@Composable
fun TagsScreen(onOpenComic: (String) -> Unit) {
    var cleanMode by rememberSaveable { mutableStateOf(false) }
    val retained = remember { LibrarySnapshot.peek() }
    var comics by remember { mutableStateOf(retained?.comics.orEmpty()) }
    var progressById by remember { mutableStateOf(retained?.progressById.orEmpty()) }
    LaunchedEffect(Unit) {
        val data = LibrarySnapshot.load()
        progressById = data.progressById
        comics = data.comics
    }
    val stats by produceState(initialValue = emptyList<TagStat>(), comics) {
        value = withContext(Dispatchers.Default) { TagCatalog.build(comics) }
    }
    val tagCounts by produceState(initialValue = emptyMap<String, Int>(), comics) {
        value = withContext(Dispatchers.Default) {
            val counts = HashMap<String, Int>()
            for (e in comics) for (t in e.tags) counts[t] = (counts[t] ?: 0) + 1
            counts
        }
    }
    val variantGroups by produceState(initialValue = emptyList<VariantGroup>(), tagCounts) {
        value = withContext(Dispatchers.Default) { TagNormalizer.variantGroups(tagCounts) }
    }

    // 筛选状态（跨双段保持）
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedStatus by remember { mutableStateOf<ReadingStatus?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = 84.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text("标签", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(
                "点选标签实时交集筛选 · 再点一次取消 · 合并需显式确认，可撤销",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SegmentPill("筛选", selected = !cleanMode) { cleanMode = false }
            SegmentPill("清洗", selected = cleanMode) { cleanMode = true }
        }
        Spacer(Modifier.height(8.dp))
        if (cleanMode) {
            TagCleanSection(
                comics = comics,
                stats = stats,
                variantGroups = variantGroups,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            FilterSegment(
                comics = comics,
                progressById = progressById,
                stats = stats,
                selectedTags = selectedTags,
                selectedStatus = selectedStatus,
                onToggleTag = { t ->
                    selectedTags = if (t in selectedTags) selectedTags - t else selectedTags + t
                },
                onToggleStatus = { s ->
                    selectedStatus = if (selectedStatus == s) null else s
                },
                onClear = {
                    selectedTags = emptySet()
                    selectedStatus = null
                },
                onOpenComic = onOpenComic,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun SegmentPill(label: String, selected: Boolean, onClick: () -> Unit) {
    GlassSurface(shape = RoundedCornerShape(999.dp)) {
    Box(
        Modifier
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(999.dp))
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    }
}

// ---------- 筛选段：阅读状态单选 + 标签多选取交集 + 实时卡片网格 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSegment(
    comics: List<ComicEntry>,
    progressById: Map<String, ReadingProgress?>,
    stats: List<TagStat>,
    selectedTags: Set<String>,
    selectedStatus: ReadingStatus?,
    onToggleTag: (String) -> Unit,
    onToggleStatus: (ReadingStatus) -> Unit,
    onClear: () -> Unit,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedStats = remember(stats) {
        stats.sortedWith(compareByDescending<TagStat> { it.entryCount }.thenBy { it.tag })
    }
    val statusCounts = remember(comics, progressById) {
        ReadingStatus.entries.associateWith { s -> comics.count { statusOf(progressById[it.id]) == s } }
    }
    val base = if (selectedTags.isEmpty()) comics else TagCatalog.filterIntersection(comics, selectedTags)
    val filtered = if (selectedStatus == null) base else base.filter { statusOf(progressById[it.id]) == selectedStatus }
    val expr = (listOfNotNull(selectedStatus?.label) + selectedTags).joinToString(" + ")
    var topicsExpanded by rememberSaveable { mutableStateOf(true) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                SectionLabel("阅读状态")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingStatus.entries.forEach { st ->
                        StatusPill(
                            status = st,
                            count = statusCounts[st] ?: 0,
                            selected = selectedStatus == st,
                            onClick = { onToggleStatus(st) }
                        )
                    }
                }
            }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { topicsExpanded = !topicsExpanded }.padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "题材标签 · 多选取交集",
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                    Text(
                        if (topicsExpanded) "收起  ˄" else "${sortedStats.size} 个 · 展开  ˅",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (topicsExpanded) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sortedStats.forEach { s ->
                            TagPill(
                                label = s.tag,
                                countText = "${s.entryCount}部",
                                color = tagColor(s.tag),
                                selected = s.tag in selectedTags,
                                onClick = { onToggleTag(s.tag) }
                            )
                        }
                    }
                }
            }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ResultHead(expr = expr, count = filtered.size, onClear = onClear)
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyResult(expr = expr, onClear = onClear)
            }
        } else {
            items(filtered, key = { it.id }) { e ->
                ComicCard(
                    entry = e,
                    selectedTags = selectedTags,
                    status = statusOf(progressById[e.id]),
                    onClick = { onOpenComic(e.id) }
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun StatusPill(status: ReadingStatus, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (selected) status.color else MaterialTheme.colorScheme.surface.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) status.color else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(if (selected) Color.White else status.color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            status.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "$count 部",
            fontSize = 10.sp,
            color = if (selected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = Color.White)
        }
    }
}

@Composable
private fun TagPill(label: String, countText: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (selected) color else Color.Transparent, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(if (selected) Color.White.copy(alpha = 0.9f) else color, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(5.dp))
        Text(
            countText,
            fontSize = 10.sp,
            color = if (selected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = Color.White)
        }
    }
}

@Composable
private fun ResultHead(expr: String, count: Int, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("符合条件 ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$count",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFF4B2B)
                )
                Text(" 部", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (expr.isEmpty()) "全部作品" else "交集：$expr",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .background(Color(0xFFFF4B2B).copy(alpha = 0.09f), RoundedCornerShape(999.dp))
                .clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text("清空", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4B2B))
        }
    }
}

@Composable
private fun EmptyResult(expr: String, onClear: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("没有符合条件的作品", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            if (expr.isEmpty()) "先点选标签或阅读状态"
            else "「$expr」的交集为空\n试试减少标签，或换一个组合",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Button(
            onClick = onClear,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(top = 15.dp)
        ) {
            Text("清空筛选", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicCard(
    entry: ComicEntry,
    selectedTags: Set<String>,
    status: ReadingStatus,
    onClick: () -> Unit
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(11.dp)) {
        TagCoverImage(entry.id)
        Spacer(Modifier.height(8.dp))
        Text(
            entry.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp
        )
        val meta = buildList {
            if (entry.authors.isNotEmpty()) add("作者：" + entry.authors.joinToString("、"))
            add("${entry.chapters.size} ${chapterNoun(entry.format)}")
            add(formatSize(entry.sizeBytes))
        }.joinToString(" · ")
        Text(
            meta,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(status.color, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(status.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = status.color)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 7.dp)
        ) {
            entry.tags.take(6).forEach { t ->
                val hit = t in selectedTags
                Text(
                    t,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hit) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(if (hit) Color(0xFF4A6CF7) else MaterialTheme.colorScheme.surface.copy(alpha = 0.38f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            if (entry.tags.size > 6) {
                Text(
                    "+${entry.tags.size - 6}",
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
        }
    }
}

@Composable
private fun TagCoverImage(comicId: String) {
    val bitmap = rememberCoverBitmap(comicId, 480)
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(10.dp)).background(if (CoverImageCache.r18CoversBlocked) R18_COVER_MASK_COLOR else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
