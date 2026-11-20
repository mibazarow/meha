package com.andharness.ru.data.tools.calls

import com.andharness.ru.data.calls.CallRulesStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.calls.CallRulesCodec
import com.andharness.ru.domain.calls.CallRuleEngine
import com.andharness.ru.domain.calls.CallRulesState
import com.andharness.ru.domain.calls.CallRuleTokens
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Tools over the user's own call/SMS auto-handling rules ([CallRulesStore], checklist §1):
 *
 * - `call_rules_list` — read-only view of the opt-in switch, the blocked/allow lists, the auto-reply
 *   settings and the call-back reminder flag;
 * - `call_rules_set`  — set/update any of those. Every parameter is optional and simple
 *   (string/boolean); unrecognized values are rejected with an honest Russian message instead of
 *   being guessed.
 *
 * These tools only change **Меха's own screening policy**. They never write the system blocked-number
 * list (that provider is restricted to the default phone/SMS app and the carrier — see the existing
 * `blocked_number_ui` tool) and never promise more than the call-screening role can actually do.
 *
 * References:
 * - `CallScreeningService` (the component that applies these rules):
 *   https://developer.android.com/reference/android/telecom/CallScreeningService
 * - `SmsManager.sendTextMessage` (auto-reply delivery path):
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 */
class CallRulesToolsHandler(private val store: CallRulesStore) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "call_rules_list"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching { ToolResult(true, render(store.state.value)) }
                    .getOrElse { ToolResult(false, "Ошибка чтения правил звонков: ${it.message}") }
            }
        },
        object : ToolHandler {
            override val name = "call_rules_set"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    apply(arguments)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка сохранения правил звонков: ${e.message}")
                }
            }
        },
    )

    /** Applies one `call_rules_set` call; returns the honest outcome (or the first error). */
    private fun apply(arguments: JsonObject): ToolResult {
        // Parse every optional boolean first: an absent key is a no-op, a present-but-unparseable one
        // is rejected honestly instead of being guessed.
        val bools = HashMap<String, Boolean>()
        for (key in BOOL_KEYS) {
            val raw = arguments.str(key) ?: continue
            val value = CallRuleTokens.bool(raw)
                ?: return ToolResult(
                    false,
                    "Не разобрал $key=«$raw»; нужно true/false (да/нет, вкл/выкл).",
                )
            bools[key] = value
        }
        val enabled = bools["enabled"]
        val blockUnknown = bools["block_unknown"]
        val autoReplySms = bools["auto_reply_sms"]
        val autoReplyWhenBusy = bools["auto_reply_when_busy"]
        val callBackReminder = bools["call_back_reminder"]
        val blockedRaw = arguments.str("blocked_numbers")?.takeIf { it.isNotBlank() }
        val allowRaw = arguments.str("allow_numbers")?.takeIf { it.isNotBlank() }
        val replyText = arguments.str("auto_reply_text")

        val hasAny = enabled != null || blockUnknown != null || autoReplySms != null ||
            autoReplyWhenBusy != null || callBackReminder != null ||
            blockedRaw != null || allowRaw != null || replyText != null
        if (!hasAny) {
            return ToolResult(
                false,
                "Не указано ни одного параметра. Задайте enabled, block_unknown, blocked_numbers, " +
                    "allow_numbers, auto_reply_sms, auto_reply_text, auto_reply_when_busy или call_back_reminder."
            )
        }

        if (enabled != null) store.setEnabled(enabled)
        if (blockUnknown != null) store.setBlockUnknown(blockUnknown)
        if (blockedRaw != null) store.setBlockedNumbers(CallRuleEngine.parseNumberList(blockedRaw))
        if (allowRaw != null) store.setAllowNumbers(CallRuleEngine.parseNumberList(allowRaw))
        if (autoReplySms != null) store.setAutoReplySms(autoReplySms)
        if (autoReplyWhenBusy != null) store.setAutoReplyWhenBusy(autoReplyWhenBusy)
        if (callBackReminder != null) store.setCallBackReminder(callBackReminder)
        if (replyText != null) store.setAutoReplyText(replyText)

        // Honest note: an enabled rule set that still cannot do anything visible is called out, so an
        // "включено" line is never mistaken for active filtering.
        val rules = store.current()
        val note = when {
            !rules.enabled -> "Внимание: авто-обработка выключена (enabled=false) — звонки и SMS не фильтруются."
            !rules.blockUnknown && rules.blockedNumbers.isEmpty() &&
                !rules.autoReplySms && !rules.autoReplyWhenBusy && !rules.callBackReminder ->
                "Внимание: включено, но не задано ни одного действия — фильтровать пока нечего."
            else -> "Сохранено."
        }
        return ToolResult(true, "$note\n${render(store.state.value)}")
    }

    /** Read-only rendering of the whole state (Russian, grouped). */
    private fun render(state: CallRulesState): String = buildString {
        val rules = state.rules
        appendLine("Авто-обработка звонков/SMS: ${if (rules.enabled) "ВКЛЮЧЕНА" else "выключена (по умолчанию)"}")
        appendLine("- Блокировать незнакомых: ${if (rules.blockUnknown) "да" else "нет"}")
        appendLine("- Заблокированные номера (${rules.blockedNumbers.size}/${CallRulesCodec.MAX_NUMBERS}): " +
            listOrDash(rules.blockedNumbers))
        appendLine("- Разрешённые номера — «белый список» (${rules.allowNumbers.size}/${CallRulesCodec.MAX_NUMBERS}): " +
            listOrDash(rules.allowNumbers))
        val reply = if (rules.autoReplySms) "вкл" else "выкл"
        val busy = if (rules.autoReplyWhenBusy) "вкл" else "выкл"
        appendLine("- Авто-ответ SMS незнакомым: $reply; при занятости: $busy")
        appendLine("- Текст авто-ответа: «${rules.autoReplyText.ifBlank { "—" }}»")
        appendLine("- Напоминание перезвонить (известный контакт): ${if (rules.callBackReminder) "да" else "нет"}")
        appendLine("- Отправлено авто-ответов (в журнале): ${state.autoReplyLog.size}/${CallRulesCodec.MAX_REPLY_LOG}")
        append("Короткие/сервисные и экстренные номера авто-ответом не затрагиваются никогда.")
    }.trim()

    private fun listOrDash(list: List<String>): String =
        if (list.isEmpty()) "—" else CallRuleEngine.formatNumberList(list)

    private companion object {
        /** All optional boolean parameters of `call_rules_set`, validated uniformly up front. */
        val BOOL_KEYS = listOf(
            "enabled",
            "block_unknown",
            "auto_reply_sms",
            "auto_reply_when_busy",
            "call_back_reminder",
        )
    }
}
