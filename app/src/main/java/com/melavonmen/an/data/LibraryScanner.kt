package com.melavonmen.an.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.util.ArrayDeque

internal enum class ArchiveKind { SEVEN_Z, ZIP, RAR }

internal enum class LibraryFileKind { ARCHIVE, EPUB, PDF, TXT }

internal fun archiveKind(displayName: String): ArchiveKind? = when (displayName.substringAfterLast('.', "").lowercase()) {
    "7z" -> ArchiveKind.SEVEN_Z
    "zip", "cbz" -> ArchiveKind.ZIP
    "rar", "cbr" -> ArchiveKind.RAR
    else -> null
}

internal fun libraryFileKind(displayName: String): LibraryFileKind? = when (displayName.substringAfterLast('.', "").lowercase()) {
    "7z", "zip", "cbz", "rar", "cbr" -> LibraryFileKind.ARCHIVE
    "epub" -> LibraryFileKind.EPUB
    "pdf" -> LibraryFileKind.PDF
    "txt" -> LibraryFileKind.TXT
    else -> null
}

/** SAF tree walker. It only queries document metadata and never mutates user files. */
object LibraryScanner {

    // A provider can expose a malformed/cyclic tree; this still permits deeply nested libraries.
    private const val MAX_DEPTH = 64

    fun findArchiveUris(resolver: ContentResolver, treeUri: Uri): List<Uri> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val pending = ArrayDeque<Pair<String, Int>>()
        val visited = HashSet<String>()
        val found = LinkedHashMap<String, Uri>()
        pending.addLast(rootDocId to 0)

        while (pending.isNotEmpty()) {
            val (docId, depth) = pending.removeLast()
            if (depth > MAX_DEPTH || !visited.add(docId)) continue
            val childrenUri = if (depth == 0) {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            } else {
                runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId) }.getOrNull() ?: continue
            }
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val cursor = if (depth == 0) {
                resolver.query(childrenUri, columns, null, null, null)
                    ?: throw IOException("provider returned no cursor for tree root")
            } else runCatching {
                resolver.query(
                    childrenUri,
                    columns,
                    null,
                    null,
                    null
                )
            }.getOrNull() ?: continue

            runCatching {
                cursor.use { c ->
                    while (runCatching { c.moveToNext() }.getOrDefault(false)) {
                        val row = runCatching {
                            Triple(c.getString(0), c.getString(1), c.getString(2))
                        }.getOrNull() ?: continue
                        val id = row.first ?: continue
                        val displayName = row.second ?: continue
                        if (row.third == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) pending.addLast(id to depth + 1)
                        } else if (libraryFileKind(displayName) != null) {
                            val uri = runCatching {
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            }.getOrNull() ?: continue
                            found.putIfAbsent(uri.toString(), uri)
                        }
                    }
                }
            }
        }
        return found.values.sortedBy(Uri::toString)
    }
}
