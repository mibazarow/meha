package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/**
 * `office_merge` / `office_split`: content-level merge and split for the document types where the
 * operation is meaningful. Both are explicit about what they do:
 *
 * - text documents (DOCX/ODT/RTF/TXT/MD/HTML/JSON/XML) are concatenated (or split on a separator
 *   line) as plain text — formatting is not preserved;
 * - tables (XLSX/ODS/CSV/TSV) are appended row by row (a marker row names each source) or split into
 *   one CSV per sheet;
 * - presentations (PPTX/ODP) are merged/split at the slide-content level (each slide keeps its text).
 *
 * Types that cannot be merged/split faithfully are refused with a clear message.
 *
 * References:
 * - OOXML / OpenDocument containers — https://learn.microsoft.com/en-us/office/open-xml/,
 *   https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 */
internal class OfficeMergeToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "office_merge"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        var destination: File? = null
        var backupOwned = false
        return try {
            val sourcesRaw = io.stringArgument(arguments, "sources")
                ?: return ToolResult(false, "Не указаны исходные файлы (sources, через запятую).")
            val rawSources = sourcesRaw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (rawSources.isEmpty()) return ToolResult(false, "Список sources пуст.")
            val separator = io.stringArgument(arguments, "separator") ?: OfficeMerge.DEFAULT_SEPARATOR

            val files = rawSources.map { path ->
                val file = try {
                    io.resolve(path)
                } catch (e: IllegalArgumentException) {
                    return ToolResult(false, e.message ?: "Некорректный путь: «$path».")
                }
                if (!file.isFile) return ToolResult(false, "Файл не найден: ${file.absolutePath}")
                file
            }
            val formats = files.map { DocumentFormatDetector.detect(it) }
            val kinds = formats.map { OfficeConvert.sourceKind(it) }
            kinds.forEachIndexed { index, kind ->
                if (kind == null) {
                    return ToolResult(
                        false,
                        "Файл ${files[index].name} (${formats[index].name}) нельзя объединять."
                    )
                }
            }
            if (kinds.toSet().size != 1) {
                return ToolResult(false, "Нельзя объединять файлы разных типов (текст/таблица/презентация).")
            }
            val kind = kinds.first()!!

            val destinationRaw = io.stringArgument(arguments, "destination")
                ?: return ToolResult(false, "Не указан путь результата (destination).")
            val target = OfficeConvert.Target.from(destinationRaw.substringAfterLast('.', ""))

            val bytes = when (kind) {
                OfficeConvert.SourceKind.TEXT -> {
                    val texts = files.map { textOf(it) }
                    val merged = OfficeMerge.mergeTexts(texts, separator)
                    val resolvedTarget = resolveTextTarget(target, kind)
                        ?: return ToolResult(false, textTargetError(target, kind))
                    OfficeConvert.convert(resolvedTarget, kind, merged, emptyList(), "Объединённый документ")
                }
                OfficeConvert.SourceKind.TABLE -> {
                    val rowsList = files.map { file ->
                        inspector.extractTableRows(file, DocumentFormatDetector.detect(file), null)
                            ?: return ToolResult(false, "Не удалось прочитать таблицу из ${file.name}.")
                    }
                    val markers = files.mapIndexed { index, file ->
                        if (index == 0) null else "=== ${file.name} ==="
                    }
                    val merged = OfficeMerge.mergeRows(rowsList, markers)
                    val resolvedTarget = resolveTableTarget(target, kind)
                        ?: return ToolResult(false, textTargetError(target, kind))
                    OfficeConvert.convert(resolvedTarget, kind, "", merged)
                }
                OfficeConvert.SourceKind.PRESENTATION -> {
                    val slides = files.flatMap { inspector.extractSlideModels(it, DocumentFormatDetector.detect(it)) }
                    if (slides.isEmpty()) return ToolResult(false, "Не удалось извлечь ни одного слайда.")
                    when (target) {
                        OfficeConvert.Target.PPTX -> PptxWriter.pptx(slides)
                        OfficeConvert.Target.ODP -> OdfWriter.odp(slides)
                        OfficeConvert.Target.TXT, OfficeConvert.Target.MD ->
                            OfficeConvert.convert(target, kind, slidesToText(slides), emptyList())
                        else -> return ToolResult(
                            false,
                            "Для презентаций результат указывается как .pptx, .odp, .txt или .md."
                        )
                    }
                }
            }
            if (bytes.isEmpty()) return ToolResult(false, "Результат объединения пуст.")

            val out = when (val resolved = io.resolveDestination(destinationRaw, io.booleanArgument(arguments, "overwrite"))) {
                is Destination.Failure -> return ToolResult(false, resolved.message)
                is Destination.Ok -> resolved.file
            }
            destination = out

            val writeError = io.atomicWrite(out, bytes)
            if (writeError != null) return ToolResult(false, writeError)
            backupOwned = io.backupFile(out).isFile

            val verifyError = verifyMerged(out, kind)
            if (verifyError != null) {
                val restored = io.restoreBackup(out)
                if (!restored) runCatching { out.delete() }
                backupOwned = false
                val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
                return ToolResult(false, "Проверка после записи не пройдена: $verifyError$suffix")
            }

            val backupNote = io.cleanupBackup(out)
            backupOwned = false
            ToolResult(
                true,
                "Объединено файлов: ${files.size}\n" +
                    "Результат: ${out.absolutePath}\n" +
                    "Формат результата: ${DocumentFormatDetector.detect(out).name}\n" +
                    "Размер: ${DocumentSupport.formatBytes(out.length())}" +
                    if (backupNote != null) "\n$backupNote" else ""
            )
        } catch (e: Exception) {
            val restored = if (backupOwned && destination != null) io.restoreBackup(destination!!) else false
            val suffix = if (restored) " Файл восстановлен из резервной копии." else ""
            ToolResult(false, "Ошибка при объединении: ${e.message}$suffix")
        }
    }

    private fun textOf(file: File): String {
        val extraction = inspector.extract(file, DocumentFormatDetector.detect(file), null, null)
        return if (extraction is Extraction.Success) extraction.text else ""
    }

    private fun resolveTextTarget(
        target: OfficeConvert.Target?,
        kind: OfficeConvert.SourceKind,
    ): OfficeConvert.Target? = when {
        target == null -> OfficeConvert.Target.TXT
        OfficeConvert.supports(target, kind) -> target
        else -> null
    }

    private fun resolveTableTarget(
        target: OfficeConvert.Target?,
        kind: OfficeConvert.SourceKind,
    ): OfficeConvert.Target? = when {
        target == null -> OfficeConvert.Target.CSV
        OfficeConvert.supports(target, kind) -> target
        else -> null
    }

    private fun textTargetError(target: OfficeConvert.Target?, kind: OfficeConvert.SourceKind): String =
        "Формат назначения ${target?.format?.name ?: "?"} не поддерживается для этого типа. Доступны: " +
            OfficeConvert.Target.entries.filter { OfficeConvert.supports(it, kind) }
                .joinToString(", ") { it.extension } + "."

    private fun verifyMerged(file: File, kind: OfficeConvert.SourceKind): String? {
        if (!file.isFile) return "файл недоступен после записи."
        if (file.length() == 0L) return "файл пуст после записи."
        val detected = DocumentFormatDetector.detect(file)
        if (kind == OfficeConvert.SourceKind.PRESENTATION &&
            detected != DocumentFormat.PPTX && detected != DocumentFormat.ODP &&
            detected != DocumentFormat.TEXT && detected != DocumentFormat.MARKDOWN
        ) {
            return "неожиданный формат результата: ${detected.name}."
        }
        return null
    }
}

internal class OfficeSplitToolHandler(
    private val io: DocumentToolIo,
    private val inspector: DocumentInspector,
) : ToolHandler {
    override val name = "office_split"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val raw = io.stringArgument(arguments, "path")
                ?: return ToolResult(false, "Путь к документу не указан (path).")
            val source = try {
                io.resolve(raw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный путь: «$raw».")
            }
            if (!source.isFile) return ToolResult(false, "Файл не найден: ${source.absolutePath}")

            val dirRaw = io.stringArgument(arguments, "output_dir")
                ?: return ToolResult(false, "Не указан каталог результатов (output_dir).")
            val dir = try {
                io.resolve(dirRaw)
            } catch (e: IllegalArgumentException) {
                return ToolResult(false, e.message ?: "Некорректный каталог: «$dirRaw».")
            }
            if (dir.exists() && !dir.isDirectory) {
                return ToolResult(false, "Путь указывает на файл, а не каталог: ${dir.absolutePath}")
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return ToolResult(false, "Не удалось создать каталог: ${dir.absolutePath}")
            }
            val overwrite = io.booleanArgument(arguments, "overwrite")
            val separator = io.stringArgument(arguments, "separator") ?: "---"

            val format = DocumentFormatDetector.detect(source)
            val kind = OfficeConvert.sourceKind(format)
                ?: return ToolResult(
                    false,
                    "Формат ${format.name} нельзя разбить (нужен текст, таблица или презентация)."
                )

            val written = mutableListOf<String>()
            var skipped = 0

            when (kind) {
                OfficeConvert.SourceKind.PRESENTATION -> {
                    val slides = inspector.extractSlideModels(source, format)
                    if (slides.isEmpty()) return ToolResult(false, "В презентации не найдено слайдов.")
                    val extension = if (format == DocumentFormat.PPTX) "pptx" else "odp"
                    val outFormat = if (format == DocumentFormat.PPTX) DocumentFormat.PPTX else DocumentFormat.ODP
                    slides.forEachIndexed { index, slide ->
                        val name = "slide-%03d.%s".format(Locale.ROOT, index + 1, extension)
                        val file = File(dir, name)
                        if (file.exists() && !overwrite) {
                            skipped++
                            return@forEachIndexed
                        }
                        val bytes = if (outFormat == DocumentFormat.PPTX) {
                            PptxWriter.pptx(listOf(slide))
                        } else {
                            OdfWriter.odp(listOf(slide))
                        }
                        io.atomicWrite(file, bytes)
                        io.backupFile(file).delete()
                        val error = verifyPresentation(file, outFormat, 1)
                        if (error != null) return ToolResult(false, "Слайд ${index + 1}: $error")
                        written += name
                    }
                }
                OfficeConvert.SourceKind.TABLE -> {
                    val sheets = inspector.extractAllSheets(source, format)
                    if (sheets.isEmpty()) {
                        return ToolResult(false, "Не удалось прочитать листы из ${source.name}.")
                    }
                    sheets.forEachIndexed { index, (sheetName, rows) ->
                        val name = "sheet-%03d-%s.csv".format(Locale.ROOT, index + 1, sanitize(sheetName))
                        val file = File(dir, name)
                        if (file.exists() && !overwrite) {
                            skipped++
                            return@forEachIndexed
                        }
                        io.atomicWrite(file, DocumentWriter.csv(rows, ','))
                        io.backupFile(file).delete()
                        written += name
                    }
                }
                OfficeConvert.SourceKind.TEXT -> {
                    val extraction = inspector.extract(source, format, null, null)
                    if (extraction is Extraction.Failure) return ToolResult(false, extraction.message)
                    val parts = OfficeMerge.splitText((extraction as Extraction.Success).text, separator)
                    if (parts.isEmpty()) return ToolResult(false, "Нечего разбивать: документ пуст.")
                    parts.forEachIndexed { index, part ->
                        val name = "part-%03d.txt".format(Locale.ROOT, index + 1)
                        val file = File(dir, name)
                        if (file.exists() && !overwrite) {
                            skipped++
                            return@forEachIndexed
                        }
                        io.atomicWrite(file, part.toByteArray(Charsets.UTF_8))
                        io.backupFile(file).delete()
                        written += name
                    }
                }
            }

            if (written.isEmpty() && skipped > 0) {
                return ToolResult(false, "Все файлы уже существуют (overwrite=false), ничего не записано.")
            }
            if (written.isEmpty()) return ToolResult(false, "Не создано ни одного файла.")
            ToolResult(
                true,
                "Разбит документ: ${source.absolutePath}\n" +
                    "Формат: ${format.name}\n" +
                    "Создано файлов: ${written.size} в ${dir.absolutePath}" +
                    (if (skipped > 0) "\nПропущено существующих: $skipped (overwrite=false)" else "") +
                    "\nФайлы: ${written.take(20).joinToString(", ")}" +
                    (if (written.size > 20) " …" else "")
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка при разбиении: ${e.message}")
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.', '-')
        return cleaned.ifEmpty { "sheet" }
    }
}

private fun slidesToText(slides: List<PptxWriter.Slide>): String =
    slides.joinToString("\n\n") { slide ->
        (listOfNotNull(slide.title?.takeIf { it.isNotBlank() }) + slide.lines).joinToString("\n")
    }
