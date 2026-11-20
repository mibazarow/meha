package com.andharness.ru.data.tools.apps

import android.content.Context
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.update.AndroidInstallLauncher
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * `app_install_apk`: hands a local (safe-root) or downloaded APK to the system package installer.
 * It is always honest that the user must tap «Установить»: `Intent.ACTION_INSTALL_PACKAGE` shows a
 * confirmation dialog and the agent can never observe its result, and
 * `PackageManager.canRequestPackageInstalls()` gates whether the app may request installs at all.
 *
 * References:
 * - https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE
 * - https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
 * - https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
 * - OkHttp streaming download (`ResponseBody.byteStream()`): https://square.github.io/okhttp/
 */
internal class AppInstallApkToolHandler(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) : ToolHandler {

    override val name = "app_install_apk"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val source = AppInstallApkPolicy.decideSource(
                path = (arguments["path"] as? JsonPrimitive)?.content,
                url = (arguments["url"] as? JsonPrimitive)?.content,
            )
            var downloadedNote: String? = null
            val apkFile: File = when (source) {
                is AppInstallApkPolicy.Source.Invalid ->
                    return@withContext ToolResult(false, source.message)

                is AppInstallApkPolicy.Source.LocalFile -> {
                    val file = try {
                        resolveSafePath(context.allowedToolRoots(), source.path)
                    } catch (e: IllegalArgumentException) {
                        return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
                    }
                    val check = AppInstallApkPolicy.validateLocalApk(
                        displayName = source.path,
                        exists = file.exists(),
                        isFile = file.isFile,
                        sizeBytes = file.length(),
                    )
                    if (!check.ok) return@withContext ToolResult(false, check.message)
                    file
                }

                is AppInstallApkPolicy.Source.Remote -> {
                    val file = downloadApkToCache(source.url)
                        ?: return@withContext ToolResult(
                            false,
                            "Не удалось скачать APK по ссылке «${source.url}» " +
                                "(проверьте ссылку и соединение).",
                        )
                    val check = AppInstallApkPolicy.validateLocalApk(
                        displayName = file.name,
                        exists = file.exists(),
                        isFile = file.isFile,
                        sizeBytes = file.length(),
                    )
                    if (!check.ok) return@withContext ToolResult(false, check.message)
                    downloadedNote = AppInstallApkPolicy.downloadedMessage(
                        fileName = file.name,
                        absolutePath = file.absolutePath,
                        sizeBytes = file.length(),
                    )
                    file
                }
            }
            // Android only lets an app reach the installer after the user grants the
            // "install unknown apps" special access for THIS app; without it the system
            // refuses the install. Return the honest PERMISSION_REQUIRED marker so the chat
            // opens exactly that setting screen, then stops here (nothing was installed).
            if (!AndroidInstallLauncher.canRequestPackageInstalls(context)) {
                return@withContext ToolResult(false, AppInstallApkPolicy.notAllowedResult())
            }
            // A local APK may live anywhere under the tool roots (e.g. Download/…), outside the
            // scoped FileProvider; stage a cache copy for anything the provider cannot expose
            // directly, so the installer still receives a readable content URI.
            val installFile = SharedFileContent.providerAccessibleFile(context, apkFile)
            if (!AndroidInstallLauncher.install(context, installFile)) {
                return@withContext ToolResult(
                    false,
                    "Не удалось открыть системный установщик для «${apkFile.name}».",
                )
            }
            ToolResult(
                true,
                listOfNotNull(downloadedNote, AppInstallApkPolicy.dialogShownMessage(apkFile.name))
                    .joinToString("\n"),
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Streams [url] into a fresh `.apk` file under the app's private cache directory. That location
     * is already covered by the shared FileProvider (`<cache-path name="cache" path="."/>` in
     * `res/xml/file_paths.xml`), so [AndroidInstallLauncher] can hand it a readable content URI.
     *
     * Bounded and honest: the body is capped at [AppInstallApkPolicy.MAX_DOWNLOAD_BYTES], stale
     * cache APKs are removed first to keep the cache bounded, and any failure (HTTP error, empty
     * body, oversized file, failed rename) deletes its temp file and returns `null` — this function
     * never throws. The file name is derived defensively by [AppInstallApkPolicy.apkFileNameFromUrl].
     */
    private fun downloadApkToCache(url: String): File? {
        val dir = File(context.cacheDir, AppToolLimits.APK_CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
        // Keep at most this run's APK: drop leftovers from previous attempts/dialogs.
        dir.listFiles()?.forEach { stale ->
            if (stale.isFile && (stale.name.endsWith(AppInstallApkPolicy.APK_EXTENSION, ignoreCase = true) ||
                    stale.name.endsWith(".part", ignoreCase = true))
            ) {
                stale.delete()
            }
        }
        val target = File(dir, AppInstallApkPolicy.apkFileNameFromUrl(url))
        val temp = File(dir, ".apk-${System.nanoTime()}.part")
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Пустой ответ сервера")
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(AppToolLimits.COPY_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            written += read
                            if (written > AppInstallApkPolicy.MAX_DOWNLOAD_BYTES) {
                                throw IOException(
                                    "APK превышает лимит ${AppInstallApkPolicy.MAX_DOWNLOAD_BYTES} байт.",
                                )
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            when {
                temp.length() <= 0L -> {
                    temp.delete()
                    null
                }
                target.exists() && !target.delete() -> {
                    temp.delete()
                    null
                }
                !temp.renameTo(target) -> {
                    temp.delete()
                    null
                }
                else -> target
            }
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }
}
