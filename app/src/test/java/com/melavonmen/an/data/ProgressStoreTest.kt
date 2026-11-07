package com.melavonmen.an.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProgressStoreTest {

    /** In-memory SharedPreferences so the store runs on plain JVM tests. */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? MutableSet<String> ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearFirst = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                pending[key!!] = values?.let { HashSet(it) }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key!!] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                pending[key!!] = REMOVE
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFirst = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFirst) map.clear()
                for ((k, v) in pending) {
                    if (v === REMOVE) map.remove(k) else map[k] = v
                }
            }
        }

        companion object {
            private val REMOVE = Any()
        }
    }

    private lateinit var prefs: FakePrefs

    @Before
    fun setUp() {
        prefs = FakePrefs()
        ProgressStore.initWithPrefs(prefs)
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val p = ReadingProgress(comicId = "content://tree/primary%3Acomic.7z", chapterIndex = 2, pageIndex = 17, percent = 43)
        ProgressStore.save(p)
        assertEquals(p, ProgressStore.load(p.comicId))
    }

    @Test
    fun loadMissingReturnsNull() {
        assertNull(ProgressStore.load("nope"))
    }

    @Test
    fun saveIsIdempotentOverwrite() {
        val id = "comic-1"
        ProgressStore.save(ReadingProgress(id, 0, 1, 5))
        ProgressStore.save(ReadingProgress(id, 1, 20, 55))
        assertEquals(ReadingProgress(id, 1, 20, 55), ProgressStore.load(id))
    }

    @Test
    fun latestReturnsMostRecentlySaved() {
        assertNull(ProgressStore.latest())
        ProgressStore.save(ReadingProgress("a", 0, 1, 10))
        assertEquals("a", ProgressStore.latest()?.comicId)
        ProgressStore.save(ReadingProgress("b", 3, 30, 80))
        assertEquals(ReadingProgress("b", 3, 30, 80), ProgressStore.latest())
        ProgressStore.save(ReadingProgress("a", 5, 50, 99))
        assertEquals("a", ProgressStore.latest()?.comicId)
    }

    @Test
    fun specialCharactersInComicIdSurviveJson() {
        val id = "content://tree/primary%3Amy \"comic\" \\ vol.7z"
        val p = ReadingProgress(id, 1, 2, 3)
        ProgressStore.save(p)
        assertEquals(p, ProgressStore.load(id))
    }

    @Test
    fun corruptStoredJsonLoadsAsNull() {
        prefs.map["progress.broken"] = "{{{ not json"
        assertNull(ProgressStore.load("broken"))
    }

    @Test
    fun storedValueIsJsonUnderContractKey() {
        val p = ReadingProgress("c-7", 1, 2, 33)
        ProgressStore.save(p)
        val raw = prefs.map["progress.c-7"] as String
        assertEquals(
            """{"comicId":"c-7","chapterIndex":1,"pageIndex":2,"percent":33}""",
            raw
        )
        assertEquals("c-7", prefs.map["latest_comic"])
    }
}
