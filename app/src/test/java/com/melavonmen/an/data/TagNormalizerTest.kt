package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNormalizerTest {

    @Test
    fun tradSimpVariantSameKey() {
        assertEquals(TagNormalizer.normKey("東方Project"), TagNormalizer.normKey("东方Project"))
    }

    @Test
    fun fullWidthHalfWidthCaseSameKey() {
        val k = TagNormalizer.normKey("ＡＢＣ")
        assertEquals(k, TagNormalizer.normKey("ABC"))
        assertEquals(k, TagNormalizer.normKey("abc"))
    }

    @Test
    fun trailingSpaceSameKey() {
        assertEquals(TagNormalizer.normKey("中文"), TagNormalizer.normKey("中文 "))
        assertEquals(TagNormalizer.normKey("中文"), TagNormalizer.normKey(" 中文　"))
    }

    @Test
    fun distinctTagsDifferentKeys() {
        assertNotEquals(TagNormalizer.normKey("科幻"), TagNormalizer.normKey("推理"))
    }

    @Test
    fun variantGroupsRequiresTwoForms() {
        val groups = TagNormalizer.variantGroups(
            mapOf("東方Project" to 1, "东方Project" to 1, "科幻" to 3)
        )
        assertEquals(1, groups.size)
        val g = groups[0]
        assertEquals(setOf("東方Project", "东方Project"), g.variants.keys)
    }

    @Test
    fun variantGroupsEmptyForSingleForm() {
        assertTrue(TagNormalizer.variantGroups(mapOf("科幻" to 5, "Miku" to 2)).isEmpty())
    }

    @Test
    fun caseVariantGrouped() {
        val groups = TagNormalizer.variantGroups(mapOf("Miku" to 1, "miku" to 1))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].variants.size)
    }
}
