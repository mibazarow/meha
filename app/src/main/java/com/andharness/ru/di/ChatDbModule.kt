package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.ChatDao
import com.andharness.ru.data.db.dao.MessageDao
import com.andharness.ru.data.db.dao.ScheduledTaskDao
import com.andharness.ru.data.db.dao.SettingsDao
import com.andharness.ru.data.db.dao.LlmProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Chat / message / settings / schedule / assist DAO providers. */
@Module
@InstallIn(SingletonComponent::class)
object ChatDbModule {

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideSettingsDao(database: AppDatabase): SettingsDao = database.settingsDao()

    @Provides
    fun provideLlmProviderDao(database: AppDatabase): LlmProviderDao = database.llmProviderDao()

    @Provides
    fun provideScheduledTaskDao(database: AppDatabase): ScheduledTaskDao = database.scheduledTaskDao()

    @Provides
    fun provideAssistDao(database: AppDatabase): AssistDao = database.assistDao()
}
