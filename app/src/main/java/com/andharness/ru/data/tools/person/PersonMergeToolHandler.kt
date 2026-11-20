package com.andharness.ru.data.tools.person

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `person_merge`: merges one person into another, post-verified. */
internal class PersonMergeToolHandler(
    private val personRepository: PersonRepository,
    private val identityRepository: PersonIdentityRepository,
    private val interactionRepository: InteractionRepository,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "person_merge"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            mergePersons(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка слияния персон: ${e.message}")
        }
    }

    private suspend fun mergePersons(arguments: JsonObject): ToolResult {
        val fromId = args.long(arguments, "person_id")
            ?: return ToolResult(false, "Укажите числовой person_id (кого сливаем).")
        val intoId = args.long(arguments, "into_person_id")
            ?: return ToolResult(false, "Укажите числовой into_person_id (в кого сливаем).")
        if (fromId == intoId) {
            return ToolResult(false, "person_id и into_person_id совпадают — сливать нечего.")
        }

        val from = personRepository.get(fromId)
            ?: return ToolResult(false, "Исходная персона #$fromId не найдена. Ничего не изменено.")
        personRepository.get(intoId)
            ?: return ToolResult(false, "Целевая персона #$intoId не найдена. Ничего не изменено.")

        val movedIdentities = identityRepository.forPerson(fromId).size
        val movedInteractions = interactionRepository.countFor(fromId)

        val merged = personRepository.merge(fromId, intoId)
        if (!merged) {
            return ToolResult(
                false,
                "Слияние не применилось (репозиторий вернул false). Состояние не изменилось."
            )
        }

        // Post-verify: the source must be gone and the target must still exist.
        val sourceGone = personRepository.get(fromId) == null
        val targetAfter = personRepository.get(intoId)
        if (!sourceGone || targetAfter == null) {
            return ToolResult(
                false,
                "Пост-проверка не прошла: источник #$fromId удалён = $sourceGone, цель #$intoId " +
                    "существует = ${targetAfter != null}. Проверь базу вручную."
            )
        }

        val targetIdentities = identityRepository.forPerson(intoId).size
        val targetInteractions = interactionRepository.countFor(intoId)
        return ToolResult(
            true,
            "Слил #$fromId («${from.displayName}») в #$intoId («${targetAfter.displayName}»). " +
                "Перенесено идентичностей: $movedIdentities (дубликаты отброшены), взаимодействий: " +
                "$movedInteractions. Источник удалён. Теперь у #$intoId идентичностей: " +
                "$targetIdentities, взаимодействий: $targetInteractions. Проверено повторным чтением."
        )
    }
}
