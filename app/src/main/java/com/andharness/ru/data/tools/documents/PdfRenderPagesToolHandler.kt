package com.andharness.ru.data.tools.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.documents.PdfToolOptions
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream

/**
 * `pdf_render_pages`: renders the selected pages to PNG files with Android's [PdfRenderer]. Each page
 * is rendered at `dpi` (default [PdfToolOptions.DEFAULT_RENDER_DPI]) — PDF points are 1/72", so the
 * pixel size is `points × dpi / 72` — and written to `output_dir` as `page-001.png`. Existing files
 * are skipped unless `overwrite=true`. The file is opened through a `ParcelFileDescriptor`, so the
 * whole document is never loaded into memory, and every native resource is closed in a `finally`.
 *
 * References:
 * - `PdfRenderer` / `PdfRenderer.Page` (points = 1/72") —
 *   https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
 * - `ParcelFileDescriptor` —
 *   https://developer.android.com/reference/android/os/ParcelFileDescriptor
 * - `Bitmap.compress` —
 *   https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,%20java.io.OutputStream)
 */
internal class PdfRenderPagesToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pdf_render_pages"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawPath = io.stringArgument(arguments, "path")
            ?: return@withContext ToolResult(false, "Не указан путь к PDF (path).")
        val rawDir = io.stringArgument(arguments, "output_dir")
            ?: return@withContext ToolResult(false, "Не указан каталог назначения (output_dir).")
        val overwrite = io.booleanArgument(arguments, "overwrite")
        val dpi = PdfToolOptions.renderDpi(io.stringArgument(arguments, "dpi"))

        val source = when (val resolved = io.resolvePdfSource(rawPath)) {
            is PdfSource.Failure -> return@withContext ToolResult(false, resolved.message)
            is PdfSource.Ok -> resolved
        }
        val dir = try {
            io.resolve(rawDir)
        } catch (e: IllegalArgumentException) {
            return@withContext ToolResult(false, e.message ?: "Некорректный путь: «$rawDir».")
        }
        if (dir.isFile) {
            return@withContext ToolResult(false, "Путь указывает на файл, а не каталог: ${dir.absolutePath}")
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext ToolResult(false, "Не удалось создать каталог: ${dir.absolutePath}")
        }

        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        try {
            descriptor = ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val pageCount = renderer.pageCount
            val rawPages = io.stringArgument(arguments, "pages")
            val range = if (rawPages == null) {
                1..pageCount
            } else {
                io.parsePageRange(rawPages, pageCount)
                    ?: return@withContext ToolResult(
                        false,
                        "Некорректный диапазон страниц «$rawPages»: в документе $pageCount стр."
                    )
            }

            val prefix = io.stringArgument(arguments, "prefix") ?: "page"
            for (pageNumber in range) {
                val name = PdfToolOptions.pageFileName(prefix, pageNumber)
                val target = File(dir, name)
                if (target.exists() && !overwrite) {
                    skipped += name
                    continue
                }
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = renderer.openPage(pageNumber - 1)
                    val width = PdfToolOptions.pointsToPixels(page.width, dpi)
                    val height = PdfToolOptions.pointsToPixels(page.height, dpi)
                    val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = rendered
                    rendered.eraseColor(Color.WHITE)
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(target).use { out ->
                        rendered.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    written += name
                } finally {
                    runCatching { page?.close() }
                    runCatching { bitmap?.recycle() }
                }
            }
        } catch (e: Exception) {
            return@withContext ToolResult(false, "Ошибка при рендере PDF: ${e.message}")
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }

        ToolResult(
            true,
            "Отрендерено страниц: ${written.size} → ${dir.absolutePath}\n" +
                "DPI: $dpi, формат: PNG" +
                if (skipped.isNotEmpty()) {
                    "\nПропущено (уже существуют, overwrite=false): ${skipped.joinToString(", ")}"
                } else {
                    ""
                }
        )
    }
}
