package com.andharness.ru.data.chat

import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.ChatTitleGenerator
import com.andharness.ru.domain.formatting.collapseWhitespace
import kotlinx.coroutines.flow.collect
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatTitleGeneratorImpl @Inject constructor(
    private val llmClient: LlmClient,
    private val repository: ChatRepository
) : ChatTitleGenerator {

    override suspend fun renameFromFirstMessage(chatId: Long, firstMessage: String) {
        val title = generateTitle(firstMessage)
        if (title.isNotBlank()) {
            repository.renameChat(chatId, title)
        }
    }

    private suspend fun generateTitle(message: String): String {
        return try {
            val sb = StringBuilder()
            llmClient.complete(
                LlmRequest(
                    messages = listOf(
                        LlmMessage(
                            "system",
                            "You title chat conversations. Reply with ONLY a short title in Russian, 2-5 words, no quotes, no trailing punctuation."
                        ),
                        LlmMessage("user", "Title this chat from its first message:\n$message")
                    )
                )
            ).collect { event -> if (event is LlmEvent.Text) sb.append(event.delta) }
            val llm = collapseWhitespace(sb.toString()).take(60)
            llm.ifBlank { heuristicTitle(message) }
        } catch (e: Exception) {
            heuristicTitle(message)
        }
    }

    private fun heuristicTitle(message: String): String {
        val title = collapseWhitespace(message).take(40)
        return title.ifBlank { "Новый чат" }
    }
}
