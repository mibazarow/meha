package com.andharness.ru.data.tools.apps

import android.content.Context
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `app_backup_apk`: copies a package's base + split APKs into the safe tool root. */
internal class AppBackupApkToolHandler(
    private val context: Context,
    private val inspector: AppPackageInspector,
) : ToolHandler {

    override val name = "app_backup_apk"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val pkg = (arguments["package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (pkg.isEmpty()) return@withContext ToolResult(false, "Укажите package.")
            val pm = context.packageManager
            val app = inspector.load(pm, pkg, 0)?.applicationInfo
                ?: return@withContext ToolResult(
                    true,
                    "Приложение «$pkg» не найдено или недоступно (ограничения видимости пакетов).",
                )
            val rawDest = (arguments["destPath"] as? JsonPrimitive)?.content?.trim()
            val destDir = try {
                resolveSafePath(
                    context.allowedToolRoots(),
                    rawDest?.takeIf { it.isNotEmpty() } ?: AppToolLimits.DEFAULT_BACKUP_DIR,
                )
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                return@withContext ToolResult(false, "Не удалось создать папку «${destDir.absolutePath}».")
            }
            if (!destDir.isDirectory) {
                return@withContext ToolResult(false, "Путь «${destDir.absolutePath}» не является папкой.")
            }
            val apks = buildList {
                app.sourceDir?.let { add(File(it)) }
                app.splitSourceDirs?.forEach { add(File(it)) }
            }.distinct().filter { it.isFile }
            if (apks.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    "APK-файлы приложения «$pkg» недоступны для копирования (нет sourceDir).",
                )
            }
            val lines = ArrayList<String>()
            var total = 0L
            var copied = 0
            for (src in apks) {
                val target = File(destDir, src.name)
                try {
                    src.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val size = target.length()
                    total += size
                    copied += 1
                    lines.add("${src.name} — ${formatBytes(size)} → ${target.absolutePath}")
                } catch (e: Exception) {
                    lines.add("${src.name} — не удалось скопировать: ${e.message}")
                }
            }
            ToolResult(
                copied > 0,
                buildString {
                    appendLine(
                        "Резервная копия APK «$pkg» → ${destDir.absolutePath}: " +
                            "скопировано $copied из ${apks.size} файл(ов), всего ${formatBytes(total)}."
                    )
                    lines.forEach { appendLine(it) }
                }.trim(),
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
