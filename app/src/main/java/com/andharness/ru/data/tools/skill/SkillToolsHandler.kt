package com.andharness.ru.data.tools.skill

import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.skill.SkillParseResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * CRUD tools for the reusable app-skill library (M2 slice 1, SPEC §24.2):
 *
 * - `skill_list` — read-only overview of the saved skills;
 * - `skill_get` — read-only full JSON of one skill (by id or name);
 * - `skill_save` — create/update, validating the `steps`/`parameters` JSON honestly;
 * - `skill_delete` — remove a skill (dangerous).
 *
 * This slice only stores definitions; the replay engine (resolving selectors against the
 * accessibility tree and rendering `{parameter}` templates) is a later slice. The handler depends
 * only on the Android-free [SkillStore], so validation honesty is unit-tested on the JVM.
 */
class SkillToolsHandler(
    private val store: SkillStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "skill_list"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val skills = store.list()
                        if (skills.isEmpty()) {
                            ToolResult(
                                true,
                                "Сохранённых навыков нет. Создай навык через skill_save (name + steps)."
                            )
                        } else {
                            val lines = skills.joinToString("\n") { skill ->
                                val app = skill.appPackage.ifBlank { "—" }
                                "- ${skill.id} | ${skill.name} | приложение: $app | " +
                                    "параметров: ${skill.parameters.size} | шагов: ${skill.steps.size}"
                            }
                            ToolResult(true, "Навыки (${skills.size}):\n$lines")
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "skill_get"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val query = arguments.str("id") ?: arguments.str("name")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажи id или name навыка, который нужно показать."
                            )
                        val skill = store.get(query)
                            ?: return@withContext ToolResult(
                                false,
                                "Навык «$query» не найден. Посмотри список через skill_list."
                            )
                        val header = buildString {
                            appendLine("Навык «${skill.name}» (id=${skill.id}).")
                            if (skill.appPackage.isNotBlank()) appendLine("Приложение: ${skill.appPackage}.")
                            if (skill.description.isNotBlank()) appendLine("Описание: ${skill.description}.")
                            appendLine("Параметров: ${skill.parameters.size}, шагов: ${skill.steps.size}.")
                        }.trim()
                        ToolResult(true, "$header\n\n${SkillCodec.prettySkill(skill)}")
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "skill_save"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val name = arguments.str("name")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажи name — понятное имя навыка, например «Заказать такси»."
                            )
                        val idArg = arguments.str("id")
                        // Update by id first, then by name (case-insensitive); otherwise create.
                        val existing = idArg?.let { store.get(it) } ?: store.get(name)

                        val steps = when (val stepsResult = SkillCodec.parseStepsArg(arguments["steps"])) {
                            is SkillParseResult.Err ->
                                return@withContext ToolResult(false, stepsResult.message)
                            is SkillParseResult.Ok -> stepsResult.value
                        }

                        val parameters = when (
                            val parametersResult = SkillCodec.parseParametersArg(arguments["parameters"])
                        ) {
                            is SkillParseResult.Err ->
                                return@withContext ToolResult(false, parametersResult.message)
                            is SkillParseResult.Ok -> parametersResult.value
                        }

                        val now = System.currentTimeMillis()
                        val skill = Skill(
                            id = existing?.id ?: idArg ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            description = arguments.str("description")
                                ?: existing?.description.orEmpty(),
                            appPackage = arguments.str("appPackage")
                                ?: existing?.appPackage.orEmpty(),
                            parameters = parameters,
                            steps = steps,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        val stored = store.save(skill)
                            ?: return@withContext ToolResult(
                                false,
                                "Не удалось сохранить навык: пустое имя или id."
                            )

                        val declared = stored.parameters.map { it.name }.toSet()
                        val referenced = stored.referencedParameters()
                        val undeclared = referenced.filterNot { it in declared }
                        val action = if (existing == null) "создан" else "обновлён"
                        val report = buildString {
                            append(
                                "Навык $action: id=${stored.id}, «${stored.name}», " +
                                    "параметров ${stored.parameters.size}, шагов ${stored.steps.size}."
                            )
                            if (undeclared.isNotEmpty()) {
                                append(
                                    "\nВнимание: в шагах используются параметры " +
                                        undeclared.joinToString(", ") { "{$it}" } +
                                        ", но они не объявлены в parameters — при запуске они не будут " +
                                        "подставлены."
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
            override val name = "skill_delete"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val query = arguments.str("id") ?: arguments.str("name")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажи id или name навыка, который нужно удалить."
                            )
                        val skill = store.get(query)
                            ?: return@withContext ToolResult(
                                false,
                                "Навык «$query» не найден — удалять нечего."
                            )
                        if (store.delete(skill.id)) {
                            ToolResult(true, "Навык удалён: «${skill.name}» (id=${skill.id}).")
                        } else {
                            ToolResult(false, "Не удалось удалить навык «${skill.name}» (id=${skill.id}).")
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
    )
}
