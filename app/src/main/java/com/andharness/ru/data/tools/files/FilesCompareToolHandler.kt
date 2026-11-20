package com.andharness.ru.data.tools.files

import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * `files_compare`: compares two files byte by byte. If they are byte-identical it says so; if not
 * it reports both sizes and the offset of the first differing byte. When both files are text (no NUL
 * byte in the sampled header and each no larger than [MAX_TEXT_BYTES]) it adds a small, bounded
 * unified-diff-like summary of the first divergence.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.io.FileInputStream` — https://developer.android.com/reference/java/io/FileInputStream
 */
internal class FilesCompareToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_compare"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val first = File(io.path(arguments, "path1"))
            val second = File(io.path(arguments, "path2"))
            if (!first.isFile) return ToolResult(false, "Не файл: «${first.absolutePath}».")
            if (!second.isFile) return ToolResult(false, "Не файл: «${second.absolutePath}».")

            val sizeA = first.length()
            val sizeB = second.length()
            val offset = FileCompareSupport.firstDifference(first, second)

            if (offset == null) {
                return ToolResult(
                    true,
                    "Файлы побайтово идентичны: «${first.name}» и «${second.name}» " +
                        "(${formatBytes(sizeA)}, $sizeA байт)."
                )
            }

            val builder = StringBuilder()
            builder.append("Файлы различаются.\n")
            builder.append("«${first.name}»: ${formatBytes(sizeA)} ($sizeA байт)\n")
            builder.append("«${second.name}»: ${formatBytes(sizeB)} ($sizeB байт)\n")
            builder.append("Первое различие на смещении $offset байт")

            val textDiff = textSummary(first, second, arguments)
            if (textDiff != null) {
                builder.append("\n").append(textDiff)
            } else {
                builder.append("\n(Построчное сравнение пропущено: файл слишком большой или не текстовый.)")
            }

            ToolResult(true, builder.toString())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * A bounded line-diff of the two files when both are plausibly text and small enough, otherwise
     * `null`. The cap keeps a compare of two large binaries from allocating a huge line list.
     */
    private fun textSummary(first: File, second: File, arguments: JsonObject): String? {
        if (first.length() > MAX_TEXT_BYTES || second.length() > MAX_TEXT_BYTES) return null
        if (!looksText(first) || !looksText(second)) return null
        val maxLines = arguments.str("max_lines")?.toIntOrNull()?.coerceIn(1, MAX_DIFF_LINES)
            ?: FileCompareSupport.DEFAULT_MAX_DIFF_LINES
        val diff = FileCompareSupport.unifiedSummary(
            first.readLines(),
            second.readLines(),
            maxLines = maxLines,
        )
        return diff.ifEmpty { null }
    }

    /** True when the header has no NUL byte (a reliable-enough signal that the file is text). */
    private fun looksText(file: File): Boolean = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            val read = input.read(buffer)
            read <= 0 || buffer.copyOf(read).none { it == 0.toByte() }
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        private const val HEADER_BYTES = 64 * 1024
        private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        private const val MAX_DIFF_LINES = 200
    }
}
