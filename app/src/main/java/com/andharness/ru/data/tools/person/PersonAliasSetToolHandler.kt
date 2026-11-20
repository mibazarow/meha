package com.andharness.ru.data.tools.person

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `person_alias_set`: adds an alternative name to a person, post-verified. */
internal class PersonAliasSetToolHandler(
    private val personRepository: PersonRepository,
    private val aliasRepository: PersonAliasRepository,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "person_alias_set"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            setAlias(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка установки алиаса: ${e.message}")
        }
    }

    private suspend fun setAlias(arguments: JsonObject): ToolResult {
        val personId = args.long(arguments, "person_id")
            ?: return ToolResult(false, "Укажите числовой person_id.")
        val alias = args.string(arguments, "alias")
            ?: return ToolResult(false, "Укажите alias — альтернативное имя (например «Серёга»).")

        personRepository.get(personId)
            ?: return ToolResult(false, "Персона #$personId не найдена. Ничего не изменено.")

        aliasRepository.insert(personId, alias)
            ?: return ToolResult(false, "Алиас пустой — ничего не изменено.")

        val aliases = aliasRepository.forPerson(personId)
        val verified = aliases.any { it.alias.equals(alias.trim(), ignoreCase = true) }
        if (!verified) {
            return ToolResult(
                false,
                "Пост-проверка не прошла: алиас «${alias.trim()}» не читается обратно у #$personId."
            )
        }
        return ToolResult(
            true,
            "Алиас «${alias.trim()}» добавлен персоне #$personId. Проверено повторным чтением. " +
                "Алиасов сейчас: ${aliases.size} (${aliases.joinToString(", ") { it.alias }})."
        )
    }
}
