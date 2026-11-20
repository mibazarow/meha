package com.andharness.ru.data.tools.skill

import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.skill.SkillCodec
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Import/export tools for the app-skill library (M2 slice 3, SPEC §24.2):
 *
 * - `skill_export` — read-only: a skill (by id/name) or the whole library (`all=true`) as the exact
 *   `SkillCodec` JSON, suitable for sharing/backing up SKILLS only. Chat/memory backup is explicitly
 *   out of scope and is NOT touched here.
 * - `skill_import` — takes that JSON (one skill object or an array), validates every item through
 *   [SkillCodec] and reports honestly per skill (imported / skipped / error). A duplicate (same id or
 *   name) is skipped unless `overwrite=true`.
 *
 * Only the Android-free [SkillStore] and [SkillCodec] are used, so the whole round-trip and the
 * duplicate/overwrite policy are unit-tested on the JVM (`SkillTransferToolsHandlerTest`).
 */
class SkillTransferToolsHandler(
    private val store: SkillStore,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "skill_export"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val query = arguments.str("id") ?: arguments.str("name")
                        val all = arguments.str("all")?.toBooleanStrictOrNull() ?: false
                        when {
                            query != null -> {
                                val skill = store.get(query)
                                    ?: return@withContext ToolResult(
                                        false,
                                        "Навык «$query» не найден. Посмотри список через skill_list."
                                    )
                                ToolResult(true, SkillCodec.encodeSkill(skill))
                            }

                            all -> {
                                val skills = store.list()
                                if (skills.isEmpty()) {
                                    return@withContext ToolResult(
                                        false,
                                        "Навыков нет — экспортировать нечего."
                                    )
                                }
                                ToolResult(true, SkillCodec.encodeSkills(skills))
                            }

                            else -> ToolResult(
                                false,
                                "Укажи id или name одного навыка, либо all=true, чтобы выгрузить все навыки."
                            )
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "skill_import"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val json = arguments.str("json")
                            ?: return@withContext ToolResult(
                                false,
                                "Укажи json — строку с навыком или массивом навыков (формат skill_export)."
                            )
                        val overwrite = arguments.str("overwrite")?.toBooleanStrictOrNull() ?: false
                        val parsed = SkillCodec.parseImport(json)
                        if (parsed is SkillCodec.ImportParseResult.Err) {
                            return@withContext ToolResult(false, parsed.message)
                        }
                        val items = (parsed as SkillCodec.ImportParseResult.Ok).items
                        if (items.isEmpty()) {
                            return@withContext ToolResult(
                                false,
                                "В JSON нет ни одного навыка — импортировать нечего."
                            )
                        }

                        var imported = 0
                        val report = ArrayList<String>(items.size)
                        for (item in items) {
                            val skill = item.skill
                            if (skill == null) {
                                report += "- ${item.name}: пропущен — ${item.error}"
                                continue
                            }
                            val existing = store.get(skill.id) ?: store.get(skill.name)
                            if (existing != null && !overwrite) {
                                report += "- ${skill.name}: пропущен — уже существует " +
                                    "(id=${existing.id}); передай overwrite=true, чтобы заменить"
                                continue
                            }
                            val now = System.currentTimeMillis()
                            val toStore = if (existing != null) {
                                // Overwrite by id OR name: keep the existing slot and creation time,
                                // so an imported duplicate replaces rather than adds a second entry.
                                skill.copy(
                                    id = existing.id,
                                    createdAt = existing.createdAt,
                                    updatedAt = now,
                                )
                            } else {
                                skill.copy(
                                    createdAt = if (skill.createdAt > 0L) skill.createdAt else now,
                                    updatedAt = now,
                                )
                            }
                            val stored = store.save(toStore)
                            if (stored == null) {
                                report += "- ${skill.name}: ошибка сохранения (пустой id или name)"
                                continue
                            }
                            imported++
                            val action = if (existing != null) "заменён" else "импортирован"
                            report += "- ${stored.name}: $action (id=${stored.id}, шагов ${stored.steps.size})"
                        }
                        val skipped = items.size - imported
                        val header = "Импорт навыков: импортировано $imported, пропущено $skipped."
                        ToolResult(imported > 0, header + "\n" + report.joinToString("\n"))
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
    )
}
