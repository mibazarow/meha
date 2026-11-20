package com.andharness.ru.data.tools.person

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `person_link`: links an identity to a person, reporting an owner conflict instead of re-linking. */
internal class PersonLinkToolHandler(
    private val personRepository: PersonRepository,
    private val identityRepository: PersonIdentityRepository,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "person_link"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            linkIdentity(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка привязки идентичности: ${e.message}")
        }
    }

    private suspend fun linkIdentity(arguments: JsonObject): ToolResult {
        val personId = args.long(arguments, "person_id")
            ?: return ToolResult(false, "Укажите числовой person_id.")
        val typeRaw = args.string(arguments, "type")
            ?: return ToolResult(false, "Укажите type: PHONE, EMAIL, MESSENGER или OTHER.")
        val type = IdentityType.entries.firstOrNull { it.name.equals(typeRaw, ignoreCase = true) }
            ?: return ToolResult(
                false,
                "Неизвестный тип «$typeRaw». Доступно: PHONE, EMAIL, MESSENGER, OTHER. Ничего не изменено."
            )
        val value = args.string(arguments, "value")
            ?: return ToolResult(false, "Укажите value — сам хендл (номер, email, ник). Ничего не изменено.")
        val appPackage = args.string(arguments, "package") ?: IdentityNormalizer.NO_PACKAGE
        val label = args.string(arguments, "label")

        personRepository.get(personId)
            ?: return ToolResult(false, "Персона #$personId не найдена. Ничего не изменено.")

        val normalized = IdentityNormalizer.normalize(type, value, appPackage)
            ?: return ToolResult(
                false,
                "Значение «$value» не является корректным ${type.name}-хендлом (нормализация не " +
                    "удалась). Ничего не изменено."
            )

        identityRepository.insert(personId, type, value, appPackage, label)
            ?: return ToolResult(false, "Идентичность не записалась (значение отклонено репозиторием).")

        // Post-verify: read the row back and make sure it really belongs to the requested person.
        val stored = identityRepository.byValue(type, value, appPackage)
            ?: return ToolResult(
                false,
                "Пост-проверка не прошла: $type $normalized не читается обратно. Состояние неизвестно."
            )
        if (stored.personId != personId) {
            val owner = personRepository.get(stored.personId)
            val ownerName = owner?.displayName ?: "неизвестно"
            return ToolResult(
                false,
                "Конфликт: $type $normalized уже привязан к персоне #${stored.personId} ($ownerName). " +
                    "Ничего не изменено. Варианты: слить персон (person_merge) или указать другое значение."
            )
        }

        return ToolResult(
            true,
            "Связал $type $normalized с персоной #$personId (identity_id=${stored.id}). " +
                "Проверено повторным чтением."
        )
    }
}
