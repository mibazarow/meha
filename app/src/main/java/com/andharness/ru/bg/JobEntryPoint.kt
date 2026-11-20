package com.andharness.ru.bg

import com.andharness.ru.data.notifications.NotificationChatRouter
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.chat.ChatRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface JobEntryPoint {
    fun chatRepository(): ChatRepository
    fun agentOrchestrator(): AgentOrchestrator
    fun notificationHelper(): NotificationHelper
    fun notificationChatRouter(): NotificationChatRouter
}
