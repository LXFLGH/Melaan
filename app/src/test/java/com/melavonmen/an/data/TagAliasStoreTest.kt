package com.melavonmen.an.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TagAliasStoreTest {

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
            private var clearFlag = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { pending[key!!] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in pending) if (v == null) map.remove(k) else map[k] = v
            }
        }
    }

    private fun fresh(): SharedPreferences = FakePrefs().also { TagAliasStore.initWithPrefs(it) }

    @Test
    fun mergeMapsVariantsToCanonical() {
        fresh()
        TagAliasStore.merge("東方Project", setOf("東方Project", "东方Project"))
        assertEquals("東方Project", TagAliasStore.canonicalOf("东方Project"))
        assertEquals("東方Project", TagAliasStore.canonicalOf("東方Project"))
    }

    @Test
    fun canonicalOfUnrelatedReturnsSelf() {
        fresh()
        assertEquals("科幻", TagAliasStore.canonicalOf("科幻"))
    }

    @Test
    fun mergeRejectsCanonicalOutsideVariants() {
        fresh()
        assertThrows(IllegalArgumentException::class.java) {
            TagAliasStore.merge("東方Project", setOf("东方Project"))
        }
    }

    @Test
    fun undoRestoresSelfMapping() {
        fresh()
        TagAliasStore.merge("Miku", setOf("Miku", "miku"))
        TagAliasStore.undo("Miku")
        assertEquals("miku", TagAliasStore.canonicalOf("miku"))
        assertEquals("Miku", TagAliasStore.canonicalOf("Miku"))
    }

    @Test
    fun mergeIsSoleWriteEntryAndPersists() {
        val sp = fresh()
        TagAliasStore.merge("中文", setOf("中文", "中文 "))
        // 重新从同一 prefs 初始化：持久化必须生效
        TagAliasStore.initWithPrefs(sp)
        assertEquals("中文", TagAliasStore.canonicalOf("中文 "))
    }

    @Test
    fun mergingAbsorbsOldCanonicalGroup() {
        fresh()
        TagAliasStore.merge("B", setOf("B", "D"))
        TagAliasStore.merge("A", setOf("A", "B", "C"))
        // B 曾是 D 的 canonical，合并进 A 后 D 跟随
        assertEquals("A", TagAliasStore.canonicalOf("D"))
        assertEquals("A", TagAliasStore.canonicalOf("B"))
    }

    @Test
    fun allReturnsGroups() {
        fresh()
        TagAliasStore.merge("X", setOf("X", "Y"))
        assertEquals(mapOf("X" to listOf("Y")), TagAliasStore.all())
    }

    @Test
    fun emptyStoreSerializesCleanly() {
        fresh()
        assertTrue(TagAliasStore.all().isEmpty())
    }
}
