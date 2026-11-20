package com.andharness.ru.data.tools.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `share_file`: opens the system share chooser for one file. */
internal class ShareFileToolHandler(
    private val context: Context,
    private val io: FileToolIo,
) : ToolHandler {
    override val name = "share_file"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
        val pathArg = io.path(arguments, "path")
        val file = File(pathArg)
        if (!file.isFile) return ToolResult(false, "Не файл: «$pathArg».")
        val uri = SharedFileContent.uriFor(context, file)
        val mime = io.mimeTypeOf(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // A chooser ALWAYS resolves, so "startActivity(createChooser(...)) did not throw"
        // proves nothing about whether a receiver exists (A11). Ask the PackageManager for
        // real ACTION_SEND targets first: an empty list is an honest error, not a success.
        //
        // Package visibility (Android 11+): queryIntentActivities returns an empty list for
        // intents that are not visible to us. The app declares QUERY_ALL_PACKAGES plus a
        // <queries> block (ACTION_VIEW / ACTION_SEND) in AndroidManifest.xml, so this check
        // is meaningful rather than a false negative.
        // - https://developer.android.com/training/package-visibility
        // - https://developer.android.com/reference/android/content/pm/PackageManager#queryIntentActivities(android.content.Intent,%20int)
        @Suppress("DEPRECATION")
        val targets = context.packageManager.queryIntentActivities(send, 0)
        if (targets.isEmpty()) {
            return ToolResult(false, "Нет приложения, которому можно отправить файл типа $mime.")
        }
        val chooser = Intent.createChooser(send, "Поделиться файлом").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        // Only the chooser was shown — the file was NOT sent anywhere yet.
        ToolResult(
            true,
            "Открыл диалог «Поделиться» для ${file.name} (тип $mime). " +
                "Доступно приложений: ${targets.size}. Файл пока не отправлен — нужен выбор пользователя."
        )
    } catch (e: ActivityNotFoundException) {
        ToolResult(false, "Нет приложения для отправки файла.")
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
    }
}
