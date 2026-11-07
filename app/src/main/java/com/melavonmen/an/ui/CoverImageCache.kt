package com.melavonmen.an.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.melavonmen.an.data.ExhibitionData
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
    if (comicId.startsWith("demo:")) {
        return remember(comicId, targetWidth) { exhibitionCover(comicId, targetWidth) }
    }
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

private fun exhibitionCover(comicId: String, targetWidth: Int): Bitmap {
    val width = targetWidth.coerceAtLeast(1)
    val height = (width * 1.42f).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val colors = when (comicId) {
        "demo:moonlit" -> intArrayOf(0xFF342D65.toInt(), 0xFFEB9E9C.toInt())
        "demo:cloud" -> intArrayOf(0xFFED9A62.toInt(), 0xFFFFD792.toInt())
        "demo:robot" -> intArrayOf(0xFF244B68.toInt(), 0xFF8CC4D0.toInt())
        "demo:forest" -> intArrayOf(0xFF31584B.toInt(), 0xFFB5C98A.toInt())
        "demo:bluehour" -> intArrayOf(0xFF324D84.toInt(), 0xFFB3A6CA.toInt())
        else -> intArrayOf(0xFF725078.toInt(), 0xFFE5B78A.toInt())
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null
    paint.color = 0x55FFFFFF
    canvas.drawCircle(width * 0.72f, height * 0.34f, width * 0.23f, paint)
    paint.color = 0x33101828
    canvas.drawOval(RectF(-width * 0.15f, height * 0.52f, width * 1.15f, height * 1.12f), paint)
    val title = ExhibitionData.snapshot.comics.first { it.id == comicId }.title
    paint.color = android.graphics.Color.WHITE
    paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    paint.textSize = width * 0.105f
    val lines = title.chunked(7).take(3)
    lines.forEachIndexed { index, line ->
        canvas.drawText(line, width * 0.09f, height * (0.76f + index * 0.07f), paint)
    }
    paint.textSize = width * 0.04f
    paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    canvas.drawText("MELAAN · SAMPLE", width * 0.09f, height * 0.94f, paint)
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
