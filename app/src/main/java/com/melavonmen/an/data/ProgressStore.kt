package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences

data class ReadingProgress(val comicId:String,val chapterIndex:Int,val pageIndex:Int,val percent:Int)

/**
 * Reading progress persistence (SharedPreferences, JSON values).
 *
 * - key = "progress.<comicId>" -> JSON {comicId,chapterIndex,pageIndex,percent}
 * - "latest_comic" -> comicId of the most recently saved entry (for the continue-reading card)
 * - Writes are idempotent overwrites; nothing is ever written into the 7z archives.
 */
object ProgressStore {

    private const val PREFS_NAME = "reading_progress"
    private const val KEY_PREFIX = "progress."
    private const val KEY_LATEST = "latest_comic"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        LibrarySnapshot.invalidate()
    }

    /** Test-only hook so JVM unit tests can run without an Android Context. */
    internal fun initWithPrefs(sharedPrefs: SharedPreferences) {
        prefs = sharedPrefs
        LibrarySnapshot.invalidate()
    }

    fun save(p: ReadingProgress) {
        val sp = prefs ?: throw IllegalStateException("ProgressStore.init(context) must be called before save")
        sp.edit()
            .putString(KEY_PREFIX + p.comicId, toJson(p))
            .putString(KEY_LATEST, p.comicId)
            .apply()
        LibrarySnapshot.invalidate()
    }

    fun load(comicId: String): ReadingProgress? {
        val raw = prefs?.getString(KEY_PREFIX + comicId, null) ?: return null
        return fromJson(raw)
    }

    fun latest(): ReadingProgress? {
        val id = prefs?.getString(KEY_LATEST, null) ?: return null
        return load(id)
    }

    fun remove(comicId: String) {
        val sp = prefs ?: return
        val edit = sp.edit().remove(KEY_PREFIX + comicId)
        if (sp.getString(KEY_LATEST, null) == comicId) edit.remove(KEY_LATEST)
        edit.apply()
        LibrarySnapshot.invalidate()
    }

    internal fun toJson(p: ReadingProgress): String =
        "{\"comicId\":\"${Json.escape(p.comicId)}\",\"chapterIndex\":${p.chapterIndex},\"pageIndex\":${p.pageIndex},\"percent\":${p.percent}}"

    internal fun fromJson(raw: String): ReadingProgress? {
        val map = try {
            Json.parse(raw)
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        val comicId = Json.asString(map["comicId"])
        if (comicId.isEmpty()) return null
        return ReadingProgress(
            comicId = comicId,
            chapterIndex = Json.asInt(map["chapterIndex"]),
            pageIndex = Json.asInt(map["pageIndex"]),
            percent = Json.asInt(map["percent"])
        )
    }
}
