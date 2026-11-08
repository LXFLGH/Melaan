package com.melavonmen.an.data

/** User-facing book categories; the storage field remains `ComicEntry.format`. */
enum class MediaKind(val label: String) {
    COMIC("漫画"),
    EPUB("EPUB"),
    PDF("PDF"),
    TXT("TXT"),
}

internal fun mediaKind(format: String): MediaKind = when (format.lowercase()) {
    "epub" -> MediaKind.EPUB
    "pdf" -> MediaKind.PDF
    "txt" -> MediaKind.TXT
    else -> MediaKind.COMIC
}

internal fun isDocumentFormat(format: String): Boolean = mediaKind(format) != MediaKind.COMIC

internal fun isEpubFormat(format: String): Boolean = format.equals("epub", ignoreCase = true)
internal fun isPdfFormat(format: String): Boolean = format.equals("pdf", ignoreCase = true)
internal fun isTxtFormat(format: String): Boolean = format.equals("txt", ignoreCase = true)

internal fun chapterNoun(format: String): String = when (mediaKind(format)) {
    MediaKind.COMIC -> "话"
    else -> "章"
}

internal fun formatBadgeText(format: String): String = when (format.lowercase()) {
    "7z" -> "7Z"
    "cbz" -> "CBZ"
    "cbr" -> "CBR"
    else -> format.uppercase()
}
