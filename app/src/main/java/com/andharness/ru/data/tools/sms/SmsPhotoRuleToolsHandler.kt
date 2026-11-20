package com.andharness.ru.data.tools.sms

import com.andharness.ru.data.sms.SmsPhotoRuleStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.calls.CallRuleTokens
import com.andharness.ru.domain.sms.SmsPhotoRuleCodec
import com.andharness.ru.domain.sms.SmsPhotoRuleState
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Tools over the trusted-SMS photo report config ([SmsPhotoRuleStore]):
 *
 * - `sms_photo_rule_get` — read-only view of the opt-in switch, the reply prefix and the cooldown;
 * - `sms_photo_rule_set` — set/update them (`enabled` boolean, optional `reply_text_prefix`,
 *   optional `cooldown_seconds`). Unrecognized values are rejected honestly instead of guessed.
 *
 * A reply is only ever sent to a number on the trusted-source allowlist, and never to a service/
 * short/emergency number; the rule is OFF by default.
 *
 * References:
 * - `SmsManager.sendTextMessage` (the reply path and the platform's premium/short-code restriction):
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 */
class SmsPhotoRuleToolsHandler(private val store: SmsPhotoRuleStore) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "sms_photo_rule_get"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching { ToolResult(true, render(store.state.value)) }
                    .getOrElse { ToolResult(false, "Ошибка чтения правила фото-отчёта: ${it.message}") }
            }
        },
        object : ToolHandler {
            override val name = "sms_photo_rule_set"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    apply(arguments)
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка сохранения правила фото-отчёта: ${e.message}")
                }
            }
        },
    )

    /** Applies one `sms_photo_rule_set` call; returns the honest outcome (or the first error). */
    private fun apply(arguments: JsonObject): ToolResult {
        val enabledRaw = arguments.str("enabled")
        val enabled = enabledRaw?.let {
            CallRuleTokens.bool(it)
                ?: return ToolResult(false, "Не разобрал enabled=«$it»; нужно true/false (да/нет, вкл/выкл).")
        }
        val prefix = arguments.str("reply_text_prefix")
        val cooldownRaw = arguments.str("cooldown_seconds")?.trim()?.takeIf { it.isNotEmpty() }
        val cooldown = cooldownRaw?.let {
            it.toIntOrNull()?.takeIf { value -> value in 0..SmsPhotoRuleCodec.MAX_COOLDOWN_SECONDS }
                ?: return ToolResult(
                    false,
                    "Не разобрал cooldown_seconds=«$it»; нужно целое число секунд от 0 до " +
                        "${SmsPhotoRuleCodec.MAX_COOLDOWN_SECONDS} (0 — без кулдауна).",
                )
        }

        if (enabled == null && prefix == null && cooldown == null) {
            return ToolResult(
                false,
                "Не указано ни одного параметра. Задайте enabled, reply_text_prefix или cooldown_seconds.",
            )
        }

        enabled?.let { store.setEnabled(it) }
        prefix?.let { store.setReplyTextPrefix(it) }
        cooldown?.let { store.setCooldownSeconds(it) }

        val rule = store.current()
        val note = if (!rule.enabled) {
            "Внимание: фото-отчёт выключен (enabled=false) — на команды по SMS ничего не отвечает."
        } else {
            "Сохранено. Фото-отчёт включён: отвечает фотографией только доверенным номерам, " +
                "не чаще раза в ${rule.cooldownSeconds} с на номер."
        }
        return ToolResult(true, "$note\n${render(store.state.value)}")
    }

    /** Read-only rendering of the whole state (Russian). */
    private fun render(state: SmsPhotoRuleState): String = buildString {
        val rule = state.rule
        appendLine("Фото-отчёт по SMS: ${if (rule.enabled) "ВКЛЮЧЁН" else "выключен (по умолчанию)"}")
        appendLine("- Кому отвечает: только номерам из доверенных источников (trusted_source_*)")
        appendLine("- Префикс ответа: «${rule.replyTextPrefix.ifBlank { "—" }}»")
        appendLine("- Кулдаун на номер: ${rule.cooldownSeconds} с (0 — без кулдауна)")
        appendLine("- Отправлено фото-отчётов (в журнале): ${state.replyLog.size}/${SmsPhotoRuleCodec.MAX_REPLY_LOG}")
        append("Команды-триггеры: ${SmsPhotoRuleEngineCommands.SHORT}. Служебные/короткие и экстренные номера не затрагиваются никогда.")
    }.trim()

    /** Static command list kept next to the tool output, without pulling in engine internals. */
    private object SmsPhotoRuleEngineCommands {
        val SHORT = "«фото», «сделай фото», «что на фото», «снимок», «photo», «picture»"
    }
}
