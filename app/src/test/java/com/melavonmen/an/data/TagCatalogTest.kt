package com.melavonmen.an.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TagCatalogTest {

    private class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { pending[key!!] = null; return this }
            override fun clear(): SharedPreferences.Editor { pending.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                for ((k, v) in pending) if (v == null) map.remove(k) else map[k] = v
            }
        }
    }

    private fun entry(id: String, tags: List<String>) = ComicEntry(
        id = id, uri = "uri:$id", title = id, authors = emptyList(), tags = tags,
        format = "7z", isSerial = false, chapters = emptyList(), coverEntryName = null, sizeBytes = 0L,
    )

    @Before
    fun setUp() {
        TagAliasStore.initWithPrefs(FakePrefs())
        ProgressStore.initWithPrefs(FakePrefs())
    }

    @Test
    fun buildCountsOccurrencesAndEntries() {
        val entries = listOf(
            entry("a", listOf("科幻", "科幻", "热血")),
            entry("b", listOf("科幻")),
        )
        val stats = TagCatalog.build(entries).associateBy { it.tag }
        assertEquals(3, stats["科幻"]!!.count)
        assertEquals(2, stats["科幻"]!!.entryCount)
        assertEquals(1, stats["热血"]!!.count)
        assertEquals("科幻", stats["科幻"]!!.normKey)
    }

    @Test
    fun intersectionIsAndSemantics() {
        val entries = listOf(
            entry("scifi", listOf("科幻", "热血")),
            entry("mystery", listOf("科幻", "推理")),
            entry("both", listOf("科幻", "推理")),
            entry("other", listOf("恋爱")),
        )
        val r = TagCatalog.filterIntersection(entries, setOf("科幻", "推理"))
        assertEquals(listOf("mystery", "both"), r.map { it.id })
    }

    @Test
    fun intersectionSingleTag() {
        val entries = listOf(
            entry("a", listOf("科幻")), entry("b", listOf("恋爱")),
        )
        assertEquals(listOf("a"), TagCatalog.filterIntersection(entries, setOf("科幻")).map { it.id })
    }

    @Test
    fun emptyTagsMatchesAll() {
        val entries = listOf(entry("a", listOf("科幻")), entry("b", emptyList()))
        assertEquals(2, TagCatalog.filterIntersection(entries, emptySet()).size)
    }

    @Test
    fun intersectionIsAliasAware() {
        TagAliasStore.merge("东方Project", setOf("东方Project", "東方Project"))
        val entries = listOf(
            entry("t1", listOf("東方Project")),
            entry("t2", listOf("东方Project")),
            entry("t3", listOf("Miku")),
        )
        val r = TagCatalog.filterIntersection(entries, setOf("东方Project"))
        assertEquals(listOf("t1", "t2"), r.map { it.id })
    }

    @Test
    fun statusFilterByProgress() {
        val entries = listOf(entry("u", listOf("科幻")), entry("r", listOf("科幻")), entry("d", listOf("科幻")))
        ProgressStore.save(ReadingProgress("r", 1, 5, 50))
        ProgressStore.save(ReadingProgress("d", 2, 9, 100))
        val alias = TagAliasStore
        assertEquals(listOf("u"), TagCatalog.filterIntersection(entries, alias, setOf("科幻"), "未读").map { it.id })
        assertEquals(listOf("r"), TagCatalog.filterIntersection(entries, alias, setOf("科幻"), "在读").map { it.id })
        assertEquals(listOf("d"), TagCatalog.filterIntersection(entries, alias, setOf("科幻"), "已读完").map { it.id })
        assertEquals(3, TagCatalog.filterIntersection(entries, alias, setOf("科幻"), null).size)
    }

    @Test
    fun tagsAndStatusCombine() {
        val entries = listOf(
            entry("x", listOf("科幻", "推理")),
            entry("y", listOf("科幻")),
        )
        ProgressStore.save(ReadingProgress("x", 1, 1, 30))
        val r = TagCatalog.filterIntersection(entries, TagAliasStore, setOf("科幻", "推理"), "在读")
        assertEquals(listOf("x"), r.map { it.id })
    }
}
