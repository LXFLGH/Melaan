package com.melavonmen.an.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.melavonmen.an.data.PdfDocumentSession
import com.melavonmen.an.data.PdfPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PdfReaderExtras(session: PdfDocumentSession, onPageChange: (Int) -> Unit) {
    var thumbnails by remember(session) { mutableStateOf(false) }
    var search by remember(session) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { thumbnails = true }) { Text("页缩略图") }
        TextButton(onClick = { search = true }) { Text("PDF 搜索") }
    }

    if (thumbnails) {
        AlertDialog(
            onDismissRequest = { thumbnails = false },
            title = { Text("跳转到页面") },
            text = {
                LazyColumn {
                    items(session.pageCount) { index ->
                        PdfThumbnailRow(session, index) {
                            onPageChange(index)
                            thumbnails = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { thumbnails = false }) { Text("关闭") } },
        )
    }

    if (search) {
        var query by remember(session) { mutableStateOf("") }
        var results by remember(session) { mutableStateOf<List<Int>?>(null) }
        var error by remember(session) { mutableStateOf<String?>(null) }
        var working by remember(session) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { search = false },
            title = { Text("搜索 PDF 文本") },
            text = {
                Column {
                    if (Build.VERSION.SDK_INT < 35) {
                        Text("系统 PDF 文本检索需要 Android 15 或更新版本。")
                    } else {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索内容") },
                            singleLine = true,
                        )
                        TextButton(enabled = query.isNotBlank() && !working, onClick = {
                            working = true
                            error = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { PdfPages.search(session, query.trim()) }
                                }.onSuccess { results = it }.onFailure { error = it.message ?: "搜索失败" }
                                working = false
                            }
                        }) { Text("查找") }
                        if (working) CircularProgressIndicator()
                        if (error != null) Text(error!!)
                        results?.let { matches ->
                            if (matches.isEmpty()) Text("没有找到文字匹配项（扫描图片 PDF 不含可搜索文本）。")
                            else LazyColumn(modifier = Modifier.height(240.dp)) {
                                items(matches) { page ->
                                    TextButton(onClick = { onPageChange(page); search = false }) {
                                        Text("第 ${page + 1} 页")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { search = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun PdfThumbnailRow(session: PdfDocumentSession, index: Int, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, session, index) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfPages.renderPage(session, index, targetWidth = 160) }.getOrNull()
        }
    }
    val image = bitmap
    DisposableEffect(image) { onDispose { image?.recycle() } }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "PDF 第 ${index + 1} 页缩略图",
                modifier = Modifier.size(width = 64.dp, height = 90.dp),
                contentScale = ContentScale.Fit,
            )
        } else CircularProgressIndicator()
        Text("第 ${index + 1} 页")
    }
    Spacer(Modifier.height(8.dp))
}
