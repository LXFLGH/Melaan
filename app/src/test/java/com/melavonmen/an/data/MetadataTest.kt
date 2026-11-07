package com.melavonmen.an.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataTest {

    @Test
    fun parsesFullMetadata() {
        val json = """
            {
              "id": "g-123",
              "title": "Test Comic",
              "authors": ["Author A", "Author B"],
              "tags": ["tag1", "tag2"],
              "works": ["w"],
              "actors": ["actress"],
              "description": "desc",
              "publish_date": "2024-01-02",
              "update_date": "2024-02-03",
              "page_count": 120,
              "views": 9,
              "likes": 3,
              "chapters": ["第1话", "第2话"]
            }
        """.trimIndent()
        val meta = MetadataParser.parse(json)
        assertEquals(
            ComicMeta(
                id = "g-123",
                title = "Test Comic",
                authors = listOf("Author A", "Author B"),
                tags = listOf("tag1", "tag2"),
                publishDate = "2024-01-02",
                pageCount = 120,
                description = "desc"
            ),
            meta
        )
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val meta = MetadataParser.parse("""{"title":"T"}""")
        assertEquals("", meta?.id)
        assertEquals("T", meta?.title)
        assertEquals(emptyList<String>(), meta?.authors)
        assertEquals(emptyList<String>(), meta?.tags)
        assertEquals("", meta?.publishDate)
        assertEquals(0, meta?.pageCount)
        assertEquals("", meta?.description)
    }

    @Test
    fun numericIdAndPageCountAreCoerced() {
        val meta = MetadataParser.parse("""{"id":42,"page_count":"15"}""")
        assertEquals("42", meta?.id)
        assertEquals(15, meta?.pageCount)
    }

    @Test
    fun camelCaseAliasesAreAccepted() {
        val meta = MetadataParser.parse("""{"publishDate":"2020-01-01","pageCount":3}""")
        assertEquals("2020-01-01", meta?.publishDate)
        assertEquals(3, meta?.pageCount)
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(MetadataParser.parse("not json at all"))
        assertNull(MetadataParser.parse("""{"title": broken"""))
        assertNull(MetadataParser.parse("""[1,2,3]"""))
        assertNull(MetadataParser.parse(""))
    }

    @Test
    fun escapedStringsSurviveParsing() {
        val meta = MetadataParser.parse("""{"title":"a\"b\\c\nd"}""")
        assertEquals("a\"b\\c\nd", meta?.title)
    }

    @Test
    fun parsesFromByteArray() {
        val meta = MetadataParser.parse("""{"id":"x"}""".toByteArray(Charsets.UTF_8))
        assertEquals("x", meta?.id)
    }

    @Test
    fun jsonEscapeRoundTripsThroughParser() {
        val raw = Json.escape("content://tree/primary%3Acomic\"x\\y")
        val parsed = Json.parse("\"$raw\"") as String
        assertEquals("content://tree/primary%3Acomic\"x\\y", parsed)
    }
}
