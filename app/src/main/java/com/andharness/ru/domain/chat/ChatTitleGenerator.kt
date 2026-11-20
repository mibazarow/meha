package com.andharness.ru.domain.chat

interface ChatTitleGenerator {
    /** Renames the chat from its first user message to a short auto title. */
    suspend fun renameFromFirstMessage(chatId: Long, firstMessage: String)
}
