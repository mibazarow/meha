package com.andharness.ru.data.tools.documents

import android.content.Context
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.di.PdfModule
import com.andharness.ru.domain.tools.ToolHandler

/**
 * Document tools. `doc_info` detects a document's logical format (see [DocumentFormatDetector]) and
 * reports an honest, format-specific structural summary, plus the byte size and absolute path.
 * `doc_read` extracts the text of the same formats (optionally a single PDF page / PPTX slide /
 * spreadsheet sheet) with an explicit `max_chars` cut-off. `doc_create` writes a new document in the
 * formats that can be produced on-device (see [DocumentWriter] for the pure byte generators and
 * [createPdf] for the PdfBox-backed PDF writer), then re-opens it through the same reader path
 * `doc_read` uses before reporting success. `doc_edit` appends to or replaces text inside an
 * existing document of the same formats (see [DocumentWriter] for the pure container transforms and
 * [DocumentSupport.rewriteZip] for OOXML/ODF repackaging), writing atomically with a `.bak` backup
 * and re-reading the result before reporting success. `pdf_merge` combines several PDFs into one with
 * PdfBox `PDFMergerUtility`, and `pdf_split` copies a 1-based page range into a new document with
 * `PDDocument.importPage`; both write to a temp file in the destination directory, re-open it with
 * PdfBox to check the page count, and only then swap it in — a mismatch is an honest failure that
 * leaves no half-written output and never touches the sources. `pdf_ocr` renders one PDF page with
 * `android.graphics.pdf.PdfRenderer` and runs ML Kit text recognition on the bitmap, and
 * `xlsx_set_cell` writes one cell of an existing workbook with the pure [DocumentWriter.setXlsxCell]
 * transform, verifying the stored value through [DocumentSupport.xlsxCellValue] before reporting
 * success.
 *
 * [pdfInitializer] is never read: it exists so Hilt constructs `PdfModule.PdfBoxInitializer`
 * (which calls `PDFBoxResourceLoader.init`) before any PdfBox API is touched.
 *
 * References:
 * - PdfBox-Android (`PDDocument.load` / `getNumberOfPages` / `PDFTextStripper`; for creation
 *   `PDDocument` / `PDPage` / `PDPageContentStream` / `PDType0Font` (embedded Unicode font, see
 *   [PdfFontAssets]) with `PDType1Font` as fallback / `PDRectangle`; for merging and
 *   splitting `PDFMergerUtility` / `MemoryUsageSetting` / `PDDocument.importPage`) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - rtfparserkit (`StringTextConverter`) — https://github.com/joniles/rtfparserkit
 * - Apache Commons CSV (`CSVParser`) — https://commons.apache.org/proper/commons-csv/
 * - `java.io.FileOutputStream` —
 *   https://developer.android.com/reference/java/io/FileOutputStream
 */
internal class DocumentToolsHandler(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") pdfInitializer: PdfModule.PdfBoxInitializer,
    private val ocrRecognizer: OcrRecognizer,
) {

    /** The document tools in their original registration order. */
    fun handlers(): List<ToolHandler> {
        val io = DocumentToolIo(context)
        val inspector = DocumentInspector()
        val writer = PdfWriter(context)
        val editor = DocumentEditor(inspector, writer)
        return listOf(
            DocInfoToolHandler(io, inspector),
            DocReadToolHandler(io, inspector),
            DocCreateToolHandler(io, writer, inspector),
            DocEditToolHandler(io, editor),
            PdfFromLinesToolHandler("pdf_create", markdown = false, io = io, writer = writer),
            PdfFromLinesToolHandler("pdf_from_markdown", markdown = true, io = io, writer = writer),
            PdfMergeToolHandler(io),
            PdfSplitToolHandler(io),
            PdfOcrToolHandler(io, ocrRecognizer),
            PdfPagesInfoToolHandler(io),
            PdfRotateToolHandler(io),
            PdfWatermarkToolHandler(io, writer),
            PdfExtractImagesToolHandler(io),
            PdfRenderPagesToolHandler(io),
            PdfCompressToolHandler(io),
            PdfFillFormToolHandler(io),
            XlsxSetCellToolHandler(io, inspector),
            PdfFromTemplateToolHandler("pdf_from_template", fromFile = false, io = io, writer = writer),
            PdfFromTemplateToolHandler("pdf_from_template_file", fromFile = true, io = io, writer = writer),
            XlsxSetCellsToolHandler(io, inspector),
            DocxReplaceToolHandler(io, inspector),
            DocxAppendToolHandler(io, inspector),
            PptxCreateToolHandler(io),
            PptxEditToolHandler(io),
            OdpCreateToolHandler(io),
            XlsxStyleToolHandler(io, inspector),
            OfficeConvertToolHandler(io, inspector),
            OfficeMergeToolHandler(io, inspector),
            OfficeSplitToolHandler(io, inspector),
            DocTablesToolHandler(io, inspector),
        )
    }
}
