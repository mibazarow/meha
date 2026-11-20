package com.andharness.ru.data.tools.files

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.domain.agent.ToolResult
import java.io.File

/**
 * Opens files/directories with external apps, resolving every intent with the PackageManager
 * before anything is started so an activity start that matches nothing is never reported as a
 * success (A11).
 *
 * Unlike a plain `Intent.createChooser`, this launcher picks ONE app itself and starts the intent
 * with `setPackage`, so the system "open with" chooser never appears:
 *  1. a previously stored pick for the MIME type ([FileOpenChoiceStore]);
 *  2. the user's current system default handler;
 *  3. the best-ranked handler the PackageManager returns;
 *  4. an explicit package passed by the caller (`app` argument) overrides all of the above.
 * When nothing can open the type the tool returns an honest failure with curated Google Play
 * suggestions (see [FileOpenMime]) instead of a chooser that matches nothing.
 *
 * References:
 * - `Intent.ACTION_VIEW` / `setDataAndType`:
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * - `Intent.setPackage` (explicit target → no chooser):
 *   https://developer.android.com/reference/android/content/Intent#setPackage(java.lang.String)
 * - `Intent.FLAG_GRANT_READ_URI_PERMISSION` (let the receiver read the content URI):
 *   https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION
 * - `PackageManager.resolveActivity` (the default handler) and `MATCH_DEFAULT_ONLY`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#resolveActivity(android.content.Intent,%20int)
 *   https://developer.android.com/reference/android/content/pm/PackageManager#MATCH_DEFAULT_ONLY
 * - `PackageManager.queryIntentActivities` (handlers ordered best-first):
 *   https://developer.android.com/reference/android/content/pm/PackageManager#queryIntentActivities(android.content.Intent,%20int)
 * - `androidx.core.content.FileProvider`:
 *   https://developer.android.com/reference/androidx/core/content/FileProvider
 */
internal class FileOpenLauncher(
    private val context: Context,
    private val io: FileToolIo,
    private val choices: FileOpenChoiceStore,
) {

    /** Where a resolved package came from; a system default is never persisted (see [openFile]). */
    private enum class Source { STORED, SYSTEM_DEFAULT, RANKED, FORCED }

    private data class Resolved(val packageName: String, val source: Source)

    /**
     * Opens [file] in a single app without showing the system chooser.
     *
     * @param forcedPackage when non-null, opened in exactly this package (the `app` argument),
     *   bypassing automatic resolution; a package that cannot handle the MIME fails honestly.
     *
     * The intent is resolved BEFORE it is started, so a start that silently matches nothing is
     * reported as a failure rather than as a fake success (A11). A successful pick is remembered for
     * its MIME type, except when it was the system default (which must stay free to change).
     */
    fun openFile(file: File, forcedPackage: String? = null): ToolResult {
        val uri = SharedFileContent.uriFor(context, file)
        val mime = io.mimeTypeOf(file)

        val resolved = when {
            forcedPackage != null && canHandle(uri, mime, forcedPackage) ->
                Resolved(forcedPackage, Source.FORCED)
            forcedPackage != null ->
                return ToolResult(
                    false,
                    "Приложение «$forcedPackage» не установлено или не умеет открывать файлы типа $mime."
                )
            else -> resolveApp(uri, mime) ?: return noHandlerResult(file, mime)
        }

        val intent = viewIntent(uri, mime).setPackage(resolved.packageName)
        return try {
            context.startActivity(intent)
            if (resolved.source != Source.SYSTEM_DEFAULT) {
                choices.remember(mime, resolved.packageName)
            }
            ToolResult(
                true,
                "Открыл файл «${file.name}» (тип $mime) в ${resolved.packageName} — без системного выбора приложения."
            )
        } catch (e: ActivityNotFoundException) {
            // The handler disappeared between resolve and start — still do not claim success.
            ToolResult(false, "Приложение «${resolved.packageName}» не смогло открыть файл типа $mime.")
        }
    }

    /**
     * Picks one package for [mime] in the documented preference order: a stored choice, then the
     * system default, then the best-ranked handler. Returns null when nothing can handle the type.
     */
    private fun resolveApp(uri: Uri, mime: String): Resolved? {
        val pm = context.packageManager
        // (a) A previous pick, if it is still installed and can still handle the type.
        choices.packageFor(mime)?.let { stored ->
            if (canHandle(uri, mime, stored)) return Resolved(stored, Source.STORED)
        }
        // (b) The user's current system default handler. When there is NO default the platform
        // returns its own ResolverActivity (the chooser) under the "android" package — that is NOT
        // an app, so it is skipped and the ranked query below picks a real handler instead.
        val probe = viewIntent(uri, mime)
        pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.let { best ->
            val pkg = best.activityInfo.packageName
            if (!pkg.isSystemResolver()) return Resolved(pkg, Source.SYSTEM_DEFAULT)
        }
        // (c) Best-ranked handler from the ordered query.
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()
            ?.activityInfo?.packageName?.takeIf { !it.isSystemResolver() }?.let { pkg ->
                return Resolved(pkg, Source.RANKED)
            }
        return null
    }

    /**
     * True for the package names the platform uses for its own activity resolver / chooser. Seeing
     * one means "a chooser would be shown", i.e. there is no default handler to use.
     */
    private fun String.isSystemResolver(): Boolean =
        this == "android" || this == "com.android.internal.app" || this == "com.android.intentresolver"

    /** True when [pkg] is installed and currently resolves ACTION_VIEW for [mime]. */
    private fun canHandle(uri: Uri, mime: String, pkg: String): Boolean =
        viewIntent(uri, mime).setPackage(pkg).resolveActivity(context.packageManager) != null

    /** The ACTION_VIEW intent for a FileProvider content URI, with the read grant and NEW_TASK. */
    private fun viewIntent(uri: Uri, type: String) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Honest failure when no installed app handles [mime]: no chooser, and a curated list of Play
     * Store apps the user can install. See [FileOpenMime.playSuggestions].
     */
    private fun noHandlerResult(file: File, mime: String): ToolResult {
        val suggestions = FileOpenMime.playSuggestions(FileOpenMime.family(mime))
        val list = suggestions.joinToString("\n") { "• ${it.app}: ${it.url}" }
        return ToolResult(
            false,
            "Ни одно установленное приложение не открывает файлы типа $mime (файл «${file.name}»). " +
                "Системный выбор приложения намеренно не показан. " +
                "Можно установить подходящее приложение из Google Play:\n$list"
        )
    }

    /**
     * Best-effort "show this folder" (A11).
     *
     * Android has no supported public intent that opens a *specific* directory path:
     * ACTION_OPEN_DOCUMENT_TREE is a picker that hands a chosen tree to the caller, it does not
     * display a given folder. So a folder request can only launch a file-manager UI, and the result
     * says so honestly instead of implying the concrete folder was opened.
     *
     * Order of attempts, best-supported first:
     *  1. ACTION_MAIN + CATEGORY_APP_FILES — the platform file-manager entry point.
     *  2. A DocumentsUI / vendor file-manager launch intent by package.
     *  3. Last resort: ACTION_VIEW with the non-standard `resource/folder` type, which only a few
     *     third-party managers register for.
     *
     * Each candidate is started with its resolved COMPONENT, so an ambiguous implicit intent never
     * falls back to a system chooser.
     *
     * References:
     * - https://developer.android.com/guide/components/intents-common#Files
     * - https://developer.android.com/reference/android/content/Intent#CATEGORY_APP_FILES
     */
    fun openDirectory(dir: File): ToolResult {
        // A folder cannot be copied, so the non-standard `resource/folder` candidate is offered only
        // when the directory is actually inside a FileProvider root; otherwise it is skipped and the
        // platform file-manager candidates are tried instead of throwing from getUriForFile.
        val folderView = SharedFileContent.directUriOrNull(context, dir)?.let { folderUri ->
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val launched = launchFirstResolvable(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES),
            context.packageManager.getLaunchIntentForPackage("com.google.android.documentsui"),
            context.packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles"),
            folderView,
        )
        return if (launched != null) {
            ToolResult(
                true,
                "Открыл файловый менеджер, а не конкретную папку (Android не даёт открывать путь «${dir.absolutePath}» " +
                    "напрямую). Приложение: ${launched.flattenToShortString()}."
            )
        } else {
            ToolResult(false, "Не нашёл приложение файлового менеджера. Путь: ${dir.absolutePath}")
        }
    }

    /**
     * Starts the first candidate that has a real handler and returns the component it resolved to,
     * or null when none of the candidates resolves. The intent is started with its resolved
     * COMPONENT so a match never degrades into a chooser, and `resolveActivity` is checked BEFORE
     * `startActivity`, so a start that matches nothing is reported as a failure, never as success.
     * - https://developer.android.com/reference/android/content/pm/PackageManager#resolveActivity(android.content.Intent,%20int)
     */
    private fun launchFirstResolvable(vararg candidates: Intent?): ComponentName? {
        for (candidate in candidates) {
            val intent = candidate ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val component = intent.resolveActivity(context.packageManager) ?: continue
            intent.component = component
            try {
                context.startActivity(intent)
                return component
            } catch (e: ActivityNotFoundException) {
                // Try the next candidate.
            }
        }
        return null
    }
}
