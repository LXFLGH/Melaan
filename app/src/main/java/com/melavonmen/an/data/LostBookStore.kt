package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Keeps metadata about disappeared SAF files so users can explicitly reconnect reading data. */
data class LostBookInfo(
    val id: String,
    val title: String,
    val format: String,
    val sizeBytes: Long,
)

object LostBookStore {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("melaan_lost_books", Context.MODE_PRIVATE)
    }

    fun remember(entry: ComicEntry) {
        prefs?.edit()?.putString(entry.id, JSONObject()
            .put("title", entry.title)
            .put("format", entry.format)
            .put("size", entry.sizeBytes)
            .toString()
        )?.apply()
    }

    fun forget(id: String) {
        prefs?.edit()?.remove(id)?.apply()
    }

    fun list(): List<LostBookInfo> = prefs?.all.orEmpty().mapNotNull { (id, value) ->
        val obj = runCatching { JSONObject(value as? String ?: return@mapNotNull null) }.getOrNull()
            ?: return@mapNotNull null
        LostBookInfo(id, obj.optString("title"), obj.optString("format"), obj.optLong("size"))
    }.sortedBy(LostBookInfo::title)
}
