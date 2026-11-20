package com.andharness.ru.data.tools.usage

import com.andharness.ru.data.usage.AutomationSuggestionEngine
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.usage.AutomationSuggestion
import com.andharness.ru.domain.usage.UsageEventKind
import com.andharness.ru.domain.usage.UsagePatternAnalyzer
import com.andharness.ru.domain.usage.UsagePatternEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Builds the predictive-suggestion tools (update.md idea #16):
 *
 * - `automation_suggestions` — the real tool: mines the last 14 days of app usage and returns
 *   candidate automations (or an honest empty state / `PERMISSION_REQUIRED` for usage access).
 * - `automation_suggestions_simulate` — **debug-only** seam that feeds a synthetic multi-day history
 *   through the very same analyzer and accept/dismiss path, so the pipeline can be verified on a
 *   device that has too little real usage history. It refuses to run in a release build.
 */
class AutomationSuggestionsToolsHandler(
    private val engine: AutomationSuggestionEngine
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "automation_suggestions"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!engine.hasUsageAccess()) {
                        return@withContext ToolResult(false, SpecialAccess.required(SpecialAccess.USAGE_ACCESS))
                    }
                    val limit = (arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?.coerceIn(1, AutomationSuggestionEngine.MAX_LIMIT)
                        ?: AutomationSuggestionEngine.DEFAULT_LIMIT

                    val suggestions = engine.currentSuggestions(limit)
                    if (suggestions.isEmpty()) {
                        ToolResult(
                            true,
                            "Подсказок нет: за последние 14 дней устойчивых шаблонов использования не нашлось " +
                                "(либо все найденные уже приняты или скрыты)."
                        )
                    } else {
                        ToolResult(true, buildString {
                            append("Подсказки автоматизаций (${suggestions.size}):")
                            for (s in suggestions) append("\n").append(describe(s))
                            append("\n\nСоздать правило можно через automation_rule_create; ")
                            append("в приложении — «Домой → Подсказки».")
                        })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },

        object : ToolHandler {
            override val name = "automation_suggestions_simulate"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    if (!isDebuggable()) {
                        return@withContext ToolResult(
                            false,
                            "Инструмент доступен только в debug-сборке (симуляция подсказок)."
                        )
                    }
                    val now = System.currentTimeMillis()
                    val mined = UsagePatternAnalyzer.analyze(
                        events = syntheticEvents(now, ZoneId.systemDefault()),
                        nowMillis = now
                    )
                    if (mined.isEmpty()) {
                        return@withContext ToolResult(false, "Синтетическая история не дала подсказок — ошибка анализатора.")
                    }

                    val action = arguments.str("action")?.lowercase() ?: "analyze"
                    when (action) {
                        "analyze" -> ToolResult(
                            true,
                            "Синтетических подсказок: ${mined.size}\n" + mined.joinToString("\n") { describe(it) }
                        )

                        "accept" -> {
                            val target = pick(mined, arguments.str("id"))
                                ?: return@withContext ToolResult(false, "Подсказка с указанным id не найдена.")
                            val ruleId = engine.accept(target)
                            ToolResult(
                                true,
                                "Принята подсказка ${target.id} → создано правило #$ruleId " +
                                    "(проверьте automation_rule_list). isHandled=${engine.isHandled(target.id)}"
                            )
                        }

                        "dismiss" -> {
                            val target = pick(mined, arguments.str("id"))
                                ?: return@withContext ToolResult(false, "Подсказка с указанным id не найдена.")
                            engine.dismiss(target)
                            ToolResult(
                                true,
                                "Скрыта подсказка ${target.id}. isHandled=${engine.isHandled(target.id)} " +
                                    "— повторно она не появится."
                            )
                        }

                        else -> ToolResult(false, "action должен быть analyze | accept | dismiss.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun describe(s: AutomationSuggestion): String {
        val pct = (s.confidence * 100).roundToInt()
        val pkg = s.packageName?.let { " [$it]" }.orEmpty()
        val rule = "когда ${AutomationSpec.describeTrigger(s.triggerType, s.triggerConfig)} → " +
            AutomationSpec.describeAction(s.actionType, s.actionConfig)
        return "- ${s.id} | ${s.title}$pkg | уверенность $pct% (${s.days} из ${s.observedDays} дн.)\n  " +
            "предлагаемое правило: $rule"
    }

    private fun pick(suggestions: List<AutomationSuggestion>, id: String?): AutomationSuggestion? =
        if (id.isNullOrBlank()) suggestions.first() else suggestions.firstOrNull { it.id == id }

    /**
     * A deterministic 5-of-7-day history relative to [now]: an app opened every evening at 20:30 and
     * the screen switched off at 23:00 — enough for both an app and a bedtime suggestion.
     */
    private fun syntheticEvents(now: Long, zone: ZoneId): List<UsagePatternEvent> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val out = ArrayList<UsagePatternEvent>()
        for (offset in 1..5) {
            val day = today.minusDays(offset.toLong())
            val appStart = day.atTime(20, 30).atZone(zone).toInstant().toEpochMilli()
            out += UsagePatternEvent(
                packageName = "com.andharness.smoke.app",
                startTime = appStart,
                endTime = appStart + 15 * 60_000L,
                kind = UsageEventKind.APP_FOREGROUND
            )
            val screenOff = day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
            out += UsagePatternEvent(
                packageName = "",
                startTime = screenOff,
                endTime = screenOff,
                kind = UsageEventKind.SCREEN_NON_INTERACTIVE
            )
        }
        return out
    }

    private fun isDebuggable(): Boolean = engine.isDebuggable()
}
