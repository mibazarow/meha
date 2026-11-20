package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `pptx_create` / `pptx_edit` / `odp_create`: presentation creation and bounded editing built on the
 * pure [PptxWriter] (OOXML) and [OdfWriter] (OpenDocument) generators.
 *
 * `pptx_edit` either appends slides (a targeted edit of `ppt/presentation.xml` + relationships +
 * content types, adding new slide parts) or replaces literal text inside `<a:t>` runs of one/all
 * slides. It edits in place with a `.bak` backup and reports honestly when a `find` is absent.
 *
 * References:
 * - PresentationML — https://learn.microsoft.com/en-us/office/open-xml/presentation/presentationml
 * - OpenDocument packages — https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
internal class PptxCreateToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pptx_create"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var destination: File? = null
        var backupOwned = false
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь не указан (path).")
            val slidesRaw = io.stringArgument(arguments, "slides")
                ?: return ToolResult(false, "Список слайдов не указан (slides).")
            val out = when (val resolved = io.resolveDestination(raw, io.booleanArgument(arguments, "overwrite"))) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            destination = out
            val slides = when (val parsed = parseSlides(slidesRaw)) {
                is ParsedSlides.Ok -> parsed.slides
                is ParsedSlides.Err -> return ToolResult(false, parsed.message)
            }
            val bytes = PptxWriter.pptx(slides)
            if (bytes.isEmpty()) return ToolResult(false, "Сформированная презентация пуста.")
            val writeError = io.atomicWrite(out, bytes)
            if (writeError != null) return ToolResult(false, writeError)
            backupOwned = io.backupFile(out).isFile

            val verifyError = verifyPresentation(out, DocumentFormat.PPTX, slides.size)
            if (verifyError != null) {
                val restored = io.restoreBackup(out)
                backupOwned = false
                val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }
            val backupNote = io.cleanupBackup(out)
            backupOwned = false
            ToolResult(
                true,
                "Создана презентация: ${out.absolutePath}\n" +
                    "Формат: PPTX (Office Open XML)\n" +
                    "Слайдов: ${slides.size}\n" +
                    "Размер: ${DocumentSupport.formatBytes(out.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && destination != null) io.restoreBackup(destination!!) else false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при создании презентации: ${e.message}$suffix")
        }
    }
}

internal class OdpCreateToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "odp_create"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var destination: File? = null
        var backupOwned = false
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь не указан (path).")
            val slidesRaw = io.stringArgument(arguments, "slides")
                ?: return ToolResult(false, "Список слайдов не указан (slides).")
            val out = when (val resolved = io.resolveDestination(raw, io.booleanArgument(arguments, "overwrite"))) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            destination = out
            val slides = when (val parsed = parseSlides(slidesRaw)) {
                is ParsedSlides.Ok -> parsed.slides
                is ParsedSlides.Err -> return ToolResult(false, parsed.message)
            }
            val bytes = OdfWriter.odp(slides)
            if (bytes.isEmpty()) return ToolResult(false, "Сформированная презентация пуста.")
            val writeError = io.atomicWrite(out, bytes)
            if (writeError != null) return ToolResult(false, writeError)
            backupOwned = io.backupFile(out).isFile

            val verifyError = verifyPresentation(out, DocumentFormat.ODP, slides.size)
            if (verifyError != null) {
                val restored = io.restoreBackup(out)
                backupOwned = false
                val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }
            val backupNote = io.cleanupBackup(out)
            backupOwned = false
            ToolResult(
                true,
                "Создана презентация: ${out.absolutePath}\n" +
                    "Формат: ODP (OpenDocument)\n" +
                    "Слайдов: ${slides.size}\n" +
                    "Размер: ${DocumentSupport.formatBytes(out.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && destination != null) io.restoreBackup(destination!!) else false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при создании презентации: ${e.message}$suffix")
        }
    }
}

internal class PptxEditToolHandler(
    private val io: DocumentToolIo,
) : ToolHandler {
    override val name = "pptx_edit"

    /** Tracks whether this call owns the `.bak` left by [DocumentToolIo.atomicWrite]. */
    private class State(var backupOwned: Boolean = false)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val state = State()
        var target: File? = null
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь не указан (path).")
            val file = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            target = file
            if (!file.isFile) return ToolResult(false, "Файл не найден: ${file.absolutePath}")
            if (DocumentFormatDetector.detect(file) != DocumentFormat.PPTX) {
                return ToolResult(false, "Файл не является PPTX: ${file.absolutePath}")
            }
            when (io.stringArgument(arguments, "mode")?.lowercase()) {
                "append" -> return appendSlides(file, arguments, state)
                "replace" -> return replaceText(file, arguments, state)
                else -> return ToolResult(
                    false,
                    "Не указан режим (mode): ожидается append (добавить слайды) или replace (заменить текст)."
                )
            }
        } catch (e: Exception) {
            val restored = if (state.backupOwned && target != null) io.restoreBackup(target!!) else false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при правке PPTX: ${e.message}$suffix")
        }
    }

    private fun appendSlides(file: File, arguments: JsonObject, state: State): ToolResult {
        val slidesRaw = io.stringArgument(arguments, "slides")
            ?: return ToolResult(false, "Список добавляемых слайдов не указан (slides).")
        val slides = when (val parsed = parseSlides(slidesRaw)) {
            is ParsedSlides.Ok -> parsed.slides
            is ParsedSlides.Err -> return ToolResult(false, parsed.message)
        }

        val presentationXml = DocumentSupport.readZipEntryText(file, "ppt/presentation.xml")
            ?: return ToolResult(false, "Внутренний документ ppt/presentation.xml не найден.")
        val relsXml = DocumentSupport.readZipEntryText(file, "ppt/_rels/presentation.xml.rels")
            ?: return ToolResult(false, "Внутренний документ ppt/_rels/presentation.xml.rels не найден.")
        val contentTypes = DocumentSupport.readZipEntryText(file, "[Content_Types].xml")
            ?: return ToolResult(false, "Внутренний документ [Content_Types].xml не найден.")

        val existingSlides = DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file))
        val oldCount = existingSlides.size
        val nextNumber = existingSlides.mapNotNull { SLIDE_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1
        val nextRelId = REL_ID.findAll(relsXml).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            ?.plus(1) ?: 1
        val nextSlideId = SLIDE_ID.findAll(presentationXml).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.plus(1)?.coerceAtLeast(MIN_SLIDE_ID) ?: MIN_SLIDE_ID

        var updatedPresentation = presentationXml
        var updatedRels = relsXml
        var updatedContentTypes = contentTypes
        val additions = LinkedHashMap<String, ByteArray>()
        slides.forEachIndexed { index, slide ->
            val number = nextNumber + index
            val relId = "rId${nextRelId + index}"
            val slideId = nextSlideId + index
            updatedPresentation = PptxWriter.presentationWithSlide(updatedPresentation, slideId, relId)
            updatedRels = PptxWriter.relsWithSlide(updatedRels, relId, "slides/slide$number.xml")
            updatedContentTypes = PptxWriter.contentTypesWithSlide(updatedContentTypes, number)
            additions["ppt/slides/slide$number.xml"] = PptxWriter.slideXml(slide).toByteArray(Charsets.UTF_8)
            additions["ppt/slides/_rels/slide$number.xml.rels"] = slideRelsXml().toByteArray(Charsets.UTF_8)
        }

        val bytes = DocumentSupport.rewriteZip(
            file,
            mapOf(
                "ppt/presentation.xml" to updatedPresentation.toByteArray(Charsets.UTF_8),
                "ppt/_rels/presentation.xml.rels" to updatedRels.toByteArray(Charsets.UTF_8),
                "[Content_Types].xml" to updatedContentTypes.toByteArray(Charsets.UTF_8),
            ),
            additions,
        ) ?: return ToolResult(false, "Не удалось перепаковать контейнер (${file.name}).")
        if (bytes.isEmpty()) return ToolResult(false, "Изменённая презентация получилась пустой.")

        val writeError = io.atomicWrite(file, bytes)
        if (writeError != null) return ToolResult(false, writeError)
        state.backupOwned = io.backupFile(file).isFile

        val verifyError = verifyPresentation(file, DocumentFormat.PPTX, oldCount + slides.size)
        if (verifyError != null) {
            val restored = io.restoreBackup(file)
            state.backupOwned = false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
        }
        val backupNote = io.cleanupBackup(file)
        state.backupOwned = false
        return ToolResult(
            true,
            "Изменён документ: ${file.absolutePath}\n" +
                "Формат: PPTX\n" +
                "Добавлено слайдов: ${slides.size} (было $oldCount, стало ${oldCount + slides.size})\n" +
                "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                if (backupNote != null) "\n$backupNote" else ""
        )
    }

    private fun replaceText(file: File, arguments: JsonObject, state: State): ToolResult {
        val find = io.stringArgument(arguments, "find")
            ?: return ToolResult(false, "Не указан искомый текст (find).")
        val replace = (arguments["replace"] as? JsonPrimitive)?.content
            ?: return ToolResult(false, "Не указан текст замены (replace).")
        val slides = DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file))
        if (slides.isEmpty()) return ToolResult(false, "В презентации не найдено ни одного слайда.")

        val selected: List<String>
        val slideArg = io.stringArgument(arguments, "slide")
        if (slideArg == null) {
            selected = slides
        } else {
            val requested = slideArg.toIntOrNull()
                ?: return ToolResult(false, "Некорректный номер слайда (slide): «$slideArg».")
            if (requested < 1 || requested > slides.size) {
                return ToolResult(false, "Слайд $requested вне диапазона: в презентации ${slides.size} сл.")
            }
            selected = listOf(slides[requested - 1])
        }

        var applied = 0
        val replacements = LinkedHashMap<String, ByteArray>()
        for (entry in selected) {
            val xml = DocumentSupport.readZipEntryText(file, entry)
                ?: return ToolResult(false, "Не удалось прочитать слайд $entry.")
            val updated = DocumentWriter.replaceInTextNodes(xml, find, replace, listOf("t"))
            if (updated != xml) {
                applied++
                replacements[entry] = updated.toByteArray(Charsets.UTF_8)
            }
        }
        if (applied == 0) {
            return ToolResult(false, "Текст «$find» не найден на выбранных слайдах (замена не выполнена).")
        }

        val bytes = DocumentSupport.rewriteZip(file, replacements)
            ?: return ToolResult(false, "Не удалось перепаковать контейнер (${file.name}).")
        val writeError = io.atomicWrite(file, bytes)
        if (writeError != null) return ToolResult(false, writeError)
        state.backupOwned = io.backupFile(file).isFile

        val verifyError = verifyPresentation(file, DocumentFormat.PPTX, slides.size)
        if (verifyError != null) {
            val restored = io.restoreBackup(file)
            state.backupOwned = false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
        }
        val backupNote = io.cleanupBackup(file)
        state.backupOwned = false
        return ToolResult(
            true,
            "Изменён документ: ${file.absolutePath}\n" +
                "Формат: PPTX\n" +
                "Замен на слайдах: $applied из ${selected.size} выбранных\n" +
                "Примечание: замена ограничена одним текстовым узлом `<a:t>`; текст, разбитый " +
                "между несколькими runs, не находится.\n" +
                "Размер: ${DocumentSupport.formatBytes(file.length())}" +
                if (backupNote != null) "\n$backupNote" else ""
        )
    }
}

/** Parsed slide spec or an honest parse error. */
private sealed interface ParsedSlides {
    data class Ok(val slides: List<PptxWriter.Slide>) : ParsedSlides
    data class Err(val message: String) : ParsedSlides
}

private fun parseSlides(json: String): ParsedSlides = try {
    ParsedSlides.Ok(SlideSpecParser.parse(json))
} catch (e: IllegalArgumentException) {
    ParsedSlides.Err(e.message ?: "Некорректный список слайдов.")
}

/** Verifies a freshly written presentation: format, slide/page count and non-empty content. */
internal fun verifyPresentation(file: File, format: DocumentFormat, expectedSlides: Int): String? {
    if (!file.isFile) return "файл недоступен после записи."
    if (file.length() == 0L) return "файл пуст после записи."
    val detected = DocumentFormatDetector.detect(file)
    if (detected != format) return "формат файла после записи определён как ${detected.name}."
    val actual = when (format) {
        DocumentFormat.PPTX ->
            DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file)).size
        DocumentFormat.ODP -> DocumentSupport.readZipEntryText(file, "content.xml")
            ?.let { DocumentSupport.countXmlElements(it, "page") } ?: 0
        else -> 0
    }
    if (actual != expectedSlides) return "слайдов $actual, ожидалось $expectedSlides."
    return null
}

private fun slideRelsXml(): String =
    DocumentXml.HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" " +
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" " +
        "Target=\"../slideLayouts/slideLayout1.xml\"/>" +
        "</Relationships>"

private val SLIDE_NUMBER = Regex("""^ppt/slides/slide(\d+)\.xml$""")
private val REL_ID = Regex("""Id="rId(\d+)"""")
private val SLIDE_ID = Regex("""<p:sldId\b[^>]*\bid="(\d+)"""")
private const val MIN_SLIDE_ID = 256
