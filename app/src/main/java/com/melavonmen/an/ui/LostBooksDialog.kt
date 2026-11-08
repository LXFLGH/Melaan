package com.melavonmen.an.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.LostBookInfo
import com.melavonmen.an.data.LostBookStore

/** No automatic guess: the user approves the exact old -> new URI association. */
@Composable
internal fun LostBooksDialog(entries: List<ComicEntry>, onDismiss: () -> Unit) {
    var lost by remember { mutableStateOf(LostBookStore.list()) }
    var selectedLost by remember { mutableStateOf<LostBookInfo?>(null) }
    var target by remember { mutableStateOf<ComicEntry?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val options = remember(entries, selectedLost) {
        val old = selectedLost
        if (old == null) emptyList() else entries.filter { it.format == old.format }
            .sortedWith(
                compareByDescending<ComicEntry> { it.title == old.title && it.sizeBytes == old.sizeBytes }
                    .thenByDescending { it.title == old.title }
                    .thenBy(ComicEntry::title)
            )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新关联移动或丢失的书籍") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原书丢失后的进度、书签、书单和标签可关联到新路径；不会修改或删除书籍文件。")
                if (lost.isEmpty()) Text("没有待关联的书籍")
                else {
                    Text("选择原书")
                    LazyColumn(modifier = Modifier.heightIn(max = 110.dp)) {
                        items(lost, key = LostBookInfo::id) { book ->
                            TextButton(onClick = { selectedLost = book; target = null }) {
                                Text((if (selectedLost?.id == book.id) "✓ " else "") + book.title)
                            }
                        }
                    }
                }
                if (selectedLost != null) {
                    Text("选择新文件（标题和大小一致的候选项排在最前）")
                    LazyColumn(modifier = Modifier.heightIn(max = 155.dp)) {
                        items(options, key = ComicEntry::id) { book ->
                            TextButton(onClick = { target = book }) {
                                Text((if (target?.id == book.id) "✓ " else "") + book.title)
                            }
                        }
                    }
                    TextButton(onClick = {
                        selectedLost?.let { LostBookStore.forget(it.id) }
                        lost = LostBookStore.list()
                        selectedLost = null
                        target = null
                    }) { Text("忽略这条丢失记录") }
                }
                result?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedLost != null && target != null,
                onClick = {
                    val source = selectedLost ?: return@TextButton
                    val newFile = target ?: return@TextButton
                    val ok = LibraryRepository.relinkLostBook(source.id, newFile.id)
                    result = if (ok) "已关联：${newFile.title}" else "关联失败：请重新确认文件"
                    if (ok) {
                        lost = LostBookStore.list()
                        selectedLost = null
                        target = null
                    }
                },
            ) { Text("确认重新关联") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
