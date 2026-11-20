package com.andharness.ru.data.tools.person

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.andharness.ru.bg.PersonBackfillWorker
import com.andharness.ru.data.person.MessagingIndexConfigStore
import com.andharness.ru.data.person.PersonBackfillStore
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.MessagingIndexPolicy
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.NotificationRelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `messaging_index_config`: opt-in switch, package list and backfill state of the incremental
 * personal-base indexer. `set` starts/cancels the bounded `PersonBackfillWorker` and reports the
 * honest current state, including access gaps.
 */
internal class MessagingIndexConfigToolHandler(
    private val context: Context,
    private val configStore: MessagingIndexConfigStore,
    private val backfillStore: PersonBackfillStore,
    private val indexer: PersonIndexer,
) : ToolHandler {

    override val name = "messaging_index_config"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = (arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: "get"
            when (action) {
                "get" -> ToolResult(true, describeState())
                "set" -> setConfig(arguments)
                else -> ToolResult(
                    false,
                    "Неизвестное действие «$action». Доступно: get, set.",
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка настройки индексации: ${e.message}")
        }
    }

    private fun setConfig(arguments: JsonObject): ToolResult {
        val raw = (arguments["enabled"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val enabled = raw.toBooleanStrictOrNull()
            ?: return ToolResult(
                false,
                "Укажите enabled = true или false (передан «$raw»).",
            )

        val rawPackages = (arguments["packages"] as? JsonPrimitive)?.content
        if (rawPackages != null) {
            val parsed = MessagingIndexPolicy.parsePackages(rawPackages)
            if (parsed.isEmpty()) {
                configStore.resetPackages()
            } else {
                configStore.setPackages(parsed)
            }
        }
        configStore.setEnabled(enabled)

        if (enabled) {
            PersonBackfillWorker.start(context)
        } else {
            PersonBackfillWorker.cancel(context)
        }

        val actionNote = if (enabled) {
            "Индексация включена. Разовый бэкфилл (контакты, SMS, журнал вызовов) поставлен в " +
                "WorkManager — он bounded и продолжается с сохранённого курсора."
        } else {
            "Индексация выключена. Инкрементальный приём новых SMS/уведомлений остановлен, " +
                "бэкфилл снят; уже сохранённые метаданные не удалены (вычистить — отдельная команда)."
        }
        return ToolResult(true, "$actionNote\n\n${describeState()}")
    }

    /** The honest current state, including access gaps (shared with the app add/remove tools). */
    private fun describeState(): String =
        describeIndexState(context, configStore, backfillStore, indexer)
}

/**
 * Honest indexer state (shared by `messaging_index_config` and the app add/remove tools): opt-in
 * flag, package list, Notification Listener access, source permissions, backfill cursors and the
 * storage-honesty note. Reads the current state on each call; purely formatting otherwise.
 */
internal fun describeIndexState(
    context: Context,
    configStore: MessagingIndexConfigStore,
    backfillStore: PersonBackfillStore,
    indexer: PersonIndexer,
): String = buildString {
    appendLine("Индексация персональной базы: ${if (indexer.isEnabled()) "включена" else "выключена"}.")
    val packages = configStore.packages()
    appendLine("Пакеты для мессенджеров/почты (${packages.size}): ${packages.sorted().joinToString(", ")}")
    val listener = NotificationRelayService.isEnabled()
    appendLine(
        "Спец-доступ Notification Listener: ${if (listener) "выдан" else "НЕ выдан"}. " +
            if (listener) {
                "Уведомления мессенджеров/почты индексируются (только выбранные пакеты)."
            } else {
                "Без него перехват уведомлений невозможен: включите «Доступ к уведомлениям» в " +
                    "настройках (Настройки → Уведомления → Специальный доступ)."
            }
    )
    appendLine(
        "Разрешения источников: контакты — ${yesNo(context, Manifest.permission.READ_CONTACTS)}, " +
            "SMS — ${yesNo(context, Manifest.permission.READ_SMS)}, " +
            "журнал вызовов — ${yesNo(context, Manifest.permission.READ_CALL_LOG)}."
    )
    appendLine(
        "Бэкфилл: контакты ${if (backfillStore.contactsDone()) "обработаны" else "ещё нет"}; " +
            "курсор SMS ${backfillStore.smsCursor()}, журнала вызовов ${backfillStore.callsCursor()}, " +
            "прогонов ${backfillStore.runs()}."
    )
    append(
        "Честно: метаданные и краткое резюме (gist, ≤ 80 символов) хранятся в interactions, а " +
            "ПОЛНЫЙ текст SMS и сообщений мессенджеров/почты сохраняется в шифрованной базе " +
            "приложения (stored_messages) для последующего поиска и сводок — эти данные остаются " +
            "только на устройстве и не логируются. Индексация ничего не делает, пока не включена."
    )
}

private fun yesNo(context: Context, permission: String): String =
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        "есть"
    } else {
        "нет"
    }
