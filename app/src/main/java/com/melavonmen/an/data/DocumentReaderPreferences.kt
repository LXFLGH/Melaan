package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences

/** Local-only document settings. Defaults favour continuous reading without changing comic preferences. */
data class DocumentReaderPreferences(
    val seamless: Boolean = true,
    val fontSizeSp: Int = 18,
    val lineHeightSp: Int = 30,
    val horizontalMarginDp: Int = 20,
    val nightMode: Boolean = false,
    val pdfFitWidth: Boolean = false,
    val pdfTwoPage: Boolean = false,
) {
    fun normalized() = copy(
        fontSizeSp = fontSizeSp.coerceIn(12, 36),
        lineHeightSp = lineHeightSp.coerceIn(fontSizeSp.coerceIn(12, 36) + 2, 64),
        horizontalMarginDp = horizontalMarginDp.coerceIn(0, 48),
    )
}

object DocumentReaderPreferencesStore {
    private const val NAME = "melaan_document_reader"

    fun load(context: Context): DocumentReaderPreferences {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return DocumentReaderPreferences(
            seamless = p.getBoolean("seamless", true),
            fontSizeSp = p.getInt("font_sp", 18),
            lineHeightSp = p.getInt("line_sp", 30),
            horizontalMarginDp = p.getInt("margin_dp", 20),
            nightMode = p.getBoolean("night", false),
            pdfFitWidth = p.getBoolean("pdf_fit_width", false),
            pdfTwoPage = p.getBoolean("pdf_two_page", false),
        ).normalized()
    }

    fun save(context: Context, value: DocumentReaderPreferences) {
        val v = value.normalized()
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("seamless", v.seamless)
            .putInt("font_sp", v.fontSizeSp)
            .putInt("line_sp", v.lineHeightSp)
            .putInt("margin_dp", v.horizontalMarginDp)
            .putBoolean("night", v.nightMode)
            .putBoolean("pdf_fit_width", v.pdfFitWidth)
            .putBoolean("pdf_two_page", v.pdfTwoPage)
            .apply()
    }
}
