package com.andharness.ru.data.tools.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.tools.str
import java.io.File

/**
 * Builds one [ToolHandler] per "open / intent" tool: [open_url], [app_details], [open_settings],
 * [notification_settings_open], [app_notification_settings_open], [internet_panel_open],
 * [location_settings_open], [share_text], [dial]. These turn natural-language requests into the matching system UI using
 * official Android intents (no extra permissions required for the tools below).
 *
 * References (official Android docs):
 * - `Intent.ACTION_VIEW` — open a URI (browser for http/https):
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * - `Intent.ACTION_SEND` — share content through the system chooser:
 *   https://developer.android.com/reference/android/content/Intent#ACTION_SEND
 * - `Intent.ACTION_DIAL` — open the dialer pre-filled (does NOT place the call and needs no
 *   CALL_PHONE permission):
 *   https://developer.android.com/reference/android/content/Intent#ACTION_DIAL
 * - `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` — open the "App info" screen:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS
 * - `Settings` action constants (ACTION_WIFI_SETTINGS, ACTION_BLUETOOTH_SETTINGS, ...):
 *   https://developer.android.com/reference/android/provider/Settings
 * - `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (API 30+) — the app-scoped
 *   "All files access" screen:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 * - `ActivityNotFoundException` — thrown by `startActivity` when no app can handle the intent:
 *   https://developer.android.com/reference/android/content/ActivityNotFoundException
 */
class IntentToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "open_url"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val url = arguments.str("url")
                    if (url == null) return@withContext ToolResult(false, "Укажите url.")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return@withContext ToolResult(false, "Поддерживаются только http/https ссылки.")
                    }
                    start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "app_details"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pkg = arguments.str("package")
                    if (pkg == null) return@withContext ToolResult(false, "Укажите package.")
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$pkg"))
                    start(intent)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "open_settings"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val screen = arguments.str("screen")?.lowercase() ?: "main"
                    val action = settingsAction(screen)
                        ?: return@withContext ToolResult(
                            false,
                            "Неизвестный экран «$screen». Доступны: main, wifi, bluetooth, location, airplane, display, sound, battery, data, nfc, accessibility, developer, apps, all_files."
                        )
                    val intent = Intent(action)
                    if (screen == "all_files" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        intent.data = Uri.parse("package:${context.packageName}")
                    }
                    start(intent)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            // Opens the notification-settings screen. With a package, the documented app-scoped
            // action + EXTRA_APP_PACKAGE is used; without one, the global "apps that send
            // notifications" list. Note: there is NO plain `ACTION_NOTIFICATION_SETTINGS` constant
            // in the platform SDK (checked against android.jar API 35/37) — the global screen is
            // `ACTION_ALL_APPS_NOTIFICATION_SETTINGS` (API 33+).
            // https://developer.android.com/reference/android/provider/Settings#ACTION_APP_NOTIFICATION_SETTINGS
            // https://developer.android.com/reference/android/provider/Settings#ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            // https://developer.android.com/reference/android/provider/Settings#EXTRA_APP_PACKAGE
            override val name = "notification_settings_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pkg = arguments.str("package")
                    val intent = when {
                        pkg != null -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS)
                        else -> return@withContext ToolResult(
                            false,
                            "На этой версии Android нет отдельного экрана общих настроек " +
                                "уведомлений; укажите package."
                        )
                    }
                    start(intent)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            // https://developer.android.com/reference/android/provider/Settings#ACTION_APP_NOTIFICATION_SETTINGS
            override val name = "app_notification_settings_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pkg = arguments.str("package")
                        ?: return@withContext ToolResult(false, "Укажите package.")
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    start(intent)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            // The internet connectivity panel (Wi-Fi / mobile data quick toggle), API 29+. minSdk 31
            // guarantees the action exists at runtime.
            // https://developer.android.com/reference/android/provider/Settings.Panel#ACTION_INTERNET_CONNECTIVITY
            override val name = "internet_panel_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            // https://developer.android.com/reference/android/provider/Settings#ACTION_LOCATION_SOURCE_SETTINGS
            override val name = "location_settings_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "share_text"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = arguments.str("text")
                    if (text == null) return@withContext ToolResult(false, "Укажите text.")
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    val chooser = Intent.createChooser(send, "Поделиться через")
                    start(chooser)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "dial"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val phone = arguments.str("phone")
                    if (phone == null) return@withContext ToolResult(false, "Укажите phone.")
                    start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            // ACTION_VIEW opens a URI/document in another app; ACTION_EDIT asks for an editor.
            // https://developer.android.com/guide/components/intents-common#ViewFile
            // https://developer.android.com/reference/android/content/Intent#ACTION_EDIT
            // A local file is handed over as a FileProvider content URI (the FileUriExposed-safe
            // way); the read/write grant lets the receiver actually open it.
            // https://developer.android.com/reference/androidx/core/content/FileProvider
            override val name = "intent_open"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val rawUri = arguments.str("uri")
                        ?: return@withContext ToolResult(false, "Укажите uri.")
                    val actionParam = arguments.str("action")?.lowercase() ?: "view"
                    val action = when (actionParam) {
                        "view" -> Intent.ACTION_VIEW
                        "edit" -> Intent.ACTION_EDIT
                        else -> return@withContext ToolResult(
                            false,
                            "Неизвестное действие «$actionParam». Доступны: view, edit."
                        )
                    }
                    val mime = arguments.str("mime")
                    val targetPackage = arguments.str("package")
                    val chooser = arguments.str("chooser")?.equals("true", ignoreCase = true) == true

                    val target = when (val resolution = resolveOpenTarget(rawUri)) {
                        is OpenResolution.Ready -> resolution.target
                        is OpenResolution.Missing -> return@withContext ToolResult(
                            false,
                            if (resolution.resolved == resolution.given) {
                                "Файл не найден: «${resolution.given}»."
                            } else {
                                "Файл не найден: «${resolution.given}» (также искал «${resolution.resolved}»)."
                            }
                        )
                    }

                    val intent = Intent(action).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (target.localFile != null) {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (action == Intent.ACTION_EDIT) {
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                        }
                        val effectiveMime = mime ?: target.localFile?.let { mimeTypeOf(it) }
                        if (effectiveMime != null) {
                            setDataAndType(target.uri, effectiveMime)
                        } else {
                            data = target.uri
                        }
                        targetPackage?.let { setPackage(it) }
                    }

                    if (chooser) {
                        // https://developer.android.com/reference/android/content/Intent#createChooser(android.content.Intent,%20java.lang.CharSequence)
                        val chooserIntent = Intent.createChooser(intent, "Открыть через").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooserIntent)
                        return@withContext ToolResult(
                            true,
                            "Открыт системный выбор приложения для «$rawUri». Приложение ещё не выбрано."
                        )
                    }

                    // Resolve BEFORE starting: a start that matches nothing must not be success.
                    // https://developer.android.com/reference/android/content/pm/PackageManager#resolveActivity(android.content.Intent,%20int)
                    val component = intent.resolveActivity(context.packageManager)
                        ?: return@withContext ToolResult(false, "нет приложения, умеющего открыть это.")
                    context.startActivity(intent)
                    ToolResult(
                        true,
                        "Открыто «$rawUri» (действие $actionParam) через " +
                            "${component.flattenToShortString()}. Дальше в приложении действует " +
                            "пользователь — агент не подтверждает, что там что-то произошло."
                    )
                } catch (e: ActivityNotFoundException) {
                    ToolResult(false, "нет приложения, умеющего открыть это.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            // ACTION_SEND with an explicit package targets ONE app (no chooser).
            // https://developer.android.com/guide/components/intents-common#SendText
            // https://developer.android.com/reference/android/content/Intent#ACTION_SEND
            override val name = "intent_send_to"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pkg = arguments.str("package")
                        ?: return@withContext ToolResult(false, "Укажите package.")
                    val text = arguments.str("text")
                    val pathArg = arguments.str("path")
                    if (text == null && pathArg == null) {
                        return@withContext ToolResult(false, "Укажите text и/или path.")
                    }
                    val subject = arguments.str("subject")
                    val requestedMime = arguments.str("mime")

                    val send = Intent(Intent.ACTION_SEND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        setPackage(pkg)
                        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    }

                    var attachmentName = ""
                    if (pathArg != null) {
                        val file = File(pathArg)
                        if (!file.exists()) {
                            return@withContext ToolResult(false, "Файл не найден: «$pathArg».")
                        }
                        // Local file -> FileProvider content URI, plus the read grant.
                        // https://developer.android.com/reference/androidx/core/content/FileProvider
                        val uri = SharedFileContent.uriFor(context, file)
                        attachmentName = file.name
                        send.type = requestedMime ?: "*/*"
                        send.putExtra(Intent.EXTRA_STREAM, uri)
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        send.type = requestedMime ?: "text/plain"
                    }

                    // resolveActivity returns null when the package is missing or does not accept
                    // ACTION_SEND — reported honestly instead of a fake success.
                    // https://developer.android.com/reference/android/content/pm/PackageManager#resolveActivity(android.content.Intent,%20int)
                    val component = send.resolveActivity(context.packageManager)
                        ?: return@withContext ToolResult(
                            false,
                            "Приложение «$pkg» не установлено или не умеет принимать такие данные."
                        )
                    context.startActivity(send)
                    val kind = when {
                        pathArg != null && text != null -> "текст и файл «$attachmentName»"
                        pathArg != null -> "файл «$attachmentName»"
                        else -> "текст"
                    }
                    ToolResult(true, "Отправил $kind в «$pkg» через ${component.flattenToShortString()}.")
                } catch (e: ActivityNotFoundException) {
                    ToolResult(false, "Приложение не установлено или не умеет принимать ACTION_SEND.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    /** A resolved open target: a ready-to-use [Uri] and, for local files, the backing [File]. */
    private data class OpenTarget(val uri: Uri, val localFile: File?)

    /** Outcome of [resolveOpenTarget]: either a usable target or the two paths that were tried. */
    private sealed interface OpenResolution {
        data class Ready(val target: OpenTarget) : OpenResolution
        data class Missing(val given: String, val resolved: String) : OpenResolution
    }

    /**
     * Turns the `uri` argument into an [OpenResolution]. `content://` and scheme URIs are used as-is;
     * a plain local path is first tried as given (absolute) and, when that does not exist, resolved
     * against [Context.effectiveToolRoot] via [resolveSafePath] — the same tool-root convention the
     * file/image tools use, so `Pictures/x.jpg` works like it does for `files_*` / `image_*`.
     * `file://` URIs are checked as-is. Missing local files report both attempted paths.
     * - `java.io.File#exists()` — https://developer.android.com/reference/java/io/File#exists()
     * - `androidx.core.content.FileProvider` —
     *   https://developer.android.com/reference/androidx/core/content/FileProvider
     */
    private fun resolveOpenTarget(raw: String): OpenResolution {
        val trimmed = raw.trim()
        val schemeLess = !trimmed.contains("://")
        val file: File? = when {
            trimmed.startsWith("content://") -> null
            trimmed.startsWith("file://") -> Uri.parse(trimmed).path?.let { File(it) }
            schemeLess -> {
                val direct = File(trimmed)
                if (direct.exists()) {
                    direct
                } else {
                    val resolved = try {
                        resolveSafePath(context.effectiveToolRoot(), trimmed)
                    } catch (e: IllegalArgumentException) {
                        return OpenResolution.Missing(trimmed, trimmed)
                    }
                    if (!resolved.exists()) return OpenResolution.Missing(trimmed, resolved.absolutePath)
                    resolved
                }
            }
            else -> null
        }
        if (file == null) return OpenResolution.Ready(OpenTarget(Uri.parse(trimmed), null))
        if (!file.exists()) return OpenResolution.Missing(trimmed, file.absolutePath)
        return OpenResolution.Ready(
            OpenTarget(SharedFileContent.uriFor(context, file), file)
        )
    }

    /**
     * MIME type from the file extension, falling back to star-slash-star for unknown extensions.
     * https://developer.android.com/reference/android/webkit/MimeTypeMap#getMimeTypeFromExtension(java.lang.String)
     */
    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    /** Starts [intent] and reports the user-visible outcome truthfully. */
    private fun start(intent: Intent): ToolResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult(
                true,
                "Открыто на устройстве. Дальнейшие действия в этом экране выполняет " +
                    "пользователь — сам агент их не сделал и не может подтвердить их результат.",
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult(false, "На устройстве нет приложения, которое обрабатывает это действие.")
        }
    }

    private fun settingsAction(screen: String): String? = when (screen) {
        "main", "settings" -> Settings.ACTION_SETTINGS
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
        "nfc" -> Settings.ACTION_NFC_SETTINGS
        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        "apps" -> Settings.ACTION_APPLICATION_SETTINGS
        "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        else -> null
    }

}
