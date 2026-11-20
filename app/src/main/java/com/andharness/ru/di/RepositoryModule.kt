package com.andharness.ru.di

import com.andharness.ru.data.agent.AgentOrchestratorImpl
import com.andharness.ru.data.agent.ContextManagerImpl
import com.andharness.ru.data.chat.ChatRepositoryImpl
import com.andharness.ru.data.chat.ChatSearchServiceImpl
import com.andharness.ru.data.chat.ChatTitleGeneratorImpl
import com.andharness.ru.data.device.DeviceInfoProviderImpl
import com.andharness.ru.data.embedding.LocalEmbeddingServer
import com.andharness.ru.data.permission.PermissionManagerImpl
import com.andharness.ru.data.llm.OpenAiLlmClient
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.ContextManager
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.ChatSearch
import com.andharness.ru.domain.chat.ChatTitleGenerator
import com.andharness.ru.domain.device.DeviceInfoProvider
import com.andharness.ru.domain.embedding.EmbeddingServer
import com.andharness.ru.domain.permission.PermissionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindChatTitleGenerator(impl: ChatTitleGeneratorImpl): ChatTitleGenerator

    @Binds
    @Singleton
    abstract fun bindChatSearch(impl: ChatSearchServiceImpl): ChatSearch

    @Binds
    @Singleton
    abstract fun bindLlmClient(impl: OpenAiLlmClient): LlmClient

    @Binds
    @Singleton
    abstract fun bindEmbeddingServer(impl: LocalEmbeddingServer): EmbeddingServer

    @Binds
    @Singleton
    abstract fun bindAgentOrchestrator(impl: AgentOrchestratorImpl): AgentOrchestrator

    @Binds
    @Singleton
    abstract fun bindContextManager(impl: ContextManagerImpl): ContextManager

    @Binds
    @Singleton
    abstract fun bindPermissionManager(impl: PermissionManagerImpl): PermissionManager

    @Binds
    @Singleton
    abstract fun bindDeviceInfoProvider(impl: DeviceInfoProviderImpl): DeviceInfoProvider
}
