package com.andharness.ru.bg

import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduleScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduleWorkerEntryPoint {
    fun scheduleRepository(): ScheduleRepository
    fun agentOrchestrator(): AgentOrchestrator
    fun chatRepository(): ChatRepository
    fun scheduleScheduler(): ScheduleScheduler
    fun notificationHelper(): NotificationHelper
    fun timeProvider(): TimeProvider
}
