package com.andharness.ru.data.intercept

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.CallLogWatcherWorker
import com.andharness.ru.bg.WatchdogService
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.data.core.WatchdogState
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.osint.NumberOsint
import com.andharness.ru.data.osint.NumberNormalizer
import com.andharness.ru.data.osint.NumberProfile
import com.andharness.ru.data.osint.OsintCacheStore
import com.andharness.ru.data.osint.OsintEngine
import com.andharness.ru.domain.chat.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and updates ONE chat per normalized **unknown** number (update.md §2.6) and notifies the
 * user with a deep link into that chat.
 *
 * Behaviour:
 * - "unknown" = not resolvable in the user's contacts (`ContactsContract.PhoneLookup`); the
 *   personal base from Phase 4 is not part of this phase, and this is stated in the messages;
 * - the chat is created once and reused (the `number → chatId` map lives in [NumberChatStore]);
 * - each event writes a summary message: number profile (prefix heuristic) + OSINT summary +
 *   up to 5 source links;
 * - the notification reuses [NotificationHelper] and its chat deep link;
 * - if interception is disabled the event is skipped with an explicit note (never silently).
 */
@Singleton
class NumberChatManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val notifications: NotificationHelper,
    private val osint: OsintEngine,
    private val osintCache: OsintCacheStore,
    private val store: NumberChatStore,
    private val config: WatchdogConfigStore,
) {

    fun isEnabled(): Boolean = config.isInterceptionEnabled()

    /** Entry point for call/SMS/fallback events: respects the persisted interception switch. */
    suspend fun onEvent(rawNumber: String, eventKind: String, detail: String? = null): NumberChatOutcome {
        if (!config.isInterceptionEnabled()) {
            return NumberChatOutcome(
                number = NumberNormalizer.normalize(rawNumber),
                chatId = null,
                knownContact = null,
                osint = null,
                note = DISABLED_NOTE,
            )
        }
        return process(rawNumber, eventKind, detail, force = false)
    }

    /**
     * Resolves [rawNumber], runs OSINT (cache-first unless [force]), creates/reuses the number chat
     * and posts the notification. Called by the `number_osint` tool with [force] = true.
     */
    suspend fun process(
        rawNumber: String,
        eventKind: String = EVENT_MANUAL,
        detail: String? = null,
        force: Boolean = false,
    ): NumberChatOutcome {
        val normalized = NumberNormalizer.normalize(rawNumber)
            ?: return NumberChatOutcome(
                number = null,
                chatId = null,
                knownContact = null,
                osint = null,
                note = "Номер не распознан (не телефонный номер) — авто-чат не создан, данные не выдумываны.",
            )

        val contactName = ContactLookup.displayName(context, normalized)
        if (contactName != null && !force) {
            return NumberChatOutcome(
                number = normalized,
                chatId = null,
                knownContact = contactName,
                osint = null,
                note = "Номер $normalized — известный контакт «$contactName»; авто-чат не требуется.",
            )
        }

        val profile = NumberProfile.describe(normalized)
        val osintResult = osint.research(normalized, force)
        val existing = store.get(normalized)
        val now = System.currentTimeMillis()
        val chatId = existing?.chatId
            ?: chatRepository.createChat(title = contactName ?: normalized)

        val message = renderMessage(normalized, contactName, profile, osintResult, eventKind, detail)
        chatRepository.addMessage(chatId = chatId, role = CHAT_ROLE, content = message)
        store.put(
            TrackedNumber(
                number = normalized,
                chatId = chatId,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastEventAt = now,
                lastEventKind = eventKind,
            )
        )

        val wasNew = existing == null
        notifications.postDoneNotification(
            text = notificationText(normalized, contactName, osintResult, wasNew),
            chatId = chatId,
            notificationId = notificationIdFor(normalized),
            title = "Меха: незнакомый номер",
        )

        return NumberChatOutcome(
            number = normalized,
            chatId = chatId,
            knownContact = contactName,
            osint = osintResult,
            note = buildString {
                append(if (wasNew) "Создан чат номера" else "Обновлён чат номера")
                append(" $normalized (chatId=$chatId). ")
                append(if (osintResult.fromCache) "OSINT взят из кэша." else "OSINT обновлён веб-поиском.")
            },
        )
    }

    /** Honest status for `auto_number_chat_status` / `unknown_numbers_list`. */
    suspend fun status(): String {
        val enabled = config.isInterceptionEnabled()
        val roleHeld = isCallScreeningRoleHeld()
        val tracked = store.all()
        val coreRunning = WatchdogState.status.value.running
        return buildString {
            appendLine("Авто-чат номеров: ${if (enabled) "включён" else "выключен"}.")
            appendLine(
                "Перехват звонков: роль CALL_SCREENING ${if (roleHeld) "выдана" else "НЕ выдана — " +
                    "реальный перехват невозможен, работает только фолбэк по журналу вызовов " +
                    "(опоздание до 15 мин)."}"
            )
            appendLine("SMS: приёмник SMS_RECEIVED ${if (enabled) "активен" else "неактивен (перехват выключен)"}.")
            appendLine("Ядро (WatchdogService): ${if (coreRunning) "запущено" else "не запущено"}.")
            appendLine("Записей номеров: ${tracked.size}; в кэше OSINT: ${osintCache.all().size}.")
            if (tracked.isNotEmpty()) {
                appendLine("Последние номера:")
                tracked.take(10).forEach {
                    appendLine("  ${it.number} → чат ${it.chatId} (${it.lastEventKind})")
                }
            }
        }.trim()
    }

    /** Enables/disables interception, (re)schedules the fallback watcher and starts/stops the core. */
    suspend fun setEnabled(enabled: Boolean): String {
        config.setInterceptionEnabled(enabled)
        return if (enabled) {
            val started = WatchdogService.start(context, WatchdogService.REASON_EXPLICIT)
            scheduleFallbackWatcher()
            buildString {
                append("Перехват включён.")
                append(if (started) " Ядро запущено." else " Ядро запустить не удалось (ограничение фонового запуска).")
                append(" Фолбэк-наблюдение журнала вызовов поставлено на каждые 15 минут — результат появится с задержкой.")
            }
        } else {
            cancelFallbackWatcher()
            "Перехват выключен; фолбэк-наблюдение снято. SMS-приёмник и CallScreeningService " +
                "больше не создают чаты. Ядро остановится само, если не включены другие подсистемы."
        }
    }

    /** Re-schedules the fallback watcher (idempotent); used on boot/enable. */
    fun scheduleFallbackWatcher() {
        try {
            val request = PeriodicWorkRequestBuilder<CallLogWatcherWorker>(
                FALLBACK_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CallLogWatcherWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } catch (e: Exception) {
            Log.e(TAG, "scheduling the call-log fallback watcher failed", e)
        }
    }

    fun cancelFallbackWatcher() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(CallLogWatcherWorker.WORK_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "cancelling the call-log fallback watcher failed", e)
        }
    }

    /** True only on API 29+ and when the app actually holds `ROLE_CALL_SCREENING`. */
    fun isCallScreeningRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val rm = context.getSystemService(RoleManager::class.java) ?: return false
            rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        } catch (e: Exception) {
            false
        }
    }

    private fun renderMessage(
        number: String,
        contactName: String?,
        profile: String,
        osintResult: NumberOsint,
        eventKind: String,
        detail: String?,
    ): String = buildString {
        appendLine("Событие: ${eventLabel(eventKind)}")
        if (!detail.isNullOrBlank()) appendLine("Деталь: ${detail.take(200)}")
        appendLine("Номер: $number")
        appendLine("Профиль: $profile")
        if (contactName != null) appendLine("Контакт: $contactName (найден по номеру)")
        appendLine("Соседние источники: персональная база (Phase 4) в этой версии недоступна — учитывались только контакты.")
        appendLine()
        appendLine("OSINT (свободный веб-поиск, без ключей):")
        appendLine(osintResult.summary)
        if (osintResult.sources.isNotEmpty()) {
            appendLine()
            appendLine("Ссылки (до 5):")
            osintResult.sources.forEach { appendLine("- ${it.url}") }
        }
    }.trim()

    private fun notificationText(
        number: String,
        contactName: String?,
        osintResult: NumberOsint,
        wasNew: Boolean,
    ): String {
        val who = contactName ?: number
        val prefix = if (wasNew) "Незнакомый номер" else "Повторное событие"
        val short = osintResult.summary.replace('\n', ' ').take(120)
        return "$prefix: $who. $short"
    }

    private fun eventLabel(kind: String): String = when (kind) {
        EVENT_CALL -> "входящий звонок (CallScreeningService)"
        EVENT_SMS -> "входящее SMS (SMS_RECEIVED)"
        EVENT_CALL_FALLBACK -> "звонок из журнала (фолбэк, с задержкой)"
        else -> "ручной запрос"
    }

    private fun notificationIdFor(number: String): Int =
        NOTIFICATION_ID_BASE + ((number.hashCode() and 0x7fffffff) % 100_000)

    companion object {
        private const val TAG = "Andharness"
        private const val CHAT_ROLE = "assistant"
        private const val NOTIFICATION_ID_BASE = 8_000
        private const val FALLBACK_INTERVAL_MINUTES = 15L

        const val EVENT_CALL = "call"
        const val EVENT_SMS = "sms"
        const val EVENT_CALL_FALLBACK = "call_fallback"
        const val EVENT_MANUAL = "manual"

        const val DISABLED_NOTE =
            "Перехват выключен (auto_number_chat_toggle): событие проигнорировано, авто-чат не создан."
    }
}
