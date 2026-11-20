package com.andharness.ru.bg

import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.data.message.MessageSender
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.person.PersonIndexer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessageSendWorkerEntryPoint {
    fun messageSendStore(): MessageSendStore
    fun messageSender(): MessageSender
    fun personIndexer(): PersonIndexer
    fun notificationHelper(): NotificationHelper
}
