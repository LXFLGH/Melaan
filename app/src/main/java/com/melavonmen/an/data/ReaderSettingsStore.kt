package com.melavonmen.an.data

import android.content.SharedPreferences

/** Per-comic overrides; absent values inherit the global reader preference. */
data class ReaderOverrides(
    val scrollMode: Boolean? = null,
    val rtl: Boolean? = null,
    val fitWidth: Boolean? = null,
    val doublePage: Boolean? = null,
)

object ReaderSettingsStore {
    private const val KEY_AUTO_NEXT = "reader.auto_next_chapter"
    private val exhibitionOverrides = mutableMapOf<String, ReaderOverrides>()
    private var exhibitionAutoNext = false

    fun loadAutoNext(prefs: SharedPreferences): Boolean = if (ExhibitionData.enabled) exhibitionAutoNext else prefs.getBoolean(KEY_AUTO_NEXT, false)

    fun saveAutoNext(prefs: SharedPreferences, enabled: Boolean) {
        if (ExhibitionData.enabled) {
            exhibitionAutoNext = enabled
            return
        }
        prefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
    }

    private fun key(id: String, field: String) = "reader.$id.$field"

    fun load(prefs: SharedPreferences, id: String): ReaderOverrides {
        if (ExhibitionData.enabled) return exhibitionOverrides[id] ?: ReaderOverrides()
        return ReaderOverrides(
        scrollMode = prefs.getBooleanOrNull(key(id, "scroll")),
        rtl = prefs.getBooleanOrNull(key(id, "rtl")),
        fitWidth = prefs.getBooleanOrNull(key(id, "fit")),
        doublePage = prefs.getBooleanOrNull(key(id, "double")),
    )
    }

    fun save(prefs: SharedPreferences, id: String, overrides: ReaderOverrides) {
        if (ExhibitionData.enabled) {
            exhibitionOverrides[id] = overrides
            return
        }
        val edit = prefs.edit()
        fun put(field: String, value: Boolean?) {
            val key = key(id, field)
            if (value == null) edit.remove(key) else edit.putBoolean(key, value)
        }
        put("scroll", overrides.scrollMode)
        put("rtl", overrides.rtl)
        put("fit", overrides.fitWidth)
        put("double", overrides.doublePage)
        edit.apply()
    }

    private fun SharedPreferences.getBooleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null
}
