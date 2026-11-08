package com.melavonmen.an.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stores the parsed tag list and the minimal archive index needed to restore the shelf. */
internal class LibraryIndexStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "library_index.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE comics (uri TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, " +
                "authors TEXT NOT NULL DEFAULT '[]', tags TEXT NOT NULL, chapters TEXT NOT NULL, " +
                "size_bytes INTEGER NOT NULL, cover_entry TEXT, format TEXT NOT NULL DEFAULT '7z')"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE comics ADD COLUMN format TEXT NOT NULL DEFAULT '7z'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE comics ADD COLUMN authors TEXT NOT NULL DEFAULT '[]'")
        }
    }

    fun load(): List<ComicEntry> = readableDatabase.rawQuery(
        "SELECT uri, title, authors, tags, chapters, size_bytes, cover_entry, format FROM comics ORDER BY title COLLATE NOCASE",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                val chapters = decodeChapters(cursor.getString(4))
                add(
                    ComicEntry(
                        id = uri,
                        uri = uri,
                        title = cursor.getString(1),
                        authors = decodeStrings(cursor.getString(2)),
                        tags = decodeStrings(cursor.getString(3)),
                        format = cursor.getString(7) ?: "7z",
                        isSerial = chapters.any { it.name != "单行本" || it.nestedArchiveEntryPath != null },
                        chapters = chapters,
                        coverEntryName = cursor.getString(6),
                        sizeBytes = cursor.getLong(5)
                    )
                )
            }
        }
    }

    fun replace(entries: List<ComicEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("comics", null, null)
            entries.forEach { entry ->
                db.insertOrThrow(
                    "comics",
                    null,
                    ContentValues().apply {
                        put("uri", entry.uri)
                        put("title", entry.title)
                        put("authors", encodeStrings(entry.authors))
                        put("tags", encodeStrings(entry.tags))
                        put("chapters", LibraryIndexCodec.encodeChapters(entry.chapters))
                        put("size_bytes", entry.sizeBytes)
                        put("cover_entry", entry.coverEntryName)
                        put("format", entry.format)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun encodeStrings(values: List<String>) = values.joinToString(",", "[", "]") {
        "\"${Json.escape(it)}\""
    }

    private fun decodeStrings(raw: String): List<String> = runCatching {
        (Json.parse(raw) as? List<*>)?.map(Json::asString).orEmpty()
    }.getOrDefault(emptyList())

    private fun decodeChapters(raw: String): List<ChapterRef> = LibraryIndexCodec.decodeChapters(raw)
}

internal object LibraryIndexCodec {
    fun encodeChapters(chapters: List<ChapterRef>) = chapters.joinToString(",", "[", "]") {
        val nested = it.nestedArchiveEntryPath?.let { path -> "\"${Json.escape(path)}\"" } ?: "null"
        "{\"name\":\"${Json.escape(it.name)}\",\"pages\":${encodePagePaths(it.pageEntryPaths)},\"nestedArchive\":$nested}"
    }

    fun decodeChapters(raw: String): List<ChapterRef> = runCatching {
        (Json.parse(raw) as? List<*>)?.mapNotNull { value ->
            val chapter = value as? Map<*, *> ?: return@mapNotNull null
            ChapterRef(
                name = Json.asString(chapter["name"]),
                pageEntryPaths = (chapter["pages"] as? List<*>)?.map(Json::asString).orEmpty(),
                nestedArchiveEntryPath = Json.asString(chapter["nestedArchive"]).takeIf(String::isNotBlank)
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun encodePagePaths(paths: List<String>) = paths.joinToString(",", "[", "]") { "\"${Json.escape(it)}\"" }
}
