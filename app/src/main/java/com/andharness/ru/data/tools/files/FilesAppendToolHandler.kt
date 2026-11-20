package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * `files_append`: appends UTF-8 text to a file, fsyncing and verifying the new length.
 *
 * [pathResolver] resolves the required `path` argument to a canonical absolute path (in production
 * this is [FileToolIo.path]); tests pass a lambda over a temp dir to verify append semantics without
 * an Android Context.
 */
internal class FilesAppendToolHandler(
    private val pathResolver: (JsonObject, String) -> String,
) : ToolHandler {
    override val name = "files_append"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
        val file = File(pathResolver(arguments, "path"))
        val content = (arguments["content"] as? JsonPrimitive)?.content ?: ""
        // BUG-050: same single-call size guard as files_write — an over-size append is rejected with
        // an honest "split into parts" error rather than reaching disk.
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > FileToolIo.MAX_SINGLE_WRITE_BYTES) {
            return ToolResult(false, FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE)
        }
        file.parentFile?.mkdirs()
        val before = if (file.exists()) file.length() else 0L

        // Append the explicit UTF-8 bytes (never the platform default charset) into a
        // buffered stream, then flush and fsync so the appended data survives a crash.
        // java.io.FileOutputStream(File, boolean) —
        // https://developer.android.com/reference/java/io/FileOutputStream
        // FileDescriptor.sync() —
        // https://developer.android.com/reference/java/io/FileDescriptor#sync()
        val fos = FileOutputStream(file, true)
        try {
            val out = fos.buffered()
            out.write(bytes)
            out.flush()
            fos.fd.sync()
        } finally {
            fos.close()
        }

        // Verification: the file must have grown by exactly the appended byte count.
        val after = file.length()
        if (after != before + bytes.size) {
            return ToolResult(
                false,
                "Проверка после дозаписи не пройдена: ожидалось ${before + bytes.size} байт, фактически $after."
            )
        }
        ToolResult(true, "Дописано ${bytes.size} байт в ${file.name}")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
