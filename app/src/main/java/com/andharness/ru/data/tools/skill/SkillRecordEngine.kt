package com.andharness.ru.data.tools.skill

import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.skill.SkillConversion
import com.andharness.ru.domain.skill.SkillRecordingConverter
import com.andharness.ru.domain.skill.SkillStep
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Recorder tools for app skills (M2 slice 3, SPEC §24.2):
 *
 * - `skill_record_start` — begin capturing the `ui_*` actions the agent performs (an optional
 *   `appPackage` annotates the skill; otherwise the current foreground app is used);
 * - `skill_record_stop` — stop and CONVERT the recording into a [Skill] via the pure
 *   [SkillRecordingConverter], persist it through [SkillStore], and report the created id plus which
 *   actions were converted and which could not be expressed semantically (honestly).
 *
 * The sound run engine ([SkillRunEngine]) replays the created skill; this class only records.
 */
class SkillRecordEngine(
    private val store: SkillStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "skill_record_start"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val appPackage = arguments.str("appPackage")
                        val foreground = SkillActionCapture.start(appPackage)
                        val detected = if (appPackage == null) foreground else null
                        val report = buildString {
                            append(
                                "Запись навыка начата: действия ui_tap / ui_type / ui_scroll / key_event " +
                                    "записываются по порядку вместе с семантикой элементов. Выполни действия, " +
                                    "затем вызови skill_record_stop с именем навыка."
                            )
                            if (!detected.isNullOrBlank()) {
                                append(" Приложение определено по активному окну: $detected.")
                            } else if (!appPackage.isNullOrBlank()) {
                                append(" Приложение: $appPackage.")
                            } else {
                                append(
                                    " Приложение не определено (служба спецвозможностей выключена) — " +
                                        "укажи appPackage при остановке, если нужно."
                                )
                            }
                        }
                        ToolResult(true, report)
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "skill_record_stop"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val name = arguments.str("name")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажи name — понятное имя навыка, например «Заказать такси»."
                            )
                        val snapshot = SkillActionCapture.stop()
                            ?: return@withContext ToolResult(
                                false,
                                "Запись не активна: вызови skill_record_start перед действиями. " +
                                    "Записываются только вызовы ui_* при активной записи навыка."
                            )
                        if (snapshot.actions.isEmpty()) {
                            return@withContext ToolResult(
                                false,
                                "Не записано ни одного действия — навык «$name» не сохранён. " +
                                    "Действия записываются, только когда инструменты ui_* вызываются при " +
                                    "активной записи навыка."
                            )
                        }

                        val conversion = SkillRecordingConverter.convert(snapshot.actions)
                        if (!conversion.hasSteps) {
                            return@withContext ToolResult(
                                false,
                                "Ни одно действие не удалось превратить в шаг навыка — «$name» не сохранён.\n" +
                                    conversion.skipped.joinToString("\n"),
                            )
                        }

                        val appPackage = arguments.str("appPackage")
                            ?.takeIf { it.isNotBlank() }
                            ?: snapshot.requestedPackage.takeIf { it.isNotBlank() }
                            ?: snapshot.foregroundPackage.orEmpty()
                        // BUG-032: a recording has no launch step, so the replay would start on Меха
                        // when run from the library. Prepend launch_app for the known target package.
                        val steps = SkillRecordingConverter.withLaunchStep(conversion.steps, appPackage)
                        val now = System.currentTimeMillis()
                        val skill = Skill(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            description = arguments.str("description").orEmpty(),
                            appPackage = appPackage,
                            steps = steps,
                            createdAt = now,
                            updatedAt = now,
                        )
                        val stored = store.save(skill)
                            ?: return@withContext ToolResult(
                                false,
                                "Не удалось сохранить навык «$name» (пустое имя или id)."
                            )

                        ToolResult(true, render(snapshot, stored, conversion))
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
    )

    private fun render(
        snapshot: SkillActionCapture.Snapshot,
        skill: Skill,
        conversion: SkillConversion,
    ): String = buildString {
        appendLine(
            "Навык «${skill.name}» создан из записи: id=${skill.id}, " +
                "приложение: ${skill.appPackage.ifBlank { "—" }}, шагов ${skill.steps.size}."
        )
        appendLine("Записано действий: ${snapshot.actions.size}.")
        if (conversion.converted.isNotEmpty()) {
            appendLine("Преобразовано в шаги:")
            conversion.converted.forEach { appendLine("  $it") }
        }
        if (conversion.skipped.isNotEmpty()) {
            appendLine("Пропущено (не удалось выразить семантически — честно, без тапов по координатам):")
            conversion.skipped.forEach { appendLine("  $it") }
        }
        if (skill.steps.firstOrNull() is SkillStep.LaunchApp &&
            conversion.steps.firstOrNull() !is SkillStep.LaunchApp
        ) {
            appendLine(
                "Добавлен стартовый шаг launch_app «${skill.appPackage}», " +
                    "чтобы навык запускался из библиотеки «Навыки» (BUG-032)."
            )
        }
        if (snapshot.overflow) {
            appendLine("Достигнут лимит ${SkillCodec.MAX_STEPS} шагов — лишние действия не записаны.")
        }
        append("Дальше: проверь план через skill_run dry_run=true или запусти skill_run.")
    }
}
