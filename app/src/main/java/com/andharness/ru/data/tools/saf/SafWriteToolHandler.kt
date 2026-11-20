package com.andharness.ru.data.tools.saf

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `saf_write`: writes UTF-8 text into a document inside a persisted tree grant. An overwrite first
 * preserves the current content in a sibling «<name>.bak» backup, and the write is post-verified by
 * byte count; every failure path restores from the backup this call made.
 *
 * `ContentResolver.openOutputStream(Uri, "wt")` (write + truncate):
 * https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)
 */
internal class SafWriteToolHandler(
    private val context: Context,
    private val access: SafDocumentAccess,
) : ToolHandler {

    override val name = "saf_write"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // Backup bookkeeping shared with [recover]: [backupOwned] is true only when THIS
        // call created [backupDoc], so a pre-existing «<name>.bak» document is never
        // touched (mirrors the `files_write` backup pattern).
        var backupDoc: DocumentFile? = null
        var backupOwned = false
        var targetDoc: DocumentFile? = null

        // Restores the previous document content from the backup taken by this call and
        // drops the backup again. Used by every failure path that runs after a backup was
        // made; without an owned backup it just appends nothing and reports [reason].
        fun recover(reason: String): ToolResult {
            val backup = backupDoc
            val target = targetDoc
            var suffix = ""
            if (backupOwned && backup != null && target != null) {
                backupOwned = false
                backupDoc = null
                // ContentResolver.openOutputStream(Uri, "wt") (write + truncate) —
                // https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)
                if (access.copyDocumentContent(backup.uri, target.uri)) {
                    val dropped = runCatching { backup.delete() }.getOrDefault(false)
                    suffix = " Исходный файл восстановлен из резервной копии."
                    if (!dropped) {
                        suffix += " Резервную копию «${backup.name ?: "?"}» удалить не удалось."
                    }
                } else {
                    suffix = " Восстановить исходный файл из резервной копии не удалось;" +
                        " копия сохранена как «${backup.name ?: "?"}»."
                }
            }
            return ToolResult(false, "$reason$suffix")
        }

        return@withContext try {
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
            val content = (arguments["content"] as? JsonPrimitive)?.content
                ?: return@withContext ToolResult(false, "Параметр content обязателен.")
            val overwrite = (arguments["overwrite"] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: true

            val fileName = segments.last()
            // Missing parents are created so a write into a new subfolder still succeeds.
            val parent = access.ensureDirectory(id, segments.dropLast(1))
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось получить папку назначения: «${path?.trim().orEmpty()}»."
                )

            val existing = parent.findFile(fileName)
            if (existing != null) {
                if (existing.isDirectory) {
                    return@withContext ToolResult(false, "«$fileName» — уже директория.")
                }
                // No silent clobber: an existing target with overwrite=false stays untouched.
                if (!overwrite) {
                    return@withContext ToolResult(
                        false,
                        "Файл уже существует: «$fileName». Перезапись отключена (overwrite=false)."
                    )
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            // MimeTypeMap.getMimeTypeFromExtension —
            // https://developer.android.com/reference/android/webkit/MimeTypeMap#getMimeTypeFromExtension(java.lang.String)
            val mime = if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } else {
                null
            }
            val mimeType = mime ?: "application/octet-stream"

            val doc = existing ?: parent.createFile(mimeType, fileName)
                ?: return@withContext ToolResult(false, "Не удалось создать файл: «$fileName».")
            targetDoc = doc

            // An overwrite first preserves the current content: a sibling «<name>.bak»
            // document is created in the SAME parent and the previous bytes are streamed into
            // it. A directory target gets a directory backup (there is no content to copy).
            // The backup is best-effort — when it cannot be produced the write still proceeds,
            // exactly like `files_write`.
            if (existing != null) {
                val backup = access.createBackup(parent, existing, fileName, mimeType)
                if (backup != null) {
                    backupDoc = backup
                    backupOwned = true
                }
            }

            val bytes = content.toByteArray(Charsets.UTF_8)
            // ContentResolver.openOutputStream(Uri, String) with "wt" (write + truncate) —
            // https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)
            val out = context.contentResolver.openOutputStream(doc.uri, "wt")
                ?: return@withContext recover("Не удалось открыть «$fileName» на запись.")
            out.use {
                it.write(bytes)
                it.flush()
            }

            // Post-write verification: the document must report exactly the UTF-8 byte count.
            val actual = doc.length()
            if (actual != bytes.size.toLong()) {
                return@withContext recover(
                    "Проверка после записи не пройдена: ожидалось ${bytes.size} байт, " +
                        "записано $actual."
                )
            }
            // Verified overwrite: drop the backup this call made. A failed deletion is
            // reported honestly and never turns a successful write into an error.
            val madeBackup = if (backupOwned) backupDoc else null
            backupOwned = false
            backupDoc = null
            val backupNote = if (madeBackup != null) {
                if (madeBackup.delete()) {
                    "Резервная копия удалена после проверки."
                } else {
                    "Запись выполнена, но резервную копию «${madeBackup.name ?: "$fileName.bak"}» удалить не удалось."
                }
            } else {
                null
            }
            ToolResult(
                true,
                "Записано ${bytes.size} байт: «$fileName»." +
                    if (backupNote != null) " $backupNote" else ""
            )
        } catch (e: Exception) {
            recover("Ошибка: ${e.message}")
        }
    }
}
