package com.andharness.ru.data.tools.person

import android.content.Context
import android.content.pm.PackageManager
import com.andharness.ru.bg.PersonBackfillWorker
import com.andharness.ru.data.person.MessagingIndexConfigStore
import com.andharness.ru.data.person.PersonBackfillStore
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.InstalledApp
import com.andharness.ru.domain.person.InstalledAppResolver
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * `messaging_index_app_add` / `messaging_index_app_remove`: add or remove one installed app from
 * the messenger/mail allow-list **by name or package**, so the user can say
 * «добавь приложение MAX в индексацию» without knowing the package id.
 *
 * Resolution is delegated to the pure [InstalledAppResolver] (exact package → exact label →
 * label-contains, case-insensitive) and honours the "never guess" rule: zero or several matches
 * change nothing and return the candidate list (`package | label`), asking for the exact package.
 *
 * Installed apps are read with `PackageManager.getInstalledApplications(GET_META_DATA)` and labelled
 * with `getApplicationLabel` (the app declares `QUERY_ALL_PACKAGES`), mirroring `app_list`.
 * https://developer.android.com/reference/android/content/pm/PackageManager#getInstalledApplications(int)
 * https://developer.android.com/reference/android/content/pm/PackageManager#getApplicationLabel(android.content.pm.ApplicationInfo)
 */
internal class MessagingIndexAppAddToolHandler(
    private val context: Context,
    private val configStore: MessagingIndexConfigStore,
    private val backfillStore: PersonBackfillStore,
    private val indexer: PersonIndexer,
) : ToolHandler {

    override val name = "messaging_index_app_add"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = appQuery(arguments)
                ?: return@withContext ToolResult(
                    false,
                    "Укажите app: название приложения (например «MAX», «Telegram») или пакет."
                )
            when (val resolution = resolveApp(context, query)) {
                is AppResolution.NotFound -> ToolResult(false, notFoundText(query))
                is AppResolution.Ambiguous -> ToolResult(false, ambiguousText(query, resolution.hits))
                is AppResolution.Found -> add(resolution.app)
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка добавления приложения в индексацию: ${e.message}")
        }
    }

    private fun add(app: InstalledApp): ToolResult {
        configStore.setPackages(configStore.packages() + app.pkg)
        configStore.setEnabled(true)
        PersonBackfillWorker.start(context)
        val updated = configStore.packages()
        return ToolResult(
            true,
            buildString {
                appendLine("Добавлено в индексацию: «${app.label}» (${app.pkg}).")
                appendLine(
                    "Индексация включена; разовый бэкфилл (контакты, SMS, журнал вызовов) " +
                        "поставлен в WorkManager — он bounded и продолжается с сохранённого курсора."
                )
                appendLine(
                    "Пакеты для мессенджеров/почты (${updated.size}): ${updated.sorted().joinToString(", ")}"
                )
                appendLine()
                append(describeIndexState(context, configStore, backfillStore, indexer))
            },
        )
    }
}

/**
 * `messaging_index_app_remove`: removes one installed app's package from the allow-list by name or
 * package, without touching the opt-in flag. Honest caveat: because an empty stored set makes
 * [MessagingIndexConfigStore.packages] fall back to the defaults, removing the last explicit entry
 * effectively reverts the list to `MessagingIndexPolicy.DEFAULT_PACKAGES` — this is reported.
 */
internal class MessagingIndexAppRemoveToolHandler(
    private val context: Context,
    private val configStore: MessagingIndexConfigStore,
    private val backfillStore: PersonBackfillStore,
    private val indexer: PersonIndexer,
) : ToolHandler {

    override val name = "messaging_index_app_remove"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = appQuery(arguments)
                ?: return@withContext ToolResult(
                    false,
                    "Укажите app: название приложения (например «MAX», «Telegram») или пакет."
                )
            when (val resolution = resolveApp(context, query)) {
                is AppResolution.NotFound -> ToolResult(false, notFoundText(query))
                is AppResolution.Ambiguous -> ToolResult(false, ambiguousText(query, resolution.hits))
                is AppResolution.Found -> remove(resolution.app)
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка удаления приложения из индексации: ${e.message}")
        }
    }

    private fun remove(app: InstalledApp): ToolResult {
        val before = configStore.packages()
        val wasPresent = before.any { it.equals(app.pkg, ignoreCase = true) }
        val remaining = before.filterNot { it.equals(app.pkg, ignoreCase = true) }.toSet()
        configStore.setPackages(remaining)
        val updated = configStore.packages()
        return ToolResult(
            true,
            buildString {
                if (wasPresent) {
                    appendLine("Убрано из индексации: «${app.label}» (${app.pkg}).")
                } else {
                    appendLine(
                        "«${app.label}» (${app.pkg}) и так не было в списке индексации — ничего не изменилось."
                    )
                }
                appendLine("Индексация не выключается (opt-in флаг не трогается).")
                if (wasPresent && remaining.isEmpty()) {
                    appendLine(
                        "Честно: это был последний сохранённый пакет, поэтому хранилище возвращается " +
                            "к списку по умолчанию (MessagingIndexPolicy.DEFAULT_PACKAGES) — " +
                            "фактически снова действует список по умолчанию."
                    )
                }
                appendLine(
                    "Пакеты для мессенджеров/почты (${updated.size}): ${updated.sorted().joinToString(", ")}"
                )
                appendLine()
                append(describeIndexState(context, configStore, backfillStore, indexer))
            },
        )
    }
}

private sealed interface AppResolution {
    data class Found(val app: InstalledApp) : AppResolution
    data class Ambiguous(val hits: List<InstalledApp>) : AppResolution
    data object NotFound : AppResolution
}

private const val MAX_CANDIDATES = 25

private fun appQuery(arguments: JsonObject): String? =
    (arguments["app"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun resolveApp(context: Context, query: String): AppResolution {
    val hits = InstalledAppResolver.resolve(query, installedApps(context))
    return when (hits.size) {
        0 -> AppResolution.NotFound
        1 -> AppResolution.Found(hits.first())
        else -> AppResolution.Ambiguous(hits)
    }
}

/** Installed apps (package + label), deterministically sorted by label then package. */
private fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .map { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)
            InstalledApp(pkg = info.packageName, label = label)
        }
        .sortedWith(compareBy({ it.label.lowercase(Locale.US) }, { it.pkg }))
}

private fun notFoundText(query: String): String =
    "Приложение по запросу «$query» не найдено среди установленных. Уточните название или " +
        "укажите точный пакет (например org.telegram.messenger). Ничего не изменено."

private fun ambiguousText(query: String, hits: List<InstalledApp>): String = buildString {
    appendLine("По запросу «$query» найдено несколько приложений — ничего не изменено.")
    appendLine("Уточните и укажите точный пакет:")
    hits.take(MAX_CANDIDATES).forEach { appendLine("${it.pkg} | ${it.label}") }
    if (hits.size > MAX_CANDIDATES) {
        append("… и ещё ${hits.size - MAX_CANDIDATES} совпадений.")
    }
}
