package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** Per-comic user tags are kept separately from archive metadata and the parsed library index. */
internal class ComicUserTagStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("comic_user_tags", Context.MODE_PRIVATE)

    fun get(comicId: String): List<String> = synchronized(preferences) {
        decode(preferences.getString(key(comicId), null))
    }

    fun add(comicId: String, tag: String): List<String> = synchronized(preferences) {
        val cleaned = tag.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 64 && cleaned.none(Char::isISOControl)) {
            "Tag must contain 1 to 64 printable characters"
        }
        val current = decode(preferences.getString(key(comicId), null))
        val result = if (current.any { it.equals(cleaned, ignoreCase = true) }) current else current + cleaned
        if (result != current) {
            preferences.edit().putString(key(comicId), encode(result)).apply()
        }
        result
    }

    fun removeTag(comicId: String, tag: String): List<String> = synchronized(preferences) {
        val current = decode(preferences.getString(key(comicId), null))
        val result = current.filterNot { it.equals(tag.trim(), ignoreCase = true) }
        if (result != current) preferences.edit().putString(key(comicId), encode(result)).apply()
        result
    }

    fun remove(comicId: String) = synchronized(preferences) {
        preferences.edit().remove(key(comicId)).apply()
    }

    private fun key(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return "comic_${digest.joinToString("") { "%02x".format(it) }}"
    }

    private fun encode(tags: List<String>): String = tags.joinToString(",", "[", "]") {
        "\"${Json.escape(it)}\""
    }

    private fun decode(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return runCatching {
            (Json.parse(raw) as? List<*>)?.map(Json::asString).orEmpty()
        }.getOrDefault(emptyList())
    }
}
