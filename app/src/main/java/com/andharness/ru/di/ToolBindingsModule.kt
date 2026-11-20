package com.andharness.ru.di

import com.andharness.ru.data.audit.ToolAuditRepositoryImpl
import com.andharness.ru.data.crash.CrashLogRepositoryImpl
import com.andharness.ru.data.crash.RestartStateRepositoryImpl
import com.andharness.ru.data.mcp.McpToolProvider
import com.andharness.ru.data.rag.RagIndexer
import com.andharness.ru.data.tools.ToolRegistryImpl
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.crash.CrashLogRepository
import com.andharness.ru.domain.crash.RestartStateRepository
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.rag.RagRepository
import com.andharness.ru.domain.tools.ToolRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Interface-to-implementation bindings for the tool registry and the small tool-adjacent
 * repositories. The `Set<ToolHandler>` multibinding lives in [ToolHandlersModule]; the JSON-backed
 * stores live in [StoreProvidersModule]/[UndoProvidersModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolBindingsModule {

    @Binds
    @Singleton
    abstract fun bindToolRegistry(impl: ToolRegistryImpl): ToolRegistry

    /** Dynamic tools of the enabled MCP servers (see [McpToolProvider]). */
    @Binds
    @Singleton
    abstract fun bindMcpDynamicTools(impl: McpToolProvider): McpDynamicTools

    @Binds
    @Singleton
    abstract fun bindCrashLogRepository(impl: CrashLogRepositoryImpl): CrashLogRepository

    @Binds
    @Singleton
    abstract fun bindRestartStateRepository(impl: RestartStateRepositoryImpl): RestartStateRepository

    @Binds
    @Singleton
    abstract fun bindToolAuditRepository(impl: ToolAuditRepositoryImpl): ToolAuditRepository

    @Binds
    @Singleton
    abstract fun bindRagRepository(impl: RagIndexer): RagRepository
}
