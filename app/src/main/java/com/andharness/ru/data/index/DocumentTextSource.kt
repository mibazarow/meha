package com.andharness.ru.data.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.andharness.ru.data.tools.documents.DocumentFormat
import com.andharness.ru.data.tools.documents.DocumentFormatDetector
import com.andharness.ru.data.tools.documents.DocumentTextExtractor
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.domain.common.Hashing
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort text extraction for the device-document indexer, off the main thread, never throwing.
 *
 * Approach per format (reusing the app's existing, well-tested document stack so nothing heavy is
 * added):
 * - `.txt` / `.md` / `.csv` / `.json` / `.xml` / `.html` — read verbatim.
 * - `.docx` / `.xlsx` / `.odt` / `.ods` / `.rtf` — the container is unpacked and its XML/text parts
 *   parsed by [DocumentTextExtractor] (no Apache POI; `.docx` text comes from `word/document.xml`
 *   via [com.andharness.ru.data.tools.documents.DocumentSupport.docxText], built on `java.util.zip`
 *   and `org.xmlpull.v1.XmlPullParser`).
 * - `.pdf` — first the embedded text layer is extracted with the existing PdfBox-based
 *   [DocumentTextExtractor]; when a PDF has essentially no text layer (a scan / image-only PDF) the
 *   pages are rendered with [PdfRenderer] and recognised with the app's shared offline
 *   [OcrRecognizer] (the engine behind `ocr_image` / `pdf_ocr`). OCR is slow, so at most
 *   [MAX_OCR_PAGES] pages per document are rendered.
 *
 * The MediaStore `content://` URI is copied to a private temporary file first (bounded by
 * [MAX_FILE_BYTES]) because the extractors and [PdfRenderer] operate on files/seekable descriptors;
 * the copy is deleted in a `finally`. Extraction is capped at [MAX_TEXT_CHARS] so one huge file
 * cannot dominate an indexing pass. Extraction is **failure-safe per file**: [extract] never throws
 * and returns an honest [DocumentExtraction] — [DocumentExtraction.Skipped] for a readable document
 * with nothing to index (unsupported format / empty text / oversized file) and
 * [DocumentExtraction.Failed] for a genuine error, each with a logged reason, so one bad file can
 * never abort a pass (nor be lost silently as an anonymous failure).
 *
 * References:
 * - `ContentResolver.openInputStream(Uri)` / `ContentResolver` —
 *   https://developer.android.com/reference/android/content/ContentResolver
 * - `PdfRenderer` (constructor takes a **seekable** `ParcelFileDescriptor` and owns it; render one
 *   page at a time; `Page.RENDER_MODE_FOR_DISPLAY`) —
 *   https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
 * - `ParcelFileDescriptor.open(File, MODE_READ_ONLY)` —
 *   https://developer.android.com/reference/android/os/ParcelFileDescriptor
 * - PdfBox-based extraction (`PDFTextStripper`) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Shared offline OCR engine (Tesseract4Android + ML Kit Latin fallback) —
 *   https://github.com/adaptech-cz/Tesseract4Android
 * - `java.util.zip.ZipFile` (DOCX/XLSX/ODF container reading) —
 *   https://developer.android.com/reference/java/util/zip/ZipFile
 * - `android.util.Log` (`Log.i(String, String)` / `Log.w(String, String, Throwable)` for the
 *   per-file skip/failure reasons) — https://developer.android.com/reference/android/util/Log
 */
@Singleton
class DocumentTextSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrRecognizer: OcrRecognizer,
) {

    /**
     * Extracts the text of [entry] on [Dispatchers.IO]. **Never throws** and always reports an honest
     * [DocumentExtraction]: [DocumentExtraction.Text] when text was read, [DocumentExtraction.Skipped]
     * for a readable document with nothing to index, or [DocumentExtraction.Failed] for a genuine
     * error — in the last two cases the reason is logged so a skipped/bad file is reported, never lost
     * silently. A single file can never abort the caller's indexing pass.
     */
    suspend fun extract(entry: DeviceDocumentEntry): DocumentExtraction = withContext(Dispatchers.IO) {
        val temp = try {
            copyToTemp(entry)
        } catch (e: DocumentTooLargeException) {
            Log.i(TAG, "Skipping oversized document ${entry.displayName}: ${e.message}")
            return@withContext DocumentExtraction.Skipped("file exceeds $MAX_FILE_BYTES bytes")
        } catch (e: Exception) {
            Log.w(TAG, "Could not stage ${entry.displayName} for extraction", e)
            return@withContext DocumentExtraction.failed(e)
        }
        try {
            val format = try {
                DocumentFormatDetector.detect(temp)
            } catch (e: Exception) {
                Log.w(TAG, "Could not detect the format of ${entry.displayName}", e)
                return@withContext DocumentExtraction.failed(e)
            }
            if (!DocumentTextExtractor.isIndexable(format)) {
                Log.i(TAG, "Skipping non-indexable document ${entry.displayName} ($format)")
                return@withContext DocumentExtraction.Skipped("unsupported format $format")
            }
            val extracted = try {
                DocumentTextExtractor.extract(temp, maxChars = MAX_TEXT_CHARS)
            } catch (e: Exception) {
                Log.w(TAG, "Extraction failed for ${entry.displayName}", e)
                return@withContext DocumentExtraction.failed(e)
            }
            var text = ""
            var ocrUsed = false
            var pages = 0
            when (val classified = DocumentExtraction.classify(format, extracted)) {
                is DocumentExtraction.Skipped -> {
                    Log.i(TAG, "Skipping ${entry.displayName}: ${classified.reason}")
                    return@withContext classified
                }

                is DocumentExtraction.Failed -> {
                    Log.w(TAG, "Extraction failed for ${entry.displayName}: ${classified.reason}")
                    return@withContext classified
                }

                is DocumentExtraction.Text -> text = classified.text
            }
            if (format == DocumentFormat.PDF && text.length < PDF_OCR_TEXT_THRESHOLD) {
                val ocr = try {
                    ocrPdf(temp)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Log.w(TAG, "PDF OCR fallback failed for ${entry.displayName}", e)
                    null
                }
                if (ocr != null && ocr.first.isNotBlank()) {
                    text = ocr.first.trim()
                    pages = ocr.second
                    ocrUsed = true
                }
            }
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                Log.i(TAG, "Skipping document with no text ${entry.displayName}")
                DocumentExtraction.Skipped("no extractable text")
            } else {
                DocumentExtraction.Text(trimmed, ocrUsed, pages)
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    /**
     * Copies the `content://` document to a private temp file, bounded by [MAX_FILE_BYTES]. The name
     * is a hash of the source URI (stable, collision-free for distinct URIs) with the original
     * extension preserved so [DocumentFormatDetector] can use it. A document over [MAX_FILE_BYTES] is
     * a bounded [DocumentTooLargeException] (an honest skip), not a generic failure.
     */
    private fun copyToTemp(entry: DeviceDocumentEntry): File {
        val dir = File(context.cacheDir, TEMP_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create ${dir.absolutePath}")
        }
        val extension = entry.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT).take(8)
        val base = Hashing.sha256Hex(entry.sourceUri).take(16)
        val target = File(dir, if (extension.isEmpty()) base else "$base.$extension")
        val uri = runCatching { Uri.parse(entry.sourceUri) }.getOrNull()
            ?: throw IOException("invalid uri ${entry.sourceUri}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        throw DocumentTooLargeException("document exceeds $MAX_FILE_BYTES bytes")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("cannot open ${entry.sourceUri}")
        return target
    }

    /** Raised by [copyToTemp] when a document is bigger than [MAX_FILE_BYTES]: a bounded skip. */
    private class DocumentTooLargeException(message: String) : IOException(message)

    /**
     * Renders up to [MAX_OCR_PAGES] pages of the PDF [file] and OCRs each one, returning the joined
     * text and the number of pages read. Every native resource is released in `finally`; a failure
     * anywhere is caught by the caller.
     */
    private suspend fun ocrPdf(file: File): Pair<String, Int> {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val pageCount = minOf(renderer.pageCount, MAX_OCR_PAGES)
            val builder = StringBuilder()
            for (index in 0 until pageCount) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = renderer.openPage(index)
                    val scale = TARGET_RENDER_WIDTH.toFloat() / page.width
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = rendered
                    rendered.eraseColor(Color.WHITE)
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val result = ocrRecognizer.recognize(rendered)
                    if (result.text.isNotBlank()) {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(result.text)
                    }
                } finally {
                    runCatching { page?.close() }
                    runCatching { bitmap?.recycle() }
                }
            }
            return builder.toString() to pageCount
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Cache sub-directory holding the transient document copies (deleted after each extraction). */
        const val TEMP_DIR = "device_doc_index"

        /** Characters of extracted text kept per document; a longer document is truncated. */
        const val MAX_TEXT_CHARS = 120_000

        /** Largest document copied for extraction; bigger files are skipped (bounded work/disk). */
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L

        /** Copy buffer size for staging a document into the cache. */
        private const val COPY_BUFFER = 64 * 1024

        /** Below this many characters a PDF is treated as having no text layer and OCR'd instead. */
        const val PDF_OCR_TEXT_THRESHOLD = 40

        /** Hard cap on PDF pages rendered for OCR per document (OCR is expensive). */
        const val MAX_OCR_PAGES = 20

        /** Pixel width a PDF page is rendered at before OCR (keeps small print legible). */
        private const val TARGET_RENDER_WIDTH = 1600
    }
}
