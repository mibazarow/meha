package com.andharness.ru.data.tools.saf

import android.content.Context
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream

/**
 * `saf_read`: reads a bounded, text-only slice of a document inside a persisted tree grant.
 * `ContentResolver.openInputStream(Uri)`:
 * https://developer.android.com/reference/android/content/ContentResolver#openInputStream(android.net.Uri)
 */
internal class SafReadToolHandler(
    private val context: Context,
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_read"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = access.grantIdArg(arguments) ?: return@withContext ToolResult(
                false,
                "Некорректный grant_id: «${(arguments["grant_id"] as? JsonPrimitive)?.content}». Используйте saf_grants_list."
            )
            if (access.grantUri(id) == null) {
                return@withContext ToolResult(false, "Грант #$id не найден. Используйте saf_grants_list.")
            }
            val path = (arguments["path"] as? JsonPrimitive)?.content
            val segments = access.pathSegments(path)
                ?: return@withContext ToolResult(false, access.invalidPathMessage(path))
            if (segments.isEmpty()) {
                return@withContext ToolResult(false, "Нужен путь к файлу внутри гранта.")
            }
            val doc = access.resolveDocument(id, path)
                ?: return@withContext ToolResult(false, "Документ не найден: «${path?.trim().orEmpty()}».")
            if (doc.isDirectory) {
                return@withContext ToolResult(false, "«${doc.name ?: "?"}» — директория; используйте saf_list.")
            }
            val size = doc.length()
            val offset = ((arguments["offset"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
                .coerceAtLeast(0L)
            // Bounded by MAX_SDK_READ_LIMIT so a model-supplied limit cannot allocate a huge buffer.
            val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: SafLimits.MAX_READ_BYTES.toLong())
                .coerceAtLeast(1L)
                .coerceAtMost(SafLimits.MAX_SDK_READ_LIMIT.toLong())

            // ContentResolver.openInputStream —
            // https://developer.android.com/reference/android/content/ContentResolver#openInputStream(android.net.Uri)
            val stream = context.contentResolver.openInputStream(doc.uri)
                ?: return@withContext ToolResult(false, "Не удалось открыть «${doc.name ?: "?"}» на чтение.")
            stream.use { raw ->
                val input = BufferedInputStream(raw)
                // Binary detection on the first up-to-8 KiB: a NUL byte marks a non-text file.
                val probe = ByteArray(SafLimits.BINARY_PROBE_BYTES)
                var probed = 0
                while (probed < probe.size) {
                    val n = input.read(probe, probed, probe.size - probed)
                    if (n < 0) break
                    probed += n
                }
                if ((0 until probed).any { probe[it] == 0.toByte() }) {
                    return@withContext ToolResult(
                        false,
                        "Это бинарный файл; используйте files_hash/files_stat. Размер: $size байт."
                    )
                }

                val buffer = ByteArray(limit.toInt())
                var filled = 0
                if (offset < probed) {
                    val from = offset.toInt()
                    val n = minOf(probed - from, buffer.size)
                    System.arraycopy(probe, from, buffer, 0, n)
                    filled = n
                }
                var toSkip = if (offset > probed) offset - probed else 0L
                while (toSkip > 0L) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0L) break
                    toSkip -= skipped
                }
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }

                // Java's String(byte[], int, int, Charset) replaces malformed input with U+FFFD.
                val text = String(buffer, 0, filled, Charsets.UTF_8)
                val moreRemains = if (size > 0L) size - offset > filled else filled >= buffer.size
                if (moreRemains) {
                    val total = if (size > 0L) size.toString() else "?"
                    ToolResult(
                        true,
                        text + "…[прочитано $filled байт со смещения $offset из $total байт]"
                    )
                } else {
                    ToolResult(true, text)
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
