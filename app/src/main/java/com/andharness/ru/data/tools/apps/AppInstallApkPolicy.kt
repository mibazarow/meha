package com.andharness.ru.data.tools.apps

import com.andharness.ru.data.tools.files.sanitizeFileName
import com.andharness.ru.data.tools.formatBytes
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure (Android-free) decision logic for the [app_install_apk] tool: which source to use (a local
 * `.apk` path or a URL to download), how to turn a URL into a safe `.apk` file name, whether a local
 * file really looks like an installable APK, and the honest user-facing result texts.
 *
 * It has no Android imports so every rule below is unit-testable on the JVM. The Android side
 * (`AppsToolsHandler`) only performs the I/O — the download, the permission check and the system
 * installer launch — and delegates every decision to this object.
 *
 * References (why the decisions are shaped this way):
 * - Install-unknown-apps gate (`PackageManager.canRequestPackageInstalls`) and its settings screen
 *   (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, optionally scoped to a package):
 *   https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
 * - `Intent.ACTION_INSTALL_PACKAGE` always shows the system confirmation dialog, so the agent can
 *   never know the final outcome and must not claim the app was installed:
 *   https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE
 * - RFC 6266 `Content-Disposition` / `okhttp3.HttpUrl` path-segment parsing for file names:
 *   https://www.rfc-editor.org/rfc/rfc6266
 */
object AppInstallApkPolicy {

    /** File-name extension an install candidate must carry (case-insensitive). */
    const val APK_EXTENSION = ".apk"

    /**
     * Cap on a downloaded APK: 200 MiB, matching `download_file`'s [DEFAULT_MAX_BYTES]. A larger
     * body is aborted honestly instead of filling the cache partition and risking a crash.
     */
    const val MAX_DOWNLOAD_BYTES: Long = 200L * 1024 * 1024

    /**
     * [com.andharness.ru.domain.permission.SpecialAccess] token that maps to the "install unknown
     * apps" system setting. Kept here as a plain string so this file stays Android-free; the handler
     * uses `SpecialAccess.required(SpecialAccess.INSTALL_UNKNOWN_APPS)`, which yields the same
     * `PERMISSION_REQUIRED:<token>` marker.
     */
    const val UNKNOWN_APP_SOURCES_TOKEN = "special:install_unknown_apps"

    /** Where the APK to install comes from. */
    sealed interface Source {
        /** A path already on the device, resolved through the shared safe-path helper. */
        data class LocalFile(val path: String) : Source

        /** A remote URL: the handler downloads it into the app cache, then installs it. */
        data class Remote(val url: String) : Source

        /** Neither usable input was given; [message] is the honest reason. */
        data class Invalid(val message: String) : Source
    }

    /** Outcome of validating a candidate APK already on disk. */
    data class Validation(val ok: Boolean, val message: String)

    /**
     * Picks the source for one `app_install_apk` call. An explicit non-blank `path` always wins
     * (the caller asked for a local file); otherwise a non-blank `url` is downloaded; when both are
     * absent the call is rejected honestly. Mirrors the tool's documented contract.
     */
    fun decideSource(path: String?, url: String?): Source {
        val localPath = path?.trim().orEmpty()
        val remoteUrl = url?.trim().orEmpty()
        return when {
            localPath.isNotEmpty() -> Source.LocalFile(localPath)
            remoteUrl.isNotEmpty() -> Source.Remote(remoteUrl)
            else -> Source.Invalid("Укажите path к локальному .apk или url, откуда его скачать.")
        }
    }

    /** True when [name] carries the `.apk` extension (case-insensitive) with a non-empty base. */
    fun isApkFileName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.length > APK_EXTENSION.length && trimmed.endsWith(APK_EXTENSION, ignoreCase = true)
    }

    /**
     * Derives the cache file name for a downloaded APK from [url]'s last path segment (sanitized),
     * appending `.apk` when the segment has no such extension. Falls back to `install.apk` when the
     * URL is unparseable or has no usable segment, so a download can never produce a nameless file.
     */
    fun apkFileNameFromUrl(url: String): String {
        val segment = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
        val base = sanitizeFileName(segment) ?: DEFAULT_DOWNLOAD_NAME
        return if (isApkFileName(base)) base else "$base$APK_EXTENSION"
    }

    /**
     * Validates a local candidate by its observable facts (existence, file-ness, extension, size).
     * Kept pure so the handler passes `File` properties in and the rules are unit-tested directly.
     */
    fun validateLocalApk(
        displayName: String,
        exists: Boolean,
        isFile: Boolean,
        sizeBytes: Long,
    ): Validation = when {
        !exists -> Validation(false, "APK не найден: «$displayName».")
        !isFile -> Validation(false, "Путь «$displayName» не является файлом.")
        !isApkFileName(displayName) ->
            Validation(false, "Файл «$displayName» не является APK (нужно расширение .apk).")
        sizeBytes <= 0L ->
            Validation(false, "Файл «$displayName» пуст (0 байт) — скачайте APK заново.")
        else -> Validation(true, "")
    }

    /**
     * Result marker telling the orchestrator the user must grant the "install unknown apps" special
     * access; the chat then opens that exact system screen. This is an honest failure, not an install.
     */
    fun notAllowedResult(): String = "PERMISSION_REQUIRED:$UNKNOWN_APP_SOURCES_TOKEN"

    /**
     * Honest text for a successfully downloaded APK (before the permission check/launch): names the
     * saved file and its size, so the model can report where it went.
     */
    fun downloadedMessage(fileName: String, absolutePath: String, sizeBytes: Long): String =
        "APK скачан: «$fileName» (${formatBytes(sizeBytes)}) → $absolutePath."

    /**
     * Honest text after the system installer was launched. It never claims the app was installed:
     * Android requires the user to tap «Установить», and the outcome is unknowable to the agent.
     */
    fun dialogShownMessage(fileName: String): String =
        "Открыт системный установщик для «$fileName» — подтвердите установку кнопкой «Установить» " +
            "в диалоге. Агент не может установить APK молча и не знает итог установки: после " +
            "подтверждения проверьте результат через app_is_installed."

    /** Fallback base name for a URL with no usable last path segment. */
    private const val DEFAULT_DOWNLOAD_NAME = "install"
}
