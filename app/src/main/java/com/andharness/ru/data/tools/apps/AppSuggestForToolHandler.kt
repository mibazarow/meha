package com.andharness.ru.data.tools.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.andharness.ru.data.tools.files.FileOpenMime
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `app_suggest_for`: for ANY format/task, tells the agent which app is needed and — when none is
 * installed — offers a curated list of Google Play apps to install.
 *
 * It resolves the `format`/`task` argument to a MIME family (pure, [AppSuggestDecision]), then asks
 * the PackageManager whether an installed app can currently open that MIME. The check mirrors
 * [com.andharness.ru.data.tools.files.FileOpenLauncher.openFile]: an `ACTION_VIEW` intent carrying
 * the MIME is resolved BEFORE anything is claimed, so "a handler exists" is never invented. The
 * platform's own activity resolver (`android`, `com.android.internal.app`,
 * `com.android.intentresolver`) is not an app and is filtered out, so its presence does not count as
 * an installed handler.
 *
 * References:
 * - `Intent.ACTION_VIEW` + `setDataAndType` (the open-a-file intent the launcher uses):
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * - `PackageManager.queryIntentActivities` (installed handlers) and `MATCH_DEFAULT_ONLY`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#queryIntentActivities(android.content.Intent,%20int)
 *   https://developer.android.com/reference/android/content/pm/PackageManager#MATCH_DEFAULT_ONLY
 * - `market://` Play deep links and the web Play search fallback:
 *   https://developer.android.com/distribute/marketing-tools/linking-to-google-play
 */
internal class AppSuggestForToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "app_suggest_for"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val format = (arguments["format"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val task = (arguments["task"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val input = format.ifEmpty { task }
            if (input.isEmpty()) {
                return@withContext ToolResult(false, "Укажите format (MIME/расширение/тип) или task.")
            }
            val resolution = FileOpenMime.resolve(input)
            val installed = resolution.mime?.let { hasHandler(it) } ?: false
            val advice = AppSuggestDecision.advice(input, installed)
            ToolResult(advice.success, advice.text)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /** True when a real (non-resolver) installed app currently resolves ACTION_VIEW for [mime]. */
    private fun hasHandler(mime: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.andharness.ru.probe/file"), mime)
        }
        return context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .any { !it.isSystemResolver() }
    }

    /** The package names the platform uses for its own activity resolver / chooser (not an app). */
    private fun String.isSystemResolver(): Boolean =
        this == "android" || this == "com.android.internal.app" || this == "com.android.intentresolver"
}
