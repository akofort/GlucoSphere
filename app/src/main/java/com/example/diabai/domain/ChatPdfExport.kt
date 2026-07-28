package com.example.diabai.domain

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A4 at 72dpi (PdfDocument works in points, not pixels) -- the same page size
 * `android.graphics.pdf.PdfDocument` examples and most viewers assume by default. */
private const val PDF_PAGE_WIDTH = 595
private const val PDF_PAGE_HEIGHT = 842
private const val PDF_MARGIN = 40

private val pdfTimestampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Strips Markdown syntax the same way [com.example.diabai.ui.stripMarkdownForSpeech] does for
 * TTS, but -- unlike that one -- keeps line/paragraph breaks intact instead of collapsing them
 * into a single spoken sentence, since a PDF is read, not heard. */
private fun stripMarkdownForPdf(text: String): String = text
    .replace(Regex("(?s)<think>.*?</think>"), " ")
    .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("[*`_]"), "")
    .replace(Regex("^\\|?[-:| ]+\\|?$", RegexOption.MULTILINE), " ")
    .replace('|', ' ')
    .replace(Regex("^[-•]\\s*", RegexOption.MULTILINE), "• ")
    .replace(Regex("[ \\t]{2,}"), " ")
    .trim()

/**
 * Renders [title] + [bodyText] (a chat answer's raw Markdown) into a simple, paginated one-column
 * PDF -- "Chatantworten als PDF teilen". No Markdown formatting survives (see [stripMarkdownForPdf]),
 * only plain wrapped text; this is meant for sharing a readable answer outside the app (e.g. to a
 * doctor by mail), not a faithful visual export of the chat bubble.
 *
 * Written to `cacheDir/shared_pdfs/` (never `filesDir`) -- these are throwaway, regeneratable
 * exports, not app data worth backing up or keeping around; [shareChatAnswerAsPdf] hands the
 * result straight to the share sheet right after this returns.
 */
fun buildChatAnswerPdf(context: Context, title: String, bodyText: String, generatedAtMillis: Long = System.currentTimeMillis()): File {
    val contentWidth = PDF_PAGE_WIDTH - PDF_MARGIN * 2

    val titlePaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 16f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val metaPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 9f
        isAntiAlias = true
    }
    val bodyPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 11f
        isAntiAlias = true
    }

    fun layoutFor(text: String, paint: TextPaint) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.15f)
            .build()

    val titleLayout = layoutFor(title, titlePaint)
    val metaLayout = layoutFor("GlucoSphere – ${pdfTimestampFormat.format(Instant.ofEpochMilli(generatedAtMillis))}", metaPaint)
    val headerHeight = titleLayout.height + 6 + metaLayout.height + 16

    val plainBody = stripMarkdownForPdf(bodyText).ifBlank { " " }
    val bodyLayout = layoutFor(plainBody, bodyPaint)
    val totalLines = bodyLayout.lineCount

    val document = PdfDocument()
    var pageNumber = 1
    var startLine = 0
    do {
        val pageInfo = PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageNumber).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        var contentTop = PDF_MARGIN.toFloat()

        if (pageNumber == 1) {
            canvas.save()
            canvas.translate(PDF_MARGIN.toFloat(), contentTop)
            titleLayout.draw(canvas)
            canvas.translate(0f, titleLayout.height + 6f)
            metaLayout.draw(canvas)
            canvas.restore()
            contentTop += headerHeight
        }

        val availableHeight = PDF_PAGE_HEIGHT - PDF_MARGIN - contentTop
        val startTop = bodyLayout.getLineTop(startLine)
        var endLine = startLine
        while (endLine < totalLines && bodyLayout.getLineBottom(endLine) - startTop <= availableHeight) {
            endLine++
        }
        // A single line taller than the whole remaining page (shouldn't happen at this font size,
        // but would otherwise spin forever never advancing past `startLine`) still forces progress.
        if (endLine == startLine) endLine = (startLine + 1).coerceAtMost(totalLines)

        canvas.save()
        canvas.translate(PDF_MARGIN.toFloat(), contentTop - startTop)
        canvas.clipRect(0, startTop, contentWidth, bodyLayout.getLineBottom(endLine - 1))
        bodyLayout.draw(canvas)
        canvas.restore()

        document.finishPage(page)
        startLine = endLine
        pageNumber++
    } while (startLine < totalLines)

    val outputDir = File(context.cacheDir, "shared_pdfs").apply { mkdirs() }
    val outputFile = File(outputDir, "glucosphere-antwort-$generatedAtMillis.pdf")
    FileOutputStream(outputFile).use { document.writeTo(it) }
    document.close()
    return outputFile
}

/** Builds the PDF (see [buildChatAnswerPdf]) and immediately hands it to the system share sheet
 * via a [FileProvider] content URI -- same "let the user pick the target app" pattern as every
 * other `ACTION_SEND` in this app, just `application/pdf` instead of `text/plain`. */
fun shareChatAnswerAsPdf(context: Context, title: String, bodyText: String, chooserTitle: String) {
    val file = buildChatAnswerPdf(context, title, bodyText)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}
