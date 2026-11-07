package com.melavonmen.an.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.melavonmen.an.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_COVER_CACHE_BYTES = 32 * 1024 * 1024
internal val R18_COVER_MASK_COLOR = androidx.compose.ui.graphics.Color(0xFF808080)

/** Shared by all tab/detail covers. Reader page bitmaps deliberately use their own bounded cache. */
internal object CoverImageCache {
    const val KEY_R18_COVER_BLOCKED = "r18_cover_blocked"
    private val r18CoversBlockedState = mutableStateOf(false)
    private val maskingLock = Any()
    val r18CoversBlocked: Boolean get() = r18CoversBlockedState.value

    private val bitmaps = object : LruCache<String, Bitmap>(MAX_COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val loadLocks = Array(16) { Mutex() }

    fun get(comicId: String, targetWidth: Int): Bitmap? = bitmaps.get(key(comicId, targetWidth))

    suspend fun load(comicId: String, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (r18CoversBlocked) return@withContext null
        val cacheKey = key(comicId, targetWidth)
        bitmaps.get(cacheKey)?.let { return@withContext it }
        val mutex = loadLocks[(cacheKey.hashCode() and Int.MAX_VALUE) % loadLocks.size]
        mutex.withLock {
            if (r18CoversBlocked) return@withLock null
            bitmaps.get(cacheKey)?.let { return@withLock it }
            val bytes = LibraryRepository.readCover(comicId) ?: return@withLock null
            val decoded = decodeSampledCover(bytes, targetWidth) ?: return@withLock null
            val canCache = synchronized(maskingLock) {
                if (r18CoversBlocked) false else {
                    bitmaps.put(cacheKey, decoded)
                    true
                }
            }
            if (!canCache) return@withLock null
            decoded
        }
    }

    fun remove(comicId: String) {
        val prefix = "$comicId@"
        bitmaps.snapshot().keys.filter { it.startsWith(prefix) }.forEach(bitmaps::remove)
    }

    fun setR18CoversBlocked(blocked: Boolean) {
        synchronized(maskingLock) {
            r18CoversBlockedState.value = blocked
            if (blocked) clear()
        }
    }

    fun clear() {
        bitmaps.evictAll()
    }

    private fun key(comicId: String, targetWidth: Int) = "$comicId@$targetWidth"
}

@Composable
internal fun rememberCoverBitmap(comicId: String, targetWidth: Int): Bitmap? {
    val blocked = CoverImageCache.r18CoversBlocked
    val bitmap by produceState<Bitmap?>(
        initialValue = if (blocked) null else CoverImageCache.get(comicId, targetWidth),
        comicId,
        targetWidth,
        blocked,
    ) {
        value = if (blocked) null else CoverImageCache.load(comicId, targetWidth)
    }
    return bitmap
}

private fun decodeSampledCover(bytes: ByteArray, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
