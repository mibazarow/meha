package com.andharness.ru.data.tools.apps

import com.andharness.ru.data.tools.files.FileOpenMime

/**
 * Pure decision + message builder for the `app_suggest_for` tool. It resolves the requested
 * format/task to a MIME family (via [FileOpenMime]) and, given whether a handler app is installed
 * (a PackageManager question answered by the handler, never here), produces the honest reply the
 * agent sees. Staying Android-free keeps the format→family mapping, the suggestion selection and the
 * installed-vs-not branch JVM-unit-testable with no Context or Robolectric.
 */
internal object AppSuggestDecision {

    /** The outcome: [success] mirrors [com.andharness.ru.domain.agent.ToolResult.success]. */
    data class Advice(val success: Boolean, val text: String)

    /**
     * @param input the raw `format` (mime/extension/kind) or `task` free text.
     * @param installed whether an installed app currently resolves ACTION_VIEW for the MIME.
     */
    fun advice(input: String, installed: Boolean): Advice {
        val resolution = FileOpenMime.resolve(input)
        val mime = resolution.mime
        if (!resolution.matched || mime == null) {
            return Advice(
                success = false,
                text = "Не удалось определить формат по запросу «$input». Укажите MIME (например " +
                    "application/pdf), расширение (pdf, docx, rar), общее слово (video, image, " +
                    "audio, office, archive) или кратко опишите задачу."
            )
        }
        if (installed) {
            return Advice(
                success = true,
                text = "Для формата $mime (семейство ${resolution.family.name}) на устройстве УЖЕ " +
                    "есть приложение, которое его открывает. Откройте файл через files_open " +
                    "(или intent_open), и Меха выберет обработчик сама."
            )
        }
        val suggestions = FileOpenMime.playSuggestions(resolution.family)
        val list = suggestions.joinToString("\n") { "• ${it.app}: ${it.market} (веб: ${it.url})" }
        return Advice(
            success = true,
            text = "Ни одно установленное приложение не открывает формат $mime " +
                "(семейство ${resolution.family.name}). Установить приложение молча агент не может — " +
                "предложите пользователю поставить одно из приложений Google Play:\n$list\n" +
                "После установки проверьте результат через app_is_installed."
        )
    }
}
