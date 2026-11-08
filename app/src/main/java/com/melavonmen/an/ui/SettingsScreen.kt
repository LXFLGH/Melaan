package com.melavonmen.an.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.LibrarySnapshot
import com.melavonmen.an.data.ReadingProgress
import com.melavonmen.an.data.ReaderSettingsStore
import com.melavonmen.an.data.LocalBackup
import com.melavonmen.an.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * T3c 设置/我的（共享契约，签名禁改）：
 *   @Composable fun SettingsScreen()
 * 视觉参照 comic-app-preview.html #screen-me：扫描文件夹卡(路径+「重新扫描」) + 菜单
 * (扫描管理/阅读设置/缓存与数据库/R18 封面屏蔽/深色阅读模式/阅读时屏幕常亮) + 统计条(藏书/已读页数/连续阅读)。
 *
 * 设置项持久化（SharedPreferences "melaan_settings"，阅读器集成契约，勿改键名）：
 *   tree_uri: String   — SAF 本地书库 tree uri
 *   dark_reading: Boolean (默认 true)   — 深色阅读模式
 *   keep_screen_on: Boolean (默认 false) — 阅读时屏幕常亮
 *   r18_cover_blocked: Boolean (默认 false) — 全局封面灰色屏蔽
 *   reader_scroll_mode: Boolean (默认 false) — 阅读器上下滚动模式
 */

private const val PREFS_NAME = "melaan_settings"
private const val KEY_TREE_URI = "tree_uri"
private const val KEY_DARK_READING = "dark_reading"
private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
private const val KEY_BACKGROUND_URI = "app_background_uri"
private const val KEY_READER_SCROLL_MODE = "reader_scroll_mode"
private const val KEY_READER_RTL = "reader_rtl"
private const val KEY_READER_FIT_WIDTH = "reader_fit_width"
private const val KEY_READER_SEAMLESS = "reader_seamless"
private const val KEY_READER_DOUBLE_PAGE = "reader_double_page"

private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val SubText: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Accent = Color(0xFFFF4B2B)
private val Line = Color(0xFFE7E9F0)
private val SwitchOff = Color(0xFFDDE0E9)

private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun displayPath(uriStr: String): String {
    if (uriStr.isEmpty()) return "未设置"
    val doc = Uri.decode(uriStr.substringAfterLast("/tree/", "").substringBefore('/'))
    return when {
        doc.isEmpty() -> uriStr
        doc.startsWith("primary:") -> "/storage/emulated/0/" + doc.removePrefix("primary:")
        else -> doc
    }
}

private fun computeReadPages(
    entries: List<ComicEntry>,
    progressById: Map<String, ReadingProgress?>,
): Int {
    var total = 0
    for (e in entries) {
        val p = progressById[e.id] ?: continue
        if (e.chapters.isEmpty()) continue
        val ci = p.chapterIndex.coerceIn(0, e.chapters.size - 1)
        var n = p.pageIndex.coerceAtLeast(0) + 1
        for (c in 0 until ci) n += e.chapters[c].pageEntryPaths.size
        total += n
    }
    return total
}

@Composable
fun SettingsScreen(
    backgroundUri: String?,
    onBackgroundChange: (String?) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val scope = rememberCoroutineScope()
    var treeUris by remember { mutableStateOf(emptyList<String>()) }
    var scanning by remember { mutableStateOf(false) }
    var indexedCount by remember { mutableIntStateOf(0) }
    var readPages by remember { mutableIntStateOf(0) }
    var storedDataBytes by remember { mutableStateOf(0L) }
    var darkReading by remember { mutableStateOf(true) }
    var keepScreenOn by remember { mutableStateOf(false) }
    var blockR18Covers by remember {
        mutableStateOf(prefs(context).getBoolean(CoverImageCache.KEY_R18_COVER_BLOCKED, false))
    }
    var scrollMode by remember { mutableStateOf(false) }
    var seamless by remember { mutableStateOf(true) }
    var rtl by remember { mutableStateOf(true) }
    var fitWidth by remember { mutableStateOf(false) }
    var doublePage by remember { mutableStateOf(false) }
    var autoNext by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var infoDialog by remember { mutableStateOf<String?>(null) }
    var scanReport by remember { mutableStateOf<com.melavonmen.an.data.ScanReport?>(null) }

    LaunchedEffect(Unit) {
        val sp = prefs(context)
        treeUris = sp.getString(LibraryRepository.PREF_KEY_TREE_URIS, null)
            ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.toList()
            ?: listOfNotNull(sp.getString(KEY_TREE_URI, null)?.takeIf(String::isNotBlank))
        darkReading = sp.getBoolean(KEY_DARK_READING, true)
        keepScreenOn = sp.getBoolean(KEY_KEEP_SCREEN_ON, false)
        blockR18Covers = sp.getBoolean(CoverImageCache.KEY_R18_COVER_BLOCKED, false)
        CoverImageCache.setR18CoversBlocked(blockR18Covers)
        scrollMode = sp.getBoolean(KEY_READER_SCROLL_MODE, false)
        seamless = sp.getBoolean(KEY_READER_SEAMLESS, true)
        rtl = sp.getBoolean(KEY_READER_RTL, true)
        fitWidth = sp.getBoolean(KEY_READER_FIT_WIDTH, false)
        doublePage = sp.getBoolean(KEY_READER_DOUBLE_PAGE, false)
        autoNext = ReaderSettingsStore.loadAutoNext(sp)
        withContext(Dispatchers.IO) {
            val data = LibrarySnapshot.load()
            indexedCount = data.comics.size
            readPages = computeReadPages(data.comics, data.progressById)
            storedDataBytes = LibraryRepository.cacheSizeBytes()
        }
    }

    fun rescan(uris: List<String>) {
        scanning = true
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { LibraryRepository.scan(uris.map(Uri::parse)) }.getOrNull()
            }
            if (report != null) {
                prefs(context).edit()
                    .putInt(LibraryRepository.PREF_KEY_CHAPTER_PARSER_VERSION, LibraryRepository.CHAPTER_PARSER_VERSION)
                    .apply()
                CoverImageCache.clear()
            }
            val data = LibrarySnapshot.load()
            indexedCount = data.comics.size
            readPages = computeReadPages(data.comics, data.progressById)
            storedDataBytes = LibraryRepository.cacheSizeBytes()
            scanning = false
            scanReport = report
            if (report?.warnings?.isNotEmpty() == true) infoDialog = "scan"
        }
    }

    fun saveTreeUris(uris: List<String>) {
        val unique = uris.distinct()
        treeUris = unique
        prefs(context).edit()
            .putString(LibraryRepository.PREF_KEY_TREE_URIS, unique.joinToString("\n"))
            .putString(KEY_TREE_URI, unique.firstOrNull())
            .apply()
        rescan(unique)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // 部分 provider 不支持持久化，忽略
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            saveTreeUris(treeUris + uri.toString())
        }
    }

    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            backgroundUri?.takeIf { it != uri.toString() }?.let { releaseBackgroundGrant(context, it) }
            prefs(context).edit().putString(KEY_BACKGROUND_URI, uri.toString()).apply()
            onBackgroundChange(uri.toString())
        }
    }

    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(LocalBackup.export(context)) }
                ?: error("无法写入备份文件")
        }.onSuccess { backupMessage = "备份已导出。" }.onFailure { backupMessage = "导出失败：${it.message.orEmpty()}" }
    }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                val limit = 10 * 1024 * 1024
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - total))
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "备份文件过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(StandardCharsets.UTF_8)
            } ?: error("无法读取备份文件")
            LocalBackup.import(context, raw)
        }.onSuccess { backupMessage = "备份数据已合并导入。" }.onFailure { backupMessage = "导入失败：${it.message.orEmpty()}" }
    }
    backupMessage?.let { message -> AlertDialog(onDismissRequest = { backupMessage = null }, confirmButton = {
        TextButton(onClick = { backupMessage = null }) { Text("知道了") }
    }, text = { Text(message) }) }

    infoDialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            confirmButton = {
                TextButton(onClick = { infoDialog = null }) { Text("知道了") }
            },
            title = {
                Text(when (kind) {
                    "reader" -> "阅读设置"
                    "backup" -> "备份与恢复"
                    "scan" -> "扫描完成 · ${scanReport?.importedCount ?: 0}/${scanReport?.archiveCount ?: 0} 部已导入"
                    "licenses" -> "授权信息"
                    else -> "缓存与数据库"
                })
            },
            text = {
                if (kind == "scan") {
                    val warnings = scanReport?.warnings.orEmpty()
                    val lines = warnings.take(10).joinToString("\n") { warning ->
                        buildString {
                            append(warning.displayName)
                            if (warning.missingMetadata) append(" · 缺少/无法解析 metadata，已使用文件名")
                            if (warning.missingCover) append(" · 缺少封面，将显示占位图")
                            warning.skippedReason?.let { append(" · 已跳过：$it") }
                        }
                    }
                    Text(lines + if (warnings.size > 10) "\n另有 ${warnings.size - 10} 项提醒" else "")
                } else if (kind == "licenses") {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "本项目\n项目使用限制：禁止商用。\n\n" +
                                "开源组件\n" +
                                "• PrismalAGSL 1.0.4 — MIT License（玻璃 UI；许可证文件：Prismal/LICENSE）\n" +
                                "• Apache Commons Compress 1.27.0 — Apache License 2.0（7z / ZIP 归档处理）\n" +
                                "• XZ for Java 1.10 — BSD Zero Clause License（0BSD；XZ / LZMA 解码）\n" +
                                "• Junrar — UnRAR free utility license（仅用于 RAR 解码；不得用于重建专有 RAR 压缩算法；完整文本：res/raw/junrar_license.txt）\n" +
                                "• AndroidX Jetpack Compose、Material 3、Activity、Core KTX、Lifecycle — Apache License 2.0\n\n" +
                                "第三方组件按各自许可证授权；项目的禁止商用声明不改变其许可证。"
                        )
                    }
                } else if (kind == "reader") {
                    Column {
                        Text("阅读方式")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSwitch(autoNext) {
                                autoNext = it
                                ReaderSettingsStore.saveAutoNext(prefs(context), it)
                            }
                            Text("章节结束后自动打开下一话", modifier = Modifier.padding(start = 8.dp))
                        }
                        listOf(false to "左右翻页", true to "上下滚动").forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = scrollMode == value, onClick = {
                                    scrollMode = value
                                    prefs(context).edit().putBoolean(KEY_READER_SCROLL_MODE, value).apply()
                                })
                                Text(label, modifier = Modifier.clickable {
                                    scrollMode = value
                                    prefs(context).edit().putBoolean(KEY_READER_SCROLL_MODE, value).apply()
                                })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSwitch(rtl) {
                                rtl = it
                                prefs(context).edit().putBoolean(KEY_READER_RTL, it).apply()
                            }
                            Text("从右向左翻页", modifier = Modifier.padding(start = 8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSwitch(fitWidth) {
                                fitWidth = it
                                prefs(context).edit().putBoolean(KEY_READER_FIT_WIDTH, it).apply()
                            }
                            Text("图片适宽", modifier = Modifier.padding(start = 8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSwitch(doublePage) {
                                doublePage = it
                                prefs(context).edit().putBoolean(KEY_READER_DOUBLE_PAGE, it).apply()
                            }
                            Text(stringResource(R.string.reader_double_page_global), modifier = Modifier.padding(start = 8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSwitch(seamless) {
                                seamless = it
                                prefs(context).edit().putBoolean(KEY_READER_SEAMLESS, it).apply()
                            }
                            Text("上下滚动时无缝衔接页面", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else if (kind == "backup") {
                    Column {
                        Text("备份包含阅读进度、书签、标签和书架数据，不包含原始书籍文件。恢复时只补入本机缺少的数据。")
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp)) {
                            Text("导出", color = Accent, modifier = Modifier.clickable { infoDialog = null; backupExport.launch("melaan-backup.json") })
                            Text("导入", color = Accent, modifier = Modifier.clickable { infoDialog = null; backupImport.launch(arrayOf("application/json", "text/*")) })
                        }
                    }
                } else {
                    Text(
                        "本地 SQLite 保存已解析标签和恢复书架所需的最小索引，封面图片保存在应用私有目录；书籍内容按需读取，不缓存。重新扫描会刷新书架和封面。"
                    )
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = if (compact) 96.dp else 24.dp)
    ) {
        Text(
            "我的",
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = Ink,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp)
        )

        // 扫描文件夹卡
        GlassSurface(
            modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Accent.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    FolderIcon()
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("本地文件夹", fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Text("${treeUris.size} 个目录", fontSize = 10.sp, color = SubText, modifier = Modifier.padding(top = 2.dp))
                }
            }
            treeUris.forEach { path ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayPath(path),
                        fontSize = 10.sp,
                        color = SubText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(top = 4.dp)
                    )
                    TextButton(onClick = {
                        val uri = Uri.parse(path)
                        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        saveTreeUris(treeUris - path)
                    }) { Text("移除", fontSize = 10.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { picker.launch(null) }) { Text("添加文件夹") }
                TextButton(onClick = {
                    if (treeUris.isEmpty()) picker.launch(null) else rescan(treeUris)
                }) { Text(if (scanning) "扫描中…" else "重新扫描") }
            }
            Text(
                "已索引 $indexedCount 部 · 支持 EPUB / PDF / TXT / 7z / ZIP",
                fontSize = 10.sp,
                color = SubText,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        }

        Spacer(Modifier.height(14.dp))

        GlassSurface(
            modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("App 背景图", fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text(if (backgroundUri == null) "未设置" else "已设置背景图片", fontSize = 10.sp, color = SubText, modifier = Modifier.padding(top = 3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                TextButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }) { Text("选择图片") }
                if (backgroundUri != null) TextButton(onClick = {
                    releaseBackgroundGrant(context, backgroundUri)
                    prefs(context).edit().remove(KEY_BACKGROUND_URI).apply()
                    onBackgroundChange(null)
                }) { Text("移除") }
            }
        }
        }

        Spacer(Modifier.height(14.dp))

        GlassSurface(
            modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("应用主题", fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("light" to "白昼", "dark" to "夜间", "system" to "跟随系统").forEach { (mode, label) ->
                        val selected = themeMode == mode
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
                                .clickable { onThemeModeChange(mode) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 菜单
        GlassSurface(
            modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
        Column(Modifier.fillMaxWidth()) {
            MenuRow(
                icon = { TintedIconBox(Accent.copy(alpha = 0.1f)) { Icon(Icons.Default.List, null, tint = Accent, modifier = Modifier.size(15.dp)) } },
                title = "扫描管理",
                subtitle = "管理多个本地文件夹并扫描新书籍",
                value = if (treeUris.isNotEmpty()) "${treeUris.size} 个" else "—",
                onClick = { picker.launch(null) }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFF7C6CFF).copy(alpha = 0.1f)) { Icon(Icons.Default.Settings, null, tint = Color(0xFF7C6CFF), modifier = Modifier.size(15.dp)) } },
                title = "阅读设置",
                subtitle = "翻页方向 · 适配方式 · 自动加载下一话",
                onClick = { infoDialog = "reader" }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFF2BB673).copy(alpha = 0.1f)) { Icon(Icons.Default.Info, null, tint = Color(0xFF2BB673), modifier = Modifier.size(15.dp)) } },
                title = "缓存与数据库",
                value = Formatter.formatShortFileSize(context, storedDataBytes),
                onClick = { infoDialog = "cache" }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFF4A8FE7).copy(alpha = 0.12f)) { Icon(Icons.Default.Info, null, tint = Color(0xFF4A8FE7), modifier = Modifier.size(15.dp)) } },
                title = "备份与恢复",
                subtitle = "导出本地阅读与书架数据",
                onClick = { infoDialog = "backup" }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFF808080).copy(alpha = 0.12f)) { Icon(Icons.Default.Info, null, tint = Color(0xFF808080), modifier = Modifier.size(15.dp)) } },
                title = stringResource(R.string.settings_r18_cover_blocking),
                subtitle = stringResource(R.string.settings_r18_cover_blocking_summary),
                trailing = {
                    MiniSwitch(blockR18Covers) { enabled ->
                        blockR18Covers = enabled
                        prefs(context).edit().putBoolean(CoverImageCache.KEY_R18_COVER_BLOCKED, enabled).apply()
                        CoverImageCache.setR18CoversBlocked(enabled)
                    }
                },
                onClick = {
                    blockR18Covers = !blockR18Covers
                    prefs(context).edit().putBoolean(CoverImageCache.KEY_R18_COVER_BLOCKED, blockR18Covers).apply()
                    CoverImageCache.setR18CoversBlocked(blockR18Covers)
                }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFF6B72D6).copy(alpha = 0.1f)) { Icon(Icons.Default.Info, null, tint = Color(0xFF6B72D6), modifier = Modifier.size(15.dp)) } },
                title = "授权信息",
                subtitle = "项目使用限制与开源组件许可证",
                onClick = { infoDialog = "licenses" }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFFF5A623).copy(alpha = 0.12f)) { MoonIcon() } },
                title = "深色阅读模式",
                trailing = {
                    MiniSwitch(darkReading) {
                        darkReading = it
                        prefs(context).edit().putBoolean(KEY_DARK_READING, it).apply()
                    }
                },
                onClick = {
                    darkReading = !darkReading
                    prefs(context).edit().putBoolean(KEY_DARK_READING, darkReading).apply()
                }
            )
            MenuRow(
                icon = { TintedIconBox(Color(0xFF16181F).copy(alpha = 0.07f)) { SunIcon() } },
                title = "阅读时屏幕常亮",
                trailing = {
                    MiniSwitch(keepScreenOn) {
                        keepScreenOn = it
                        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, it).apply()
                    }
                },
                onClick = {
                    keepScreenOn = !keepScreenOn
                    prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn).apply()
                }
            )
        }
        }

        Spacer(Modifier.height(16.dp))

        // 统计条：藏书 / 已读页数 / 连续阅读
        Row(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            StatBox(value = indexedCount.toString(), label = "藏书", modifier = Modifier.weight(1f))
            StatBox(
                value = String.format(Locale.US, "%,d", readPages),
                label = "已读页数",
                modifier = Modifier.weight(1f)
            )
            // 连续阅读：ProgressStore 无时间戳，暂无数据源（遗留风险，见回报）
            StatBox(value = "—", label = "连续阅读", modifier = Modifier.weight(1f))
        }
    }
}

private fun releaseBackgroundGrant(context: Context, uri: String) {
    runCatching {
        val parsed = Uri.parse(uri)
        context.contentResolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Composable
private fun MenuRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        icon()
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 10.5.sp,
                    color = SubText,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (value != null) {
            Text(value, fontSize = 11.sp, color = SubText)
        }
    }
}

@Composable
private fun TintedIconBox(tint: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier = Modifier) {
    GlassSurface(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .padding(vertical = 11.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Ink)
        Text(label, fontSize = 9.5.sp, color = SubText, modifier = Modifier.padding(top = 2.dp))
    }
    }
}

/** 预览稿同款小开关：42×24 圆角轨道 + 19dp 白色圆钮 */
@Composable
private fun MiniSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val knob by animateDpAsState(targetValue = if (on) 20.dp else 3.dp, label = "switchKnob")
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Accent else SwitchOff)
            .clickable { onChange(!on) }
    ) {
        Box(
            modifier = Modifier
                .padding(start = knob, top = 2.5.dp)
                .size(19.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun FolderIcon() {
    val c = Accent
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = c,
            topLeft = Offset(0f, 0f),
            size = Size(w * 0.45f, h * 0.30f),
            cornerRadius = CornerRadius(3.dp.toPx())
        )
        drawRoundRect(
            color = c,
            topLeft = Offset(0f, h * 0.20f),
            size = Size(w, h * 0.80f),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
}

@Composable
private fun MoonIcon() {
    val c = Color(0xFFE09A1F)
    Canvas(Modifier.size(15.dp)) {
        val p = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addOval(
                androidx.compose.ui.geometry.Rect(
                    size.width * 0.30f, -size.height * 0.14f,
                    size.width * 1.30f, size.height * 0.86f
                )
            )
        }
        drawPath(p, c)
    }
}

@Composable
private fun SunIcon() {
    val c = Color(0xFF16181F)
    Canvas(Modifier.size(15.dp)) {
        val r = size.minDimension
        drawCircle(c, radius = r * 0.22f)
        drawRays(c, r)
    }
}

private fun DrawScope.drawRays(color: Color, r: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val inner = r * 0.36f
    val outer = r * 0.5f
    for (i in 0 until 8) {
        val a = i * PI / 4
        drawLine(
            color = color,
            start = Offset(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat()),
            end = Offset(cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat()),
            strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
