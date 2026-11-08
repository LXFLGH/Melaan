package com.melavonmen.an

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.melavonmen.an.data.LibraryRepository
import com.melavonmen.an.data.LibrarySnapshot
import com.melavonmen.an.data.ProgressStore
import com.melavonmen.an.data.ExhibitionData
import com.melavonmen.an.data.TagAliasStore
import com.melavonmen.an.ui.MelaanApp
import com.melavonmen.an.ui.CoverImageCache
import com.melavonmen.an.ui.theme.MelavonmenAnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CoverImageCache.setR18CoversBlocked(
            getSharedPreferences("melaan_settings", MODE_PRIVATE)
                .getBoolean(CoverImageCache.KEY_R18_COVER_BLOCKED, false)
        )
        val appReady = mutableStateOf(false)
        setContent {
            MelavonmenAnTheme {
                if (appReady.value) MelaanApp() else StartupScreen()
            }
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    LibraryRepository.init(applicationContext)
                    ProgressStore.init(applicationContext)
                    TagAliasStore.init(applicationContext)
                    val settings = getSharedPreferences("melaan_settings", MODE_PRIVATE)
                    val treeUris = settings.getString(LibraryRepository.PREF_KEY_TREE_URIS, null)
                        ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?.map(Uri::parse)?.toList()
                        .orEmpty()
                        .ifEmpty { listOfNotNull(settings.getString("tree_uri", null)?.takeIf(String::isNotBlank)?.let(Uri::parse)) }
                    val parserVersion = settings.getInt(LibraryRepository.PREF_KEY_CHAPTER_PARSER_VERSION, 0)
                    val needsChapterReparse = parserVersion < LibraryRepository.CHAPTER_PARSER_VERSION
                    if (treeUris.isNotEmpty() &&
                        (LibraryRepository.comics().isEmpty() || needsChapterReparse)
                    ) {
                        LibraryRepository.scan(treeUris, preserveCoverCache = needsChapterReparse)
                        settings.edit()
                            .putInt(LibraryRepository.PREF_KEY_CHAPTER_PARSER_VERSION, LibraryRepository.CHAPTER_PARSER_VERSION)
                            .apply()
                    }
                    LibrarySnapshot.load()
                }
            }
            appReady.value = true
        }
    }

    override fun onDestroy() {
        if (isFinishing) ExhibitionData.enabled = false
        super.onDestroy()
    }
}

@Composable
private fun StartupScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text("正在载入本地书库…")
        }
    }
}
