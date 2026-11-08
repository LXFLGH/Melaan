package com.melavonmen.an.data

import java.util.Locale

data class DocumentSearchHit(
    val chapterIndex: Int,
    val progressUnits: Int,
    val excerpt: String,
)

/** Searches bounded TXT chapter spans and EPUB XHTML in reading order without an on-disk index. */
object DocumentSearch {
    suspend fun find(entry: ComicEntry, query: String, maxResults: Int = 80): List<DocumentSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        require(mediaKind(entry.format) == MediaKind.TXT || mediaKind(entry.format) == MediaKind.EPUB)
        val hits = ArrayList<DocumentSearchHit>()
        for (chapter in entry.chapters.indices) {
            val raw = if (isTxtFormat(entry.format)) {
                LibraryRepository.readTextChapter(entry.id, chapter)
            } else {
                val xhtml = String(LibraryRepository.readEpubChapter(entry.id, chapter), Charsets.UTF_8)
                xhtml.replace(Regex("(?is)<script\\b[^>]*>.*?</script>|<style\\b[^>]*>.*?</style>"), "")
                    .replace(Regex("<[^>]*>"), " ")
                    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&amp;", "&")
            }
            if (raw.isEmpty()) continue
            var start = 0
            var matchesInChapter = 0
            while (start < raw.length && hits.size < maxResults && matchesInChapter < 8) {
                val at = raw.indexOf(needle, startIndex = start, ignoreCase = true)
                if (at < 0) break
                val from = (at - 36).coerceAtLeast(0)
                val to = (at + needle.length + 72).coerceAtMost(raw.length)
                hits += DocumentSearchHit(
                    chapterIndex = chapter,
                    progressUnits = ((at.toLong() * DocumentProgress.SCROLL_UNITS) / raw.length).toInt(),
                    excerpt = raw.substring(from, to).replace(Regex("\\s+"), " ").trim(),
                )
                matchesInChapter++
                start = at + needle.length
            }
            if (hits.size >= maxResults) break
        }
        return hits
    }
}
