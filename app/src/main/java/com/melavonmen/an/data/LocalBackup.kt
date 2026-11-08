package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences

/** Versioned, merge-only backup of app-owned reader and shelf data. */
object LocalBackup {
    private const val VERSION = 1
    private val settingKeys = setOf(
        "dark_reading", "keep_screen_on", "reader_scroll_mode", "reader_rtl",
        "reader_fit_width", "reader_seamless", "reader_double_page", "reader.auto_next_chapter"
    )
    private val storeNames = listOf("reading_progress", "library_collections", "comic_user_tags")
    private val optionalStoreNames = listOf("melaan_book_overrides", "melaan_document_reader")

    fun export(context: Context): String {
        BookmarkStore.init(context)
        val stores = linkedMapOf<String, Any?>()
        (storeNames + optionalStoreNames + "melaan_settings").forEach { name ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val selected = if (name == "melaan_settings") all.filterKeys(::isBackupSettingKey) else all
            stores[name] = selected.mapValues { (_, value) -> if (value is Set<*>) value.filterIsInstance<String>() else value }
        }
        stores["tag_aliases"] = TagAliasStore.all()
        val marks = runCatching { BookmarkStore.snapshot().map { mark ->
            mapOf("id" to mark.id, "comicId" to mark.comicId, "chapterIndex" to mark.chapterIndex,
                "pageIndex" to mark.pageIndex, "title" to mark.title, "createdAtMillis" to mark.createdAtMillis)
        } }.getOrDefault(emptyList())
        return encode(mapOf("format" to "melaan-local-backup", "version" to VERSION, "stores" to stores, "bookmarks" to marks))
    }

    /** Parse and validate the whole payload before applying any preference edits. */
    fun import(context: Context, raw: String) {
        BookmarkStore.init(context)
        val root = Json.parse(raw) as? Map<*, *> ?: error("Invalid backup")
        require(root["format"] == "melaan-local-backup" && Json.asInt(root["version"]) == VERSION) { "Unsupported backup" }
        val rawStores = root["stores"] as? Map<*, *> ?: error("Missing stores")
        val updates = linkedMapOf<String, Map<String, Any?>>()
        for (name in storeNames + optionalStoreNames + "melaan_settings") {
            val values = rawStores[name] as? Map<*, *> ?: if (name in optionalStoreNames) continue else error("Missing $name")
            val clean = linkedMapOf<String, Any?>()
            values.forEach { (key, value) ->
                require(key is String) { "Invalid preference key" }
                if (name == "melaan_settings") require(isBackupSettingKey(key)) { "Unexpected setting" }
                require(value == null || value is String || value is Boolean || value is Int || value is Long || value is Float || value is Double || value is List<*>) { "Invalid preference value" }
                if (value is List<*>) require(value.all { it is String }) { "Invalid string set" }
                clean[key] = value
            }
            updates[name] = clean
        }
        val bookmarkRows = root["bookmarks"] as? List<*> ?: error("Missing bookmarks")
        val aliasRows = (rawStores["tag_aliases"] as? Map<*, *>)?.map { (canonical, variants) ->
            require(canonical is String && variants is List<*> && variants.all { it is String }) { "Invalid aliases" }
            canonical to variants.filterIsInstance<String>()
        }?.toMap() ?: error("Missing aliases")
        val marks = bookmarkRows.map { value ->
            val row = value as? Map<*, *> ?: error("Invalid bookmark")
            val id = Json.asString(row["id"])
            val comicId = Json.asString(row["comicId"])
            require(id.isNotBlank() && comicId.isNotBlank())
            ReaderBookmark(id, comicId, Json.asInt(row["chapterIndex"]).coerceAtLeast(0), Json.asInt(row["pageIndex"]).coerceAtLeast(0),
                Json.asString(row["title"]), (row["createdAtMillis"] as? Number)?.toLong() ?: 0L)
        }
        updates.forEach { (name, values) -> mergePrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        BookmarkStore.merge(marks)
        TagAliasStore.init(context)
        val currentAliases = TagAliasStore.all()
        mergeAliasGroups(currentAliases, aliasRows).forEach { (canonical, variants) ->
            if (currentAliases[canonical] != variants) TagAliasStore.merge(canonical, variants.toSet() + canonical)
        }
        LibrarySnapshot.invalidate()
    }

    private fun mergePrefs(prefs: SharedPreferences, values: Map<String, Any?>) {
        val edit = prefs.edit()
        values.forEach { (key, value) ->
            if (prefs.contains(key)) return@forEach
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Double -> edit.putLong(key, value.toBits())
                is List<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> edit.remove(key)
            }
        }
        edit.apply()
    }

    internal fun isBackupSettingKey(key: String): Boolean = key in settingKeys || key.startsWith("reader.")

    internal fun mergeAliasGroups(
        existing: Map<String, List<String>>,
        imported: Map<String, List<String>>
    ): Map<String, List<String>> {
        val result = existing.mapValuesTo(linkedMapOf()) { it.value.toList() }
        val owner = HashMap<String, String>()
        result.forEach { (canonical, variants) -> (variants + canonical).forEach { owner[it] = canonical } }
        imported.forEach { (canonical, variants) ->
            val all = (variants + canonical).distinct()
            if (all.none { owner[it] != null && owner[it] != canonical }) {
                result[canonical] = (result[canonical].orEmpty() + variants).distinct().filterNot { it == canonical }
                all.forEach { owner[it] = canonical }
            }
        }
        return result
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${Json.escape(value)}\""
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { encode(it.key.toString()) + ":" + encode(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> error("Unsupported backup value")
    }
}
