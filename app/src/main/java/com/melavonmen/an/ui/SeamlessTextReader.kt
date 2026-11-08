package com.melavonmen.an.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.DocumentProgress
import com.melavonmen.an.data.DocumentReaderPreferences
import com.melavonmen.an.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Truly continuous TXT scroll: one LazyColumn spanning neighbouring chapters.
 * Only [current-1, current+2] chapter strings are kept, and text is divided into
 * small Compose rows so even headings-free large files stay safe for layout.
 */
@Composable
internal fun SeamlessTextReader(
    entry: ComicEntry,
    initialChapter: Int,
    initialUnits: Int,
    requestedChapter: Int,
    seekToken: Int,
    preferences: DocumentReaderPreferences,
    onPositionChange: (Int, Int) -> Unit,
) {
    data class Row(
        val chapter: Int,
        val part: Int,
        val text: String,
        val progressStart: Int,
        val progressEnd: Int,
        val isHeading: Boolean = false,
        val isError: Boolean = false,
        val isLoading: Boolean = false,
    ) {
        val id: String get() = "$chapter:$part"
    }
    val chapterCount = entry.chapters.size.coerceAtLeast(1)
    val startingChapter = initialChapter.coerceIn(0, chapterCount - 1)
    var firstLoaded by remember(entry.id) { mutableIntStateOf((startingChapter - 1).coerceAtLeast(0)) }
    var lastLoaded by remember(entry.id) { mutableIntStateOf((startingChapter + 2).coerceAtMost(chapterCount - 1)) }
    var retryToken by remember(entry.id) { mutableIntStateOf(0) }
    val cache = remember(entry.id) { mutableStateMapOf<Int, Result<String>>() }
    val listState = rememberLazyListState()
    var appliedSeek by remember(entry.id) { mutableIntStateOf(-1) }

    LaunchedEffect(entry.id, firstLoaded, lastLoaded, retryToken) {
        for (chapter in firstLoaded..lastLoaded) {
            if (cache.containsKey(chapter)) continue
            val outcome = withContext(Dispatchers.IO) {
                runCatching { LibraryRepository.readTextChapter(entry.id, chapter) }
            }
            cache[chapter] = outcome
        }
        cache.keys.toList().filter { it !in firstLoaded..lastLoaded }.forEach(cache::remove)
    }

    val rows by remember(entry.id) {
        derivedStateOf {
            buildList {
                for (chapter in firstLoaded..lastLoaded) {
                    val chapterName = entry.chapters.getOrNull(chapter)?.name.orEmpty()
                    add(Row(chapter, -1, chapterName.ifBlank { "第 ${chapter + 1} 章" }, 0, 0, isHeading = true))
                    val result = cache[chapter]
                    when {
                        result == null -> add(Row(chapter, -2, "正在预加载章节…", 0, 0, isLoading = true))
                        result.isFailure -> add(Row(chapter, -3, result.exceptionOrNull()?.message ?: "无法读取章节", 0, 0, isError = true))
                        else -> {
                            val content = result.getOrThrow()
                            val chunks = content
                                .split(Regex("\\n\\s*\\n|\\r\\n\\s*\\r\\n"))
                                .flatMap { paragraph ->
                                    paragraph.trim().chunked(1500).filter(String::isNotBlank)
                                }
                                .ifEmpty { listOf("（空章节）") }
                            val totalChars = chunks.sumOf(String::length).coerceAtLeast(1)
                            var offset = 0
                            chunks.forEachIndexed { part, text ->
                                val start = ((offset.toLong() * DocumentProgress.SCROLL_UNITS) / totalChars).toInt()
                                offset += text.length
                                val end = ((offset.toLong() * DocumentProgress.SCROLL_UNITS) / totalChars).toInt()
                                add(Row(chapter, part, text, start, end))
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(seekToken, requestedChapter, rows) {
        if (appliedSeek == seekToken) return@LaunchedEffect
        val target = (if (seekToken == 0) startingChapter else requestedChapter)
            .coerceIn(0, chapterCount - 1)
        if (target !in firstLoaded..lastLoaded) {
            firstLoaded = (target - 1).coerceAtLeast(0)
            lastLoaded = (target + 2).coerceAtMost(chapterCount - 1)
            return@LaunchedEffect
        }
        if (cache[target]?.isSuccess != true) return@LaunchedEffect
        val units = if (seekToken == 0) initialUnits.coerceIn(0, DocumentProgress.SCROLL_UNITS) else 0
        val targetRows = rows.withIndex().filter { it.value.chapter == target && it.value.part >= 0 }
        val rowIndex = targetRows.lastOrNull { it.value.progressStart <= units }?.index
            ?: rows.indexOfFirst { it.chapter == target }
        if (rowIndex >= 0) {
            listState.scrollToItem(rowIndex)
            appliedSeek = seekToken
        }
    }

    LaunchedEffect(entry.id, rows, appliedSeek) {
        if (appliedSeek < 0) return@LaunchedEffect
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val row = rows.getOrNull(index)
            if (row == null) null
            else {
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
                val within = if (size > 0) listState.firstVisibleItemScrollOffset.toFloat() / size else 0f
                val units = if (row.part < 0) 0 else
                    (row.progressStart + within * (row.progressEnd - row.progressStart)).toInt()
                row.chapter to units.coerceIn(0, DocumentProgress.SCROLL_UNITS)
            }
        }.distinctUntilChanged().collect { position ->
            if (position == null) return@collect
            val (chapter, units) = position
            onPositionChange(chapter, units)
            val windowStart = (chapter - 1).coerceAtLeast(0)
            val windowEnd = (chapter + 2).coerceAtMost(chapterCount - 1)
            if (windowStart != firstLoaded || windowEnd != lastLoaded) {
                firstLoaded = windowStart
                lastLoaded = windowEnd
            }
        }
    }

    val night = preferences.nightMode
    val background = if (night) Color(0xFF14171B) else MaterialTheme.colorScheme.background
    val foreground = if (night) Color(0xFFE9E1D5) else MaterialTheme.colorScheme.onBackground
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(background),
        contentPadding = PaddingValues(horizontal = preferences.horizontalMarginDp.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.id }) { _, row ->
            when {
                row.isHeading -> {
                    Text(
                        row.text,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        fontSize = (preferences.fontSizeSp + 5).sp,
                        fontWeight = FontWeight.Bold,
                        color = foreground,
                    )
                }
                row.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 10.dp))
                }
                row.isError -> Column {
                    Text(row.text, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        cache.remove(row.chapter)
                        retryToken++
                    }) { Text("重试读取章节") }
                }
                else -> Text(
                    row.text,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = preferences.fontSizeSp.sp,
                    lineHeight = preferences.lineHeightSp.sp,
                    color = foreground,
                )
            }
        }
    }
}
