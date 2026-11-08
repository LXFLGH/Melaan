package com.melavonmen.an.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlin.math.roundToInt

/** Owns the SAF descriptor and system PDF renderer for one open book. */
class PdfDocumentSession internal constructor(
    private val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

internal object PdfPages {
    /** Native PDF text search is available on Android 15+ for searchable PDFs. */
    fun search(session: PdfDocumentSession, query: String, limit: Int = 80): List<Int> {
        if (android.os.Build.VERSION.SDK_INT < 35 || query.isBlank()) return emptyList()
        val matches = ArrayList<Int>()
        synchronized(session) {
            for (index in 0 until session.pageCount) {
                val page = session.renderer.openPage(index)
                try {
                    if (page.searchText(query).isNotEmpty()) matches += index
                } finally {
                    page.close()
                }
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    fun renderPage(session: PdfDocumentSession, index: Int, targetWidth: Int = 1080): Bitmap? {
        if (index < 0 || index >= session.pageCount) return null
        val page = session.renderer.openPage(index)
        try {
            val scale = if (page.width > 0) targetWidth.toFloat() / page.width else 1f
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    fun coverBytes(session: PdfDocumentSession, targetWidth: Int = 480): ByteArray? {
        val bitmap = renderPage(session, 0, targetWidth) ?: return null
        val output = java.io.ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
    }
}
