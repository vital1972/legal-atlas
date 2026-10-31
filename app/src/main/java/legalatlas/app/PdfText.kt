package legalatlas.app

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream

/** Текстовый слой PDF. У сканов его нет: тогда вернётся почти пустая строка. */
object PdfText {
    fun extract(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            PDFTextStripper().apply { sortByPosition = true }.getText(doc)
        }.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
