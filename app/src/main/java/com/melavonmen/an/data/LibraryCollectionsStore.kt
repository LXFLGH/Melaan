package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences

/** Latest saved progress and timestamp for one comic. */
data class ReadingHistoryEntry(val progress: ReadingProgress, val timestampMillis: Long)
data class ComicBookList(val name: String, val comicIds: Set<String>)

/** App-local favorites and recent reading positions; archive metadata is never changed. */
class LibraryCollectionsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("library_collections", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = if (ExhibitionData.enabled) ExhibitionData.favorites() else prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()

    fun setFavorite(comicId: String, favorite: Boolean) {
        if (ExhibitionData.enabled) {
            ExhibitionData.setFavorite(comicId, favorite)
            return
        }
        val next = favorites().toMutableSet().apply {
            if (favorite) add(comicId) else remove(comicId)
        }
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
    }

    fun bookLists(): List<ComicBookList> = if (ExhibitionData.enabled) ExhibitionData.bookLists() else prefs.getString(KEY_BOOK_LISTS, null)?.let { raw ->
        runCatching { (Json.parse(raw) as? List<*>)?.mapNotNull { value ->
            val row = value as? Map<*, *> ?: return@mapNotNull null
            val name = Json.asString(row["name"]).trim()
            if (name.isEmpty()) null else ComicBookList(name, (row["comicIds"] as? List<*>)?.map(Json::asString)?.toSet().orEmpty())
        } }.getOrNull()
    }.orEmpty()

    fun createBookList(name: String): ComicBookList {
        if (ExhibitionData.enabled) return ExhibitionData.createBookList(name)
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 64) { "List name must contain 1 to 64 characters" }
        val lists = bookLists()
        require(lists.none { it.name.equals(cleaned, ignoreCase = true) }) { "List already exists" }
        val list = ComicBookList(cleaned, emptySet())
        saveBookLists(lists + list)
        return list
    }

    fun removeBookList(name: String) {
        if (ExhibitionData.enabled) ExhibitionData.removeBookList(name) else saveBookLists(bookLists().filterNot { it.name == name })
    }

    fun setBookListMembership(name: String, comicId: String, included: Boolean) {
        if (ExhibitionData.enabled) {
            ExhibitionData.setBookListMembership(name, comicId, included)
            return
        }
        val lists = bookLists()
        require(lists.any { it.name == name }) { "Unknown list" }
        saveBookLists(lists.map { list ->
            if (list.name != name) list else list.copy(comicIds = list.comicIds.toMutableSet().apply {
                if (included) add(comicId) else remove(comicId)
            })
        })
    }

    private fun saveBookLists(lists: List<ComicBookList>) {
        prefs.edit().putString(KEY_BOOK_LISTS, lists.joinToString(",", "[", "]") { list ->
            "{\"name\":\"${Json.escape(list.name)}\",\"comicIds\":[${list.comicIds.joinToString(",") { id -> "\"${Json.escape(id)}\"" }}]}"
        }).apply()
    }

    private fun history(): List<ReadingProgress> = prefs.getString(KEY_HISTORY, null)?.let { raw ->
        runCatching { (Json.parse(raw) as? List<*>)?.mapNotNull(::decodeProgress) }.getOrNull()
    }.orEmpty()

    fun recordProgress(progress: ReadingProgress) {
        if (ExhibitionData.enabled) {
            ExhibitionData.saveProgress(progress)
            return
        }
        val atMillis = System.currentTimeMillis()
        val old = history()
        val next = (old.filterNot { it.comicId == progress.comicId } + progress)
            // shortcut: keep only 100 comics; paginate if users need a deeper timeline.
            .takeLast(MAX_HISTORY)
        val editor = prefs.edit().putString(KEY_HISTORY, next.joinToString(",", "[", "]") {
            "{\"comicId\":\"${Json.escape(it.comicId)}\",\"chapterIndex\":${it.chapterIndex},\"pageIndex\":${it.pageIndex},\"percent\":${it.percent}}"
        }).putLong(KEY_PREFIX_TIME + progress.comicId, atMillis)
        (old.map { it.comicId }.toSet() - next.map { it.comicId }.toSet()).forEach {
            editor.remove(KEY_PREFIX_TIME + it)
        }
        editor.apply()
    }

    fun recent(limit: Int = MAX_HISTORY): List<ReadingHistoryEntry> = if (ExhibitionData.enabled) {
        ExhibitionData.snapshot.latest?.let { listOf(ReadingHistoryEntry(it, 1L)) }.orEmpty().take(limit.coerceAtLeast(0))
    } else history()
        .map { ReadingHistoryEntry(it, prefs.getLong(KEY_PREFIX_TIME + it.comicId, 0L)) }
        .sortedByDescending(ReadingHistoryEntry::timestampMillis)
        .take(limit.coerceAtLeast(0))

    fun removeComic(comicId: String) {
        if (ExhibitionData.enabled) {
            ExhibitionData.removeComic(comicId)
            return
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favorites() - comicId)
            .remove(KEY_PREFIX_TIME + comicId)
            .putString(KEY_HISTORY, history().filterNot { it.comicId == comicId }.joinToString(",", "[", "]") {
                "{\"comicId\":\"${Json.escape(it.comicId)}\",\"chapterIndex\":${it.chapterIndex},\"pageIndex\":${it.pageIndex},\"percent\":${it.percent}}"
            }).apply()
        bookLists().forEach { list -> if (comicId in list.comicIds) setBookListMembership(list.name, comicId, false) }
    }

    private fun decodeProgress(value: Any?): ReadingProgress? {
        val map = value as? Map<*, *> ?: return null
        val id = Json.asString(map["comicId"])
        if (id.isEmpty()) return null
        return ReadingProgress(id, Json.asInt(map["chapterIndex"]), Json.asInt(map["pageIndex"]), Json.asInt(map["percent"]))
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_HISTORY = "history"
        const val KEY_BOOK_LISTS = "book_lists"
        const val KEY_PREFIX_TIME = "time."
        const val MAX_HISTORY = 100
    }
}
