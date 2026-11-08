package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** User-owned metadata overlay; the underlying SAF document/ZIP is never modified. */
data class UserBookMetadata(
    val title: String = "",
    val authors: String = "",
    val series: String = "",
)

object UserBookMetadataStore {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("melaan_book_overrides", Context.MODE_PRIVATE)
    }

    fun load(id: String): UserBookMetadata {
        val json = prefs?.getString(id, null) ?: return UserBookMetadata()
        return runCatching {
            val obj = JSONObject(json)
            UserBookMetadata(
                title = obj.optString("title").trim(),
                authors = obj.optString("authors").trim(),
                series = obj.optString("series").trim(),
            )
        }.getOrDefault(UserBookMetadata())
    }

    fun save(id: String, data: UserBookMetadata) {
        require(id.isNotBlank())
        val obj = JSONObject()
            .put("title", data.title.trim())
            .put("authors", data.authors.trim())
            .put("series", data.series.trim())
        prefs?.edit()?.putString(id, obj.toString())?.apply()
        LibrarySnapshot.invalidate()
    }

    fun remove(id: String) {
        prefs?.edit()?.remove(id)?.apply()
        LibrarySnapshot.invalidate()
    }

    fun apply(entry: ComicEntry): ComicEntry {
        val meta = load(entry.id)
        if (meta.title.isEmpty() && meta.authors.isEmpty()) return entry
        return entry.copy(
            title = meta.title.ifBlank { entry.title },
            authors = meta.authors.takeIf(String::isNotBlank)
                ?.split('、', ';', ',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: entry.authors,
        )
    }
}
