package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences

data class ReaderBookmark(
    val id: String,
    val comicId: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val title: String,
    val createdAtMillis: Long,
)

object BookmarkStore {
    private const val PREFS = "reading_bookmarks"
    private const val KEY = "items"
    private var prefs: SharedPreferences? = null
    private val exhibitionBookmarks = mutableListOf<ReaderBookmark>()

    fun init(context: Context) { prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    internal fun initWithPrefs(sharedPreferences: SharedPreferences) { prefs = sharedPreferences }

    fun add(bookmark: ReaderBookmark) {
        if (ExhibitionData.enabled) {
            exhibitionBookmarks.removeAll { it.id == bookmark.id }
            exhibitionBookmarks += bookmark
            return
        }
        val all = snapshot().filterNot { it.id == bookmark.id } + bookmark
        write(all)
    }

    fun forComic(comicId: String): List<ReaderBookmark> = snapshot()
        .filter { it.comicId == comicId }
        .sortedByDescending { it.createdAtMillis }

    fun remove(id: String) {
        if (ExhibitionData.enabled) exhibitionBookmarks.removeAll { it.id == id }
        else write(snapshot().filterNot { it.id == id })
    }

    fun removeComic(comicId: String) {
        if (ExhibitionData.enabled) exhibitionBookmarks.removeAll { it.comicId == comicId }
        else write(snapshot().filterNot { it.comicId == comicId })
    }

    fun snapshot(): List<ReaderBookmark> {
        if (ExhibitionData.enabled) return exhibitionBookmarks.toList()
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        val rows = runCatching { Json.parse(raw) as? List<*> }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val id = Json.asString(m["id"]); val comicId = Json.asString(m["comicId"])
            if (id.isBlank() || comicId.isBlank()) null else ReaderBookmark(
                id, comicId, Json.asInt(m["chapterIndex"]), Json.asInt(m["pageIndex"]),
                Json.asString(m["title"]), (m["createdAtMillis"] as? Number)?.toLong() ?: 0L
            )
        }
    }

    fun merge(bookmarks: List<ReaderBookmark>) {
        if (ExhibitionData.enabled) {
            bookmarks.filter { it.comicId.startsWith("demo:") }.forEach { exhibitionBookmarks.add(it) }
            return
        }
        val byId = snapshot().associateBy { it.id }.toMutableMap()
        bookmarks.forEach { if (it.id !in byId && it.comicId.isNotBlank()) byId[it.id] = it }
        write(byId.values.toList())
    }

    private fun write(rows: List<ReaderBookmark>) {
        val json = rows.joinToString(prefix = "[", postfix = "]") {
            "{\"id\":\"${Json.escape(it.id)}\",\"comicId\":\"${Json.escape(it.comicId)}\",\"chapterIndex\":${it.chapterIndex},\"pageIndex\":${it.pageIndex},\"title\":\"${Json.escape(it.title)}\",\"createdAtMillis\":${it.createdAtMillis}}"
        }
        prefs?.edit()?.putString(KEY, json)?.apply()
    }
}
