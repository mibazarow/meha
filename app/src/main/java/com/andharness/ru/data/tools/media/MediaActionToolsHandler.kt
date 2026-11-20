package com.andharness.ru.data.tools.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The `media_open` / `media_share` tools: act on an item a search found (a `content://` URI or a
 * local path) by handing it to the right system app.
 *
 * - `media_open` opens it directly with the system viewer (`ACTION_VIEW`), no chooser: the gallery /
 *   preview for an image, the reader for a document.
 * - `media_share` sends it to another app via the system share sheet (`ACTION_SEND`).
 *
 * The `uri` argument accepts BOTH a `content://` URI and an absolute file path; a local path is
 * turned into a `FileProvider` content URI (the `FileUriExposed`-safe way) exactly as `intent_open`
 * does. The receiver is granted read access with `FLAG_GRANT_READ_URI_PERMISSION`, so no new runtime
 * permission is needed beyond being able to read the returned URI.
 *
 * References (official Android docs):
 * - `Intent.ACTION_VIEW` (open a URI/document with the system viewer) —
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * - `Intent#setDataAndType` (set the data URI and MIME type together) —
 *   https://developer.android.com/reference/android/content/Intent#setDataAndType(android.net.Uri,%20java.lang.String)
 * - `Intent.ACTION_SEND` + `EXTRA_STREAM` (share a single piece of content) —
 *   https://developer.android.com/reference/android/content/Intent#ACTION_SEND and
 *   https://developer.android.com/reference/android/content/Intent#EXTRA_STREAM
 * - `Intent.FLAG_GRANT_READ_URI_PERMISSION` (grant the receiver read access to the URI) —
 *   https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION
 * - `ContentResolver#getType` (the provider's MIME type for a URI) —
 *   https://developer.android.com/reference/android/content/ContentResolver#getType(android.net.Uri)
 * - `FileProvider` (content URIs for app-owned/local files) —
 *   https://developer.android.com/reference/androidx/core/content/FileProvider
 * - `ActivityNotFoundException` (thrown by `startActivity` when nothing handles the intent) —
 *   https://developer.android.com/reference/android/content/ActivityNotFoundException
 */
class MediaActionToolsHandler(
    private val context: Context,
) {

    /** The two handlers, one per tool name; consumed by the Hilt tool-handler multibinding. */
    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "media_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val raw = arguments.str("uri")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите uri — ссылку content://… или путь к файлу.",
                        )
                    val target = resolveTarget(raw)
                        ?: return@withContext ToolResult(false, "Файл не найден: «$raw».")
                    val mime = MediaActionPlanner.resolveMime(arguments.str("mime"), detectedMime(target))

                    // ACTION_VIEW opens the item in the system viewer (gallery for a photo, reader
                    // for a document); the shared MediaViewer builds the same intent the chat's
                    // tap-to-open previews use (URI + resolved MIME, read grant, no chooser).
                    val intent = MediaViewer.viewIntent(target.uri, mime)
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        // Honest failure instead of a fake success: nothing can open this type.
                        return@withContext ToolResult(
                            false,
                            "На устройстве нет приложения, которое умеет открывать «$raw» (тип $mime).",
                        )
                    }
                    val via = intent.resolveActivity(context.packageManager)
                        ?.flattenToShortString()
                        ?.let { " в $it" }
                        .orEmpty()
                    ToolResult(
                        true,
                        "Открыто «${target.name(raw)}» (тип $mime)$via. Просмотр и любые " +
                            "действия в приложении выполняет пользователь.",
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось открыть: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "media_share"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val raw = arguments.str("uri")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите uri — ссылку content://… или путь к файлу.",
                        )
                    val target = resolveTarget(raw)
                        ?: return@withContext ToolResult(false, "Файл не найден: «$raw».")
                    val mime = MediaActionPlanner.resolveMime(arguments.str("mime"), detectedMime(target))
                    val text = arguments.str("text")

                    // ACTION_SEND carries the URI as a stream; the standard share sheet lets the
                    // user pick the target app.
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, target.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    }
                    // A chooser is always resolvable, so the honest report is simply that the share
                    // sheet opened; if no app accepts ACTION_SEND the system shows that itself.
                    val chooser = Intent.createChooser(send, "Поделиться").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    ToolResult(
                        true,
                        "Открыто меню «Поделиться» для «${target.name(raw)}» (тип $mime). " +
                            "Отправку выполняет пользователь: агент не знает, выбрано ли приложение " +
                            "и отправлено ли что-то.",
                    )
                } catch (e: ActivityNotFoundException) {
                    ToolResult(false, "На устройстве нет приложения для «Поделиться».")
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось поделиться: ${e.message}")
                }
            }
        },
    )

    /** A resolved hand-off target: a ready-to-use [Uri] plus, for local files, the backing [File]. */
    private data class Target(val uri: Uri, val localFile: File?) {
        /** The file name for a local file, otherwise the raw argument (for the user-facing message). */
        fun name(raw: String): String = localFile?.name ?: raw
    }

    /**
     * Turns the `uri` argument into a [Target]. `content://` and other scheme URIs are used as-is; a
     * plain local path — absolute or relative to [Context.effectiveToolRoot] — becomes a
     * `FileProvider` content URI. Returns `null` when a referenced local file does not exist.
     * - `java.io.File#exists()` — https://developer.android.com/reference/java/io/File#exists()
     * - `androidx.core.content.FileProvider` —
     *   https://developer.android.com/reference/androidx/core/content/FileProvider
     */
    private fun resolveTarget(raw: String): Target? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val path = Uri.parse(trimmed).path ?: return null
            return localTarget(File(path))
        }
        if (MediaActionPlanner.hasScheme(trimmed)) {
            // content://, http(s)://, and any other provider URI: hand it over as-is.
            return Target(Uri.parse(trimmed), null)
        }
        val file = File(trimmed).takeIf { it.exists() }
            ?: try {
                resolveSafePath(context.effectiveToolRoot(), trimmed)
            } catch (e: IllegalArgumentException) {
                return null
            }
        return localTarget(file)
    }

    private fun localTarget(file: File): Target? {
        if (!file.exists()) return null
        return Target(SharedFileContent.uriFor(context, file), file)
    }

    /**
     * The MIME type detected for [target]: the provider's type for a `content://` URI
     * (`ContentResolver.getType`) or the extension's type for a local file. May be `null`.
     */
    private fun detectedMime(target: Target): String? {
        val local = target.localFile
        if (local == null) {
            return try {
                context.contentResolver.getType(target.uri)
            } catch (e: Exception) {
                null
            }
        }
        // https://developer.android.com/reference/android/webkit/MimeTypeMap#getMimeTypeFromExtension(java.lang.String)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(local.extension.lowercase())
    }
}
