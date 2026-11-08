package com.melavonmen.an.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.ExhibitionData
import com.melavonmen.an.data.isDocumentFormat
import com.melavonmen.an.ui.theme.MelavonmenAnTheme

private const val KEY_THEME_MODE = "theme_mode"

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Default.Home),
    Library("书架", Icons.Default.Star),
    Tags("标签", Icons.Default.Search),
    Me("我的", Icons.Default.Person)
}

private val TabAccent = Color(0xFFFF4B2B)
private val TabIdle = Color(0xFF8B90A0)

@Composable
fun MelaanApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var exhibitionMode by remember { mutableStateOf(false) }
    var meTabTapCount by remember { mutableIntStateOf(0) }
    var detailComicId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerComicId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerChapterIndex by rememberSaveable { mutableIntStateOf(0) }
    var readerPageIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabStateHolder = rememberSaveableStateHolder()
    var backgroundUri by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("melaan_settings", Context.MODE_PRIVATE).getString("app_background_uri", null))
    }
    var themeMode by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("melaan_settings", Context.MODE_PRIVATE).getString(KEY_THEME_MODE, "system") ?: "system")
    }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val wallpaper by produceState<Bitmap?>(initialValue = null, backgroundUri) {
        value = backgroundUri?.let { uri -> withContext(Dispatchers.IO) { decodeWallpaper(context, uri) } }
    }

    val inReader = readerComicId != null
    val inDetail = detailComicId != null
    val readerEntry = remember(readerComicId) { readerComicId?.let(LibraryRepository::byId) }
    val documentReader = readerEntry != null && isDocumentFormat(readerEntry.format)

    fun selectTab(index: Int) {
        selectedTabIndex = index
        meTabTapCount = if (index == 3 && !exhibitionMode) meTabTapCount + 1 else if (index != 3) 0 else meTabTapCount
        if (meTabTapCount >= 5 && !exhibitionMode) {
            exhibitionMode = true
            ExhibitionData.enabled = true
            selectedTabIndex = 0
            meTabTapCount = 0
        }
    }

    BackHandler(enabled = inReader || inDetail) {
        when {
            inReader -> readerComicId = null
            inDetail -> detailComicId = null
        }
    }

    val glassState = rememberGlassState()

    MelavonmenAnTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalGlassState provides glassState) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 600.dp
                val contentWidth = if (maxWidth >= 1500.dp) 1400.dp else maxWidth
                val canvas = MaterialTheme.colorScheme.background
                Box(Modifier.fillMaxSize()) {
                    // Capture only the wallpaper; each foreground glass surface samples it once.
                    Box(Modifier.fillMaxSize().glassBackdrop(glassState)) {
                        Box(Modifier.fillMaxSize().background(canvas))
                        wallpaper?.takeUnless { exhibitionMode }?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                alpha = if (darkTheme) 0.15f else 0.28f
                            )
                        }
                        if (darkTheme) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.46f)))
                    }
                    Row(Modifier.fillMaxSize()) {
                        if (useRail && !inReader && !inDetail) {
                            GlassSurface(
                                modifier = Modifier.width(76.dp).fillMaxHeight().padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
                                shape = RoundedCornerShape(24.dp),
                                state = glassState
                            ) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Tab.entries.forEachIndexed { index, tab ->
                                        val selected = index == selectedTabIndex
                                        Column(
                                            Modifier.fillMaxWidth().height(58.dp).clickable { selectTab(index) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(tab.icon, tab.label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                            Text(tab.label, fontSize = 9.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Box(Modifier.fillMaxHeight().widthIn(max = contentWidth).fillMaxWidth()) {
                                when {
                                    inReader && documentReader && readerEntry != null -> DocumentReaderScreen(
                                        entry = readerEntry,
                                        chapterIndex = readerChapterIndex,
                                        onExit = { readerComicId = null }
                                    )
                                    inReader -> ReaderScreen(
                                        comicId = readerComicId!!,
                                        chapterIndex = readerChapterIndex,
                                        pageIndex = readerPageIndex,
                                        onExit = { readerComicId = null }
                                    )
                                    inDetail -> DetailScreen(
                                        comicId = detailComicId!!,
                                        onBack = { detailComicId = null },
                                        onRead = { comicId, chapterIndex, pageIndex ->
                                            readerComicId = comicId
                                            readerChapterIndex = chapterIndex
                                            readerPageIndex = pageIndex
                                        },
                                        onDelete = { comicId, deleteLocalFile ->
                                            scope.launch {
                                                val deleted = runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        LibraryRepository.removeComic(comicId, deleteLocalFile)
                                                    }
                                                }.getOrDefault(false)
                                                if (deleted) {
                                                    CoverImageCache.remove(comicId)
                                                    detailComicId = null
                                                    selectedTabIndex = 1
                                                } else {
                                                    Toast.makeText(context, "删除失败，请检查文件权限后重试", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    )
                                    else -> tabStateHolder.SaveableStateProvider(key = "tab-$selectedTabIndex") {
                                        when (selectedTabIndex) {
                                            0 -> HomeScreen(
                                                onOpenComic = { detailComicId = it },
                                                onRead = { comicId ->
                                                    readerComicId = comicId
                                                    readerChapterIndex = 0
                                                    readerPageIndex = 0
                                                }
                                            )
                                            1 -> LibraryScreen(onOpenComic = { detailComicId = it })
                                            2 -> TagsScreen(onOpenComic = { detailComicId = it })
                                            else -> SettingsScreen(
                                                backgroundUri = backgroundUri,
                                                onBackgroundChange = { backgroundUri = it },
                                                themeMode = themeMode,
                                                onThemeModeChange = {
                                                    themeMode = it
                                                    context.getSharedPreferences("melaan_settings", Context.MODE_PRIVATE)
                                                        .edit().putString(KEY_THEME_MODE, it).apply()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!useRail && !inReader && !inDetail) {
                        GlassSurface(
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            state = glassState,
                            blurRadius = 4.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(58.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Tab.entries.forEachIndexed { index, tab ->
                                    val on = index == selectedTabIndex
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight().clickable { selectTab(index) }.padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(tab.icon, contentDescription = tab.label, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                        Text(tab.label, fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeWallpaper(context: Context, uriString: String): Bitmap? = runCatching {
    val uri = Uri.parse(uriString)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1800 || bounds.outHeight / (sample * 2) >= 2400) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()
