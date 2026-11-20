package com.andharness.ru.data.tools.person

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Default / max interactions shown by `person_get`. */
private const val DEFAULT_PERSON_INTERACTIONS = 10
private const val MAX_PERSON_INTERACTIONS = 100

/** `person_get`: profile + identities + aliases + the most recent interactions. */
internal class PersonGetToolHandler(
    private val personRepository: PersonRepository,
    private val readSupport: PersonReadSupport,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "person_get"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val personId = args.long(arguments, "person_id")
                ?: return@withContext ToolResult(false, "Укажите числовой person_id.")
            val person = personRepository.get(personId)
                ?: return@withContext ToolResult(false, "Персона #$personId не найдена.")
            val limit = args.int(arguments, "limit", DEFAULT_PERSON_INTERACTIONS)
                .coerceIn(1, MAX_PERSON_INTERACTIONS)
            ToolResult(true, readSupport.formatProfile(person, limit))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения персоны: ${e.message}")
        }
    }
}
