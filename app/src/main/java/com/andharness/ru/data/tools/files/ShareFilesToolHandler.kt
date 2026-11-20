package com.andharness.ru.data.tools.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.TransactionTooLargeException
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `share_files`: opens the system share chooser for several files at once. */
internal class ShareFilesToolHandler(
    private val context: Context,
    private val io: FileToolIo,
) : ToolHandler {
    override val name = "share_files"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val pathsRaw = (arguments["paths"] as? JsonPrimitive)?.content.orEmpty()
            val names = pathsRaw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) {
                return ToolResult(false, "Укажите paths — пути к файлам через запятую.")
            }
            // Validate EVERY element through resolveSafePath against every allowed root,
            // which rejects `..` and any canonical result escaping those roots (A1). An
            // element that is unsafe or not an existing file is collected and reported —
            // it is NEVER silently dropped from the share set.
            val invalid = mutableListOf<String>()
            val missing = mutableListOf<String>()
            val files = mutableListOf<File>()
            for (name in names) {
                val resolved = try {
                    resolveSafePath(io.roots(), name)
                } catch (e: IllegalArgumentException) {
                    invalid += "«$name» (${e.message})"
                    continue
                }
                if (!resolved.isFile) {
                    missing += "«$name»"
                    continue
                }
                files += resolved
            }
            if (invalid.isNotEmpty() || missing.isNotEmpty()) {
                val parts = mutableListOf<String>()
                if (invalid.isNotEmpty()) parts += "некорректные: ${invalid.joinToString("; ")}"
                if (missing.isNotEmpty()) {
                    parts += "не найдены (или не файлы): ${missing.joinToString(", ")}"
                }
                return ToolResult(
                    false,
                    "Не удалось поделиться: ${parts.joinToString("; ")}. Ничего не отправлено."
                )
            }

            val mime = (arguments["mime"] as? JsonPrimitive)?.content?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: FileToolIo.GENERIC_MIME
            val uris = files.map { SharedFileContent.uriFor(context, it) }

            // ACTION_SEND_MULTIPLE hands several attachments to one target; the content
            // URIs travel in EXTRA_STREAM as a parcelable ArrayList (a 1-file send is still
            // a valid ACTION_SEND_MULTIPLE).
            // - https://developer.android.com/reference/android/content/Intent#ACTION_SEND_MULTIPLE
            // - https://developer.android.com/reference/android/content/Intent#EXTRA_STREAM
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Modern Android ignores FLAG_GRANT_READ_URI_PERMISSION on the EXTRA_STREAM
            // extras unless the same URIs are also carried by a ClipData, so the read grant
            // is propagated explicitly to every attached file.
            // https://developer.android.com/reference/android/content/ClipData
            val clip = ClipData.newUri(context.contentResolver, "files", uris.first())
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            send.clipData = clip

            // A chooser ALWAYS resolves, so "startActivity(createChooser(...)) did not throw"
            // proves nothing about whether a receiver exists (A11). Ask the PackageManager
            // for real ACTION_SEND_MULTIPLE targets first: an empty list is an honest error.
            // Package visibility (Android 11+): the app declares QUERY_ALL_PACKAGES plus a
            // <queries> block in AndroidManifest.xml, so this check is meaningful rather
            // than a false negative.
            // - https://developer.android.com/training/package-visibility
            // - https://developer.android.com/reference/android/content/pm/PackageManager#queryIntentActivities(android.content.Intent,%20int)
            @Suppress("DEPRECATION")
            val targets = context.packageManager.queryIntentActivities(send, 0)
            if (targets.isEmpty()) {
                return ToolResult(false, "Нет приложения, которому можно отправить файлы типа $mime.")
            }
            val chooser = Intent.createChooser(send, "Поделиться файлами").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            // Only the chooser was shown — the files were NOT sent anywhere yet.
            ToolResult(
                true,
                "Открыл диалог «Поделиться» для ${files.size} файлов (тип $mime). " +
                    "Доступно приложений: ${targets.size}. Файлы пока не отправлены — " +
                    "нужен выбор пользователя."
            )
        } catch (e: TransactionTooLargeException) {
            ToolResult(
                false,
                "Слишком большой набор файлов для передачи через Intent: ${e.message}. " +
                    "Попробуйте поделиться меньшим числом файлов."
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult(false, "Нет приложения для отправки файлов.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
