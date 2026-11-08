package com.melavonmen.an.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.melavonmen.an.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.LibraryCollectionsStore
import com.melavonmen.an.data.LibraryMaintenance
import com.melavonmen.an.data.LibrarySnapshot
import com.melavonmen.an.data.MediaKind
import com.melavonmen.an.data.ReadingProgress
import java.text.DateFormat
import java.util.Date
import com.melavonmen.an.data.TagAliasStore
import com.melavonmen.an.data.TagCatalog
import com.melavonmen.an.data.chapterNoun
import com.melavonmen.an.data.isDocumentFormat
import com.melavonmen.an.data.mediaKind
import com.melavonmen.an.data.MediaKind
import com.melavonmen.an.data.DocumentProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * T3c 书架（共享契约，签名禁改）：
 *   @Composable fun LibraryScreen(onOpenComic:(String)->Unit)
 * 依赖（已实现/并行中，只引用）：
 *   LibraryRepository.comics()/byId(id)/readCover(id)；ProgressStore.load(id)
 *   TagAliasStore.canonicalOf(tag)；TagCatalog.filterIntersection(entries, alias, tags, status)
 * 视觉参照 comic-app-preview.html #screen-lib：分段(全部/在读/未读/已读完) + 标签 chips 点选交集
 * + 排序钮 + 3 列网格(封面/格式角标/进度%/标签色点) + 「重置」。
 */

private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val SubText: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val ChipText: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Accent = Color(0xFFFF4B2B)
private val SegTrack: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
private val CardPlaceholder: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant

private enum class Segment(val label: String, val status: String?) {
    ALL("全部", null),
    READING("在读", "在读"),
    UNREAD("未读", "未读"),
    DONE("已读完", "已读完")
}

private enum class SortMode(val label: String) {
    RECENT("最近阅读"),
    TITLE("标题"),
    SIZE("大小"),
    PROGRESS("进度")
}

private fun statusOf(progress: ReadingProgress?): String {
    val percent = progress?.percent ?: 0
    return when {
        percent <= 0 -> "未读"
        percent >= 100 -> "已读完"
        else -> "在读"
    }
}

/** 标签色点：命中预览稿固定色板，其余按名字哈希稳定回退 */
private val TagPalette = listOf(
    0xFF4A8FE7, 0xFFE0453C, 0xFFC05BD8, 0xFF8E7BFF, 0xFFF2589B,
    0xFF3EC7A8, 0xFF6C5CE7, 0xFF2BB673, 0xFFF5A623, 0xFF8B90A0
)

private fun tagColor(name: String): Color = when (name) {
    "科幻" -> Color(0xFF4A8FE7)
    "热血" -> Color(0xFFE0453C)
    "悬疑" -> Color(0xFFC05BD8)
    "奇幻" -> Color(0xFF8E7BFF)
    "恋爱" -> Color(0xFFF2589B)
    "日常" -> Color(0xFF3EC7A8)
    "推理" -> Color(0xFF6C5CE7)
    "冒险" -> Color(0xFF2BB673)
    "通勤补番" -> Color(0xFFF5A623)
    "待整理" -> Color(0xFF8B90A0)
    else -> Color(TagPalette[(name.hashCode() and Int.MAX_VALUE) % TagPalette.size])
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

@Composable
fun LibraryScreen(onOpenComic: (String) -> Unit) {
    val retained = remember { LibrarySnapshot.peek() }
    var comics by remember { mutableStateOf(retained?.comics.orEmpty()) }
    var progressById by remember { mutableStateOf(retained?.progressById.orEmpty()) }
    var segment by remember { mutableStateOf(Segment.ALL) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf<MediaKind?>(null) }
    val context = LocalContext.current
    val collections = remember(context) { LibraryCollectionsStore(context) }
    var favorites by remember { mutableStateOf(collections.favorites()) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(collections.recent()) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var tagDialog by remember { mutableStateOf(false) }
    var tagValue by remember { mutableStateOf("") }
    var removeTag by remember { mutableStateOf(false) }
    var bookLists by remember { mutableStateOf(collections.bookLists()) }
    var activeBookList by remember { mutableStateOf<String?>(null) }
    var bookListMenu by remember { mutableStateOf(false) }
    var addListMenu by remember { mutableStateOf(false) }
    var createListDialog by remember { mutableStateOf(false) }
    var listName by remember { mutableStateOf("") }
    var duplicateCandidates by remember { mutableStateOf(emptyList<com.melavonmen.an.data.DuplicateGroup>()) }
    var duplicateDialog by remember { mutableStateOf(false) }
    var removingDuplicate by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val data = LibrarySnapshot.load()
        progressById = data.progressById
        comics = data.comics
        favorites = collections.favorites()
        history = collections.recent()
        bookLists = collections.bookLists()
    }

    // 标签 chips：按 canonicalOf 归并别名后统计条目数，频次降序
    val chipTags by produceState(initialValue = emptyList<Pair<String, Int>>(), comics) {
        value = withContext(Dispatchers.Default) {
            val counts = LinkedHashMap<String, Int>()
            for (e in comics) {
                val seen = HashSet<String>()
                for (t in e.tags) {
                    val c = TagAliasStore.canonicalOf(t)
                    if (seen.add(c)) counts[c] = (counts[c] ?: 0) + 1
                }
            }
            counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }
    }

    // 交集筛选（点选交集；status 为分段阅读状态，null=全部）
    // 契约：TagCatalog.filterIntersection(entries, alias, tags, status) -> List<ComicEntry>
    val filtered = remember(comics, progressById, selectedTags, segment, favorites, favoriteOnly, activeBookList, bookLists, selectedKind) {
        val tagged = TagCatalog.filterIntersection(comics, TagAliasStore, selectedTags, null)
        (segment.status?.let { status -> tagged.filter { statusOf(progressById[it.id]) == status } } ?: tagged)
            .filter { !favoriteOnly || it.id in favorites }
            .filter { activeBookList == null || bookLists.firstOrNull { list -> list.name == activeBookList }?.comicIds?.contains(it.id) == true }
            .filter { selectedKind == null || mediaKind(it.format) == selectedKind }
    }
    val display = remember(filtered, sortMode, progressById) {
        when (sortMode) {
            SortMode.RECENT -> filtered
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            SortMode.PROGRESS -> filtered.sortedByDescending { progressById[it.id]?.percent ?: -1 }
        }
    }

    val totalSize = remember(display) { display.sumOf { it.sizeBytes } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "书架",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Ink,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormatFilterChip(label = "全部", selected = selectedKind == null) { selectedKind = null }
                MediaKind.entries.forEach { kind ->
                    FormatFilterChip(label = kind.label, selected = selectedKind == kind) { selectedKind = kind }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("检查疑似重复作品", color = Accent, modifier = Modifier.clickable {
                duplicateCandidates = LibraryMaintenance.duplicateGroups(comics)
                duplicateDialog = true
            }.padding(bottom = 4.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (favoriteOnly) stringResource(R.string.library_favorite_filter_on) else "☆ ${stringResource(R.string.library_favorite_filter)}", color = Accent, modifier = Modifier.clickable { favoriteOnly = !favoriteOnly })
                Text(if (showHistory) stringResource(R.string.library_return_shelf) else stringResource(R.string.library_history), color = Accent, modifier = Modifier.clickable { showHistory = !showHistory })
                Box {
                    Text(activeBookList ?: stringResource(R.string.library_booklists), color = Accent, modifier = Modifier.clickable { bookListMenu = true })
                    DropdownMenu(expanded = bookListMenu, onDismissRequest = { bookListMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_all_books)) }, onClick = { activeBookList = null; bookListMenu = false })
                        bookLists.forEach { list -> DropdownMenuItem(text = { Text(list.name) }, onClick = { activeBookList = list.name; bookListMenu = false }) }
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_create_booklist)) }, onClick = { bookListMenu = false; createListDialog = true })
                    }
                }
                if (selecting && bookLists.isNotEmpty()) Box {
                    Text(stringResource(R.string.library_add_to_booklist), color = Accent, modifier = Modifier.clickable { addListMenu = true })
                    DropdownMenu(expanded = addListMenu, onDismissRequest = { addListMenu = false }) {
                        bookLists.forEach { list -> DropdownMenuItem(text = { Text(list.name) }, onClick = {
                            selectedIds.forEach { collections.setBookListMembership(list.name, it, true) }
                            bookLists = collections.bookLists()
                            addListMenu = false
                        }) }
                    }
                }
                if (selecting && activeBookList != null) Text(stringResource(R.string.library_remove_from_booklist), color = Accent, modifier = Modifier.clickable {
                    selectedIds.forEach { collections.setBookListMembership(activeBookList!!, it, false) }
                    bookLists = collections.bookLists()
                })
                Text(if (selecting) stringResource(R.string.library_cancel_select) else stringResource(R.string.library_multi_select), color = Accent, modifier = Modifier.clickable { selecting = !selecting; if (!selecting) selectedIds = emptySet() })
                if (selecting) Text("${stringResource(R.string.library_selected_count, selectedIds.size)} · ${stringResource(R.string.library_batch_add_tag)}", color = Accent, modifier = Modifier.clickable { removeTag = false; tagDialog = true })
                if (selecting) Text(stringResource(R.string.library_batch_remove_tag), color = Accent, modifier = Modifier.clickable { removeTag = true; tagDialog = true })
            }
        }
        if (showHistory) {
            items(history, key = { "history:${it.progress.comicId}" }) { item ->
                val progress = item.progress
                val entry = comics.firstOrNull { it.id == progress.comicId }
                if (entry != null) Column(
                    Modifier.fillMaxWidth().clickable { onOpenComic(progress.comicId) }.padding(vertical = 8.dp)
                ) {
                    val location = when (mediaKind(entry.format)) {
                        MediaKind.PDF -> "第 ${progress.pageIndex + 1} 页"
                        MediaKind.EPUB, MediaKind.TXT -> "第 ${progress.chapterIndex + 1} 章 · 章内 ${progress.pageIndex.coerceIn(0, DocumentProgress.SCROLL_UNITS) / 100}%"
                        MediaKind.COMIC -> "第 ${progress.chapterIndex + 1} 话 · 第 ${progress.pageIndex + 1} 页"
                    }
                    Text("${entry.title} · $location", color = Ink)
                    Text(stringResource(R.string.library_history_timestamp, DateFormat.getDateTimeInstance().format(Date(item.timestampMillis))), color = SubText, fontSize = 11.sp)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 分段：全部 / 在读 / 未读 / 已读完
                GlassSurface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.height(38.dp).padding(3.dp)) {
                    Segment.entries.forEach { seg ->
                        val on = seg == segment
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .shadow(if (on) 3.dp else 0.dp, RoundedCornerShape(9.dp))
                                .clip(RoundedCornerShape(9.dp))
                        .background(if (on) Accent else Color.Transparent)
                                .clickable { segment = seg },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                seg.label,
                                fontSize = 11.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                color = if (on) Color.White else SubText
                            )
                        }
                    }
                }
                }
                Spacer(Modifier.width(10.dp))
                // 排序钮（玻璃按钮）
                Box {
                    GlassSurface(
                        shape = RoundedCornerShape(11.dp),
                        blurRadius = 4.dp,
                        refraction = 8.dp,
                        modifier = Modifier.clickable { sortMenuOpen = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                sortMode.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ChipText
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "排序",
                                tint = ChipText,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                onClick = {
                                    sortMode = m
                                    sortMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // 标签 chips：点选交集，再点取消；「全部标签」= 清空
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val noneSelected = selectedTags.isEmpty()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (noneSelected) Accent else Color.Transparent)
                        .clickable { selectedTags = emptySet() }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        "全部标签",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (noneSelected) Color.White else ChipText
                    )
                }
                chipTags.forEach { (tag, count) ->
                    val on = tag in selectedTags
                    val c = tagColor(tag)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) c else Color.Transparent)
                            .clickable {
                                selectedTags =
                                    if (on) selectedTags - tag else selectedTags + tag
                            }
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Color.White.copy(alpha = 0.85f) else c)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            tag,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (on) Color.White else ChipText
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            count.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (on) Color.White.copy(alpha = 0.78f) else SubText
                        )
                    }
                }
            }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${display.size} 部 · ${formatSize(totalSize)} · 支持 EPUB / PDF / TXT / 7z / ZIP",
                    fontSize = 10.5.sp,
                    color = SubText
                )
                Text(
                    "重置",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            selectedTags = emptySet()
                            segment = Segment.ALL
                            sortMode = SortMode.RECENT
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        if (display.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("没有符合条件的作品", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "试试减少标签，或换一个组合",
                        fontSize = 11.sp,
                        color = SubText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 15.dp)
                            .clip(RoundedCornerShape(12.dp))
                    .background(Accent)
                            .clickable {
                                selectedTags = emptySet()
                                segment = Segment.ALL
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text("清空筛选", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        } else if (!showHistory) {
            items(display, key = { it.id }) { entry ->
                ComicCard(
                    entry = entry,
                    progress = progressById[entry.id],
                    selected = entry.id in selectedIds,
                    favorite = entry.id in favorites,
                    onClick = { if (selecting) selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id else onOpenComic(entry.id) },
                    onFavorite = { favorite -> collections.setFavorite(entry.id, favorite); favorites = collections.favorites() }
                )
            }
        }
    }
    if (createListDialog) AlertDialog(
        onDismissRequest = { createListDialog = false },
        title = { Text(stringResource(R.string.library_create_booklist)) },
        text = { OutlinedTextField(listName, { listName = it }, label = { Text(stringResource(R.string.library_booklist_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            runCatching { collections.createBookList(listName) }.onSuccess { activeBookList = it.name; bookLists = collections.bookLists(); listName = ""; createListDialog = false }
        }) { Text(stringResource(R.string.library_confirm)) } },
        dismissButton = { TextButton(onClick = { createListDialog = false }) { Text(stringResource(R.string.library_cancel)) } }
    )
    if (tagDialog) AlertDialog(
        onDismissRequest = { tagDialog = false },
        title = { Text(if (removeTag) stringResource(R.string.library_batch_remove_title) else stringResource(R.string.library_batch_add_title)) },
        text = { OutlinedTextField(tagValue, { tagValue = it }, label = { Text(stringResource(R.string.library_tag_label)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val tag = tagValue.trim()
            if (tag.isNotEmpty()) selectedIds.forEach { id ->
                if (removeTag) LibraryRepository.removeUserTag(id, tag) else LibraryRepository.addUserTag(id, tag)
            }
            comics = LibraryRepository.comics()
            tagValue = ""; tagDialog = false
        }) { Text(stringResource(R.string.library_confirm)) } },
        dismissButton = { TextButton(onClick = { tagDialog = false }) { Text(stringResource(R.string.library_cancel)) } }
    )
    if (duplicateDialog) AlertDialog(
        onDismissRequest = { duplicateDialog = false },
        title = { Text("疑似重复作品") },
        text = {
            if (duplicateCandidates.isEmpty()) Text("没有发现同名且大小相同的作品。")
            else Column {
                duplicateCandidates.flatMap { it.entries }.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("从书架移除", color = Accent, modifier = Modifier.clickable { removingDuplicate = entry.id })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { duplicateDialog = false }) { Text("完成") } }
    )
    removingDuplicate?.let { id ->
        val entry = comics.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { removingDuplicate = null },
            title = { Text("从书架移除？") },
            text = { Text("仅移除本地书架记录，不会删除原始书籍文件。${entry?.title.orEmpty()}") },
            confirmButton = { TextButton(onClick = {
                removingDuplicate = null
                scope.launch {
                    if (LibraryRepository.removeComic(id, deleteLocalFile = false)) {
                        CoverImageCache.remove(id)
                        val data = LibrarySnapshot.load()
                        comics = data.comics
                        progressById = data.progressById
                    }
                }
            }) { Text("移除记录") } },
            dismissButton = { TextButton(onClick = { removingDuplicate = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun FormatFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent else MaterialTheme.colorScheme.surface.copy(alpha = 0.48f))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else ChipText,
        )
    }
}

@Composable
private fun ComicCard(
    entry: ComicEntry,
    progress: ReadingProgress?,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: (Boolean) -> Unit
) {
    val bitmap = rememberCoverBitmap(entry.id, 480)

    val where = if (progress == null) {
        "未开始 · ${formatSize(entry.sizeBytes)}"
    } else {
        val ch = entry.chapters.size.coerceAtLeast(1)
        if (mediaKind(entry.format) == MediaKind.PDF) "第${progress.pageIndex + 1}/${entry.chapters.firstOrNull()?.pageEntryPaths?.size ?: 1}页 · ${formatSize(entry.sizeBytes)}"
        else "第${progress.chapterIndex + 1}/${ch}${chapterNoun(entry.format)} · ${formatSize(entry.sizeBytes)}"
    }

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        blurRadius = 4.dp,
        refraction = 7.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (CoverImageCache.r18CoversBlocked) R18_COVER_MASK_COLOR else CardPlaceholder)
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 格式角标
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x9E0C0E16))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        entry.format.uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 0.3.sp
                    )
                }
                // 进度% 渐变底条
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xB80C0E16))
                            )
                        )
                )
                Text(
                    if (progress != null && progress.percent > 0) "${progress.percent}%" else "新",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 5.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (favorite) "★" else "☆", color = Accent, modifier = Modifier.clickable { onFavorite(!favorite) }.padding(end = 5.dp))
            if (selected) Text("✓", color = Accent, modifier = Modifier.padding(end = 5.dp))
            Text(
                entry.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            }
            Text(
                where,
                fontSize = 10.5.sp,
                color = SubText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entry.tags.take(4).forEach { t ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(tagColor(TagAliasStore.canonicalOf(t)))
                    )
                }
            }
        }
    }

}
