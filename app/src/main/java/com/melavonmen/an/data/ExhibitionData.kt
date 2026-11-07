package com.melavonmen.an.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/** In-memory-only demo catalog substituted under the production app's normal screens. */
object ExhibitionData {
    @Volatile var enabled = false

    private val comics = listOf(
        ComicEntry("demo:moonlit", "", "月下邮差", listOf("青禾"), listOf("幻想", "治愈"), "7z", true, chapters("第1话", 4), null, 24L shl 20),
        ComicEntry("demo:cloud", "", "云端食堂", listOf("白屿"), listOf("日常", "美食"), "ZIP", true, chapters("第1话", 8), null, 68L shl 20),
        ComicEntry("demo:robot", "", "机器人也会做梦吗", listOf("南风"), listOf("科幻", "短篇"), "CBZ", false, chapters("序章", 1), null, 12L shl 20),
        ComicEntry("demo:forest", "", "森林里的小小研究所", listOf("木木"), listOf("冒险", "治愈"), "7z", true, chapters("第1话", 5), null, 31L shl 20),
        ComicEntry("demo:bluehour", "", "蓝色时刻来信", listOf("星野"), listOf("校园", "日常"), "ZIP", true, chapters("第1话", 6), null, 45L shl 20),
        ComicEntry("demo:orbit", "", "环轨旅行手册", listOf("千里"), listOf("科幻", "冒险"), "7z", false, chapters("终点站", 1), null, 19L shl 20),
    )
    private var progress = ReadingProgress("demo:moonlit", 0, 3, 32)
    private val favorites = mutableSetOf("demo:moonlit", "demo:cloud")
    private val bookLists = mutableListOf(ComicBookList("周末待读", setOf("demo:forest", "demo:bluehour")))

    val snapshot: LibrarySnapshotData
        get() = LibrarySnapshotData(
            comics = comics,
            progressById = comics.associate { comic -> comic.id to if (comic.id == progress.comicId) progress else null },
            latest = progress,
        )

    fun comic(id: String): ComicEntry? = if (id.startsWith("demo:")) comics.firstOrNull { it.id == id } else null

    fun progress(id: String): ReadingProgress? = progress.takeIf { it.comicId == id }

    fun saveProgress(value: ReadingProgress) {
        if (comic(value.comicId) != null) progress = value
    }

    fun favorites(): Set<String> = favorites.toSet()

    fun setFavorite(id: String, favorite: Boolean) {
        if (comic(id) == null) return
        if (favorite) favorites.add(id) else favorites.remove(id)
    }

    fun bookLists(): List<ComicBookList> = bookLists.toList()

    fun createBookList(name: String): ComicBookList = ComicBookList(name, emptySet()).also(bookLists::add)

    fun removeBookList(name: String) { bookLists.removeAll { it.name == name } }

    fun setBookListMembership(name: String, id: String, included: Boolean) {
        val index = bookLists.indexOfFirst { it.name == name }
        if (index < 0 || comic(id) == null) return
        val list = bookLists[index]
        bookLists[index] = list.copy(comicIds = list.comicIds.toMutableSet().apply {
            if (included) add(id) else remove(id)
        })
    }

    fun removeComic(id: String) {
        favorites.remove(id)
        bookLists.indices.forEach { index ->
            val list = bookLists[index]
            bookLists[index] = list.copy(comicIds = list.comicIds - id)
        }
    }

    fun pageBytes(id: String, chapterIndex: Int, pageIndex: Int): ByteArray? {
        val comic = comic(id) ?: return null
        if (comic.chapters.getOrNull(chapterIndex)?.pageEntryPaths?.getOrNull(pageIndex) == null) return null
        val bitmap = Bitmap.createBitmap(900, 1300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF8F4EC.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF554D67.toInt() }
        canvas.drawCircle(450f, 400f, 175f, paint)
        paint.color = 0xFFFFB7A4.toInt()
        canvas.drawCircle(450f, 400f, 125f, paint)
        paint.color = 0xFF302C3B.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 58f
        canvas.drawText(comic.title, 450f, 850f, paint)
        paint.textSize = 34f
        canvas.drawText("样例画面 · 第 ${pageIndex + 1} 页", 450f, 930f, paint)
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private fun chapters(firstName: String, count: Int) = listOf(
        ChapterRef(firstName, (1..12).map { "demo-page-$it.webp" })
    ) + (2..count).map { chapter -> ChapterRef("第${chapter}话", (1..12).map { "demo-page-$it.webp" }) }
}
