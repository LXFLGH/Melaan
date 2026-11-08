package com.melavonmen.an.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melavonmen.an.data.BookmarkStore
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.DocumentSearch
import com.melavonmen.an.data.DocumentSearchHit
import com.melavonmen.an.data.ReaderBookmark
import com.melavonmen.an.data.isPdfFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun DocumentSearchDialog(
    entry: ComicEntry,
    onJump: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(entry.id) { mutableStateOf("") }
    var hits by remember(entry.id) { mutableStateOf<List<DocumentSearchHit>?>(null) }
    var working by remember(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全文搜索") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索正文") },
                    singleLine = true,
                )
                TextButton(onClick = {
                    working = true
                    error = null
                    hits = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { DocumentSearch.find(entry, query) }
                        }.onSuccess { hits = it }.onFailure { error = it.message ?: "搜索失败" }
                        working = false
                    }
                }, enabled = query.isNotBlank() && !working) { Text("搜索全书") }
                if (working) CircularProgressIndicator()
                if (error != null) Text("搜索失败：$error")
                hits?.let { results ->
                    if (results.isEmpty()) Text("没有匹配内容")
                    else LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(results) { item ->
                            Column {
                                TextButton(onClick = {
                                    onJump(item.chapterIndex, item.progressUnits)
                                    onDismiss()
                                }) { Text("第 ${item.chapterIndex + 1} 章 · ${item.progressUnits / 100}%") }
                                Text(item.excerpt)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun DocumentBookmarksDialog(
    entry: ComicEntry,
    currentChapter: Int,
    currentPosition: Int,
    onJump: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var bookmarks by remember(entry.id) { mutableStateOf(BookmarkStore.forComic(entry.id)) }
    var title by remember(entry.id, currentChapter, currentPosition) {
        mutableStateOf(
            if (isPdfFormat(entry.format)) "第 ${currentPosition + 1} 页"
            else "第 ${currentChapter + 1} 章 · ${currentPosition / 100}%"
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书签名称") },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        BookmarkStore.add(
                            ReaderBookmark(
                                id = UUID.randomUUID().toString(),
                                comicId = entry.id,
                                chapterIndex = currentChapter,
                                pageIndex = currentPosition,
                                title = title.trim().ifEmpty { "未命名书签" },
                                createdAtMillis = System.currentTimeMillis(),
                            )
                        )
                        bookmarks = BookmarkStore.forComic(entry.id)
                    },
                ) { Text("在当前位置添加书签") }
                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    items(bookmarks, key = ReaderBookmark::id) { bookmark ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                onJump(bookmark.chapterIndex, bookmark.pageIndex)
                                onDismiss()
                            }) { Text(bookmark.title, maxLines = 1) }
                            TextButton(onClick = {
                                BookmarkStore.remove(bookmark.id)
                                bookmarks = BookmarkStore.forComic(entry.id)
                            }) { Text("删除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
