package com.andharness.ru.data.tools.person

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Default / max interactions returned by `interaction_search`. */
private const val DEFAULT_INTERACTION_LIMIT = 30
private const val MAX_INTERACTION_LIMIT = 200

/** `interaction_search`: metadata + gist search over the personal base, newest first. */
internal class InteractionSearchToolHandler(
    private val interactionRepository: InteractionRepository,
    private val personRepository: PersonRepository,
    private val readSupport: PersonReadSupport,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "interaction_search"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            searchInteractions(arguments)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка поиска взаимодействий: ${e.message}")
        }
    }

    private suspend fun searchInteractions(arguments: JsonObject): ToolResult {
        val query = args.string(arguments, "query")
        val personId = args.long(arguments, "person_id")
        val limit = args.int(arguments, "limit", DEFAULT_INTERACTION_LIMIT)
            .coerceIn(1, MAX_INTERACTION_LIMIT)

        if (personId != null) {
            personRepository.get(personId)
                ?: return ToolResult(false, "Персона #$personId не найдена.")
        }

        val interactions = when {
            personId != null && query == null -> interactionRepository.recentFor(personId, limit)
            personId != null -> interactionRepository.searchForPerson(personId, query!!, limit)
            query != null -> interactionRepository.search(query, limit)
            else -> interactionRepository.recentOverall(limit)
        }

        val persons = interactions.map { it.personId }.distinct()
            .mapNotNull { id -> personRepository.get(id)?.let { id to it } }
            .toMap()

        return ToolResult(true, buildString {
            append("Взаимодействия (")
            append(interactions.size)
            append(", новые сверху")
            if (query != null) {
                append(", поиск «")
                append(query)
                append('»')
            }
            if (personId != null) {
                append(", персона #")
                append(personId)
            }
            appendLine("):")
            if (interactions.isEmpty()) {
                appendLine("  нет")
            } else {
                interactions.forEach { interaction ->
                    append("  - ")
                    val person = persons[interaction.personId]
                    if (person == null) {
                        append(readSupport.interactionText(interaction, readSupport.unknownPerson(interaction.personId)))
                    } else {
                        append(readSupport.interactionText(interaction, person))
                    }
                    appendLine()
                }
            }
            append(
                "Честно: только метаданные и краткое резюме (gist ≤ 80 символов), тела сообщений не " +
                    "хранятся и не показываются."
            )
        })
    }
}
