package com.andharness.ru.data.tools.person

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `person_find`: resolves «какой Серёга» and never guesses silently. */
internal class PersonFindToolHandler(
    private val readSupport: PersonReadSupport,
    private val args: PersonToolArgs,
) : ToolHandler {

    override val name = "person_find"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = args.string(arguments, "query")
                ?: return@withContext ToolResult(
                    false,
                    "Укажите query: имя, алиас, телефон, email или ник."
                )
            val rawChannel = args.string(arguments, "channel")
            val channel = if (rawChannel == null) {
                null
            } else {
                InteractionChannel.from(rawChannel) ?: return@withContext ToolResult(
                    false,
                    "Неизвестный канал «$rawChannel». Доступно: SMS, CALL, MESSENGER, EMAIL, CHAT."
                )
            }

            val result = readSupport.resolveQuery(query, channel)
            if (result.outcome == PersonResolver.ResolveOutcome.NOT_FOUND) {
                ToolResult(true, readSupport.notFoundText(query))
            } else {
                ToolResult(true, readSupport.formatResolve(query, channel, result))
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка поиска персоны: ${e.message}")
        }
    }
}
