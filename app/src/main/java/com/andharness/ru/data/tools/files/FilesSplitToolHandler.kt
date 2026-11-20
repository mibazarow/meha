package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_split`: splits one file into N equal-size parts or into size-based parts (e.g. 100 MB).
 * Parts are named `«name».partNN` (zero-padded) in the destination directory and the plan is
 * verified after writing: every part must exist with exactly the planned size and the sizes must
 * sum to the source size. The source file is never modified, and no existing part is overwritten.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.io.FileOutputStream` — https://developer.android.com/reference/java/io/FileOutputStream
 * - `java.io.BufferedOutputStream` — https://developer.android.com/reference/java/io/BufferedOutputStream
 */
internal class FilesSplitToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_split"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val source = File(io.path(arguments, "source"))
            if (!source.isFile) return ToolResult(false, "Не файл: «${source.absolutePath}».")
            val total = source.length()
            if (total <= 0L) return ToolResult(false, "Файл пустой — делить нечего.")

            val partsRaw = arguments.str("parts")
            val sizeRaw = arguments.str("part_size")
            if (partsRaw != null && sizeRaw != null) {
                return ToolResult(false, "Укажите только одно: parts или part_size.")
            }

            val plan: List<Long>
            when {
                partsRaw != null -> {
                    val count = partsRaw.toIntOrNull()
                    if (count == null || count < 1) {
                        return ToolResult(false, "parts должен быть целым числом ≥ 1 (получено «$partsRaw»).")
                    }
                    if (count > FileSplitSupport.MAX_PARTS) {
                        return ToolResult(false, "Слишком много частей (максимум ${FileSplitSupport.MAX_PARTS}).")
                    }
                    if (count.toLong() > total) {
                        return ToolResult(false, "Частей ($count) больше, чем байт в файле ($total).")
                    }
                    plan = FileSplitSupport.equalPartSizes(total, count)
                }
                sizeRaw != null -> {
                    val partSize = FileSplitSupport.parseByteCount(sizeRaw)
                        ?: return ToolResult(false, "part_size: некорректный размер «$sizeRaw». Примеры: 104857600 или 100MB.")
                    val count = FileSplitSupport.partCountForSize(total, partSize)
                    if (count > FileSplitSupport.MAX_PARTS) {
                        return ToolResult(false, "Слишком много частей ($count; максимум ${FileSplitSupport.MAX_PARTS}). Увеличьте part_size.")
                    }
                    plan = FileSplitSupport.sizedPartSizes(total, partSize)
                }
                else -> return ToolResult(false, "Укажите parts (число частей) или part_size (размер части, например 100MB).")
            }
            if (plan.isEmpty()) return ToolResult(false, "Не удалось построить план разбиения.")

            val destDir = arguments.str("destination_dir")?.let { File(io.path(arguments, "destination_dir")) }
                ?: source.parentFile
                ?: io.root()
            if (!destDir.exists() && !destDir.mkdirs()) {
                return ToolResult(false, "Не удалось создать папку назначения: «${destDir.absolutePath}».")
            }

            val names = FileSplitSupport.partNames(source.name, plan.size)
            val targets = names.map { File(destDir, it) }
            val existing = targets.filter { it.exists() }
            if (existing.isNotEmpty()) {
                return ToolResult(
                    false,
                    "Части уже существуют (перезапись отменена): ${existing.joinToString(", ") { it.name }}."
                )
            }

            val written = ArrayList<File>()
            try {
                source.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                    for ((index, partSize) in plan.withIndex()) {
                        val target = targets[index]
                        target.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                            var remaining = partSize
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (remaining > 0L) {
                                val want = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, want)
                                if (read < 0) return failCleaning(written, "Файл-источник короче ожидаемого.")
                                output.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                        written += target
                    }
                }
            } catch (e: Exception) {
                return failCleaning(written, "Не удалось разбить файл: ${e.message}")
            }

            // Post-verification: exact size of every part and an exact total.
            var sum = 0L
            for ((index, target) in targets.withIndex()) {
                if (!target.isFile || target.length() != plan[index]) {
                    return failCleaning(written, "Часть «${target.name}» записана неверно. Разбиение отменено.")
                }
                sum += target.length()
            }
            if (sum != total) {
                return failCleaning(written, "Суммарный размер частей ($sum) не совпал с исходным ($total). Разбиение отменено.")
            }

            val listing = plan.indices.joinToString("\n") { index ->
                "${index + 1}) ${names[index]} — ${plan[index]} байт (${formatBytes(plan[index])})"
            }
            ToolResult(
                true,
                "Файл «${source.name}» (${formatBytes(total)}, $total байт) разбит на ${plan.size} " +
                    "частей в «${destDir.absolutePath}»:\n$listing"
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /** Deletes every part written so far (never the source) and reports an honest failure. */
    private fun failCleaning(written: List<File>, reason: String): ToolResult {
        written.forEach { it.delete() }
        return ToolResult(false, "$reason Созданные части удалены.")
    }

    private companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
