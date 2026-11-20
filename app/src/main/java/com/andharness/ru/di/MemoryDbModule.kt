package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.dao.NoteFtsDao
import com.andharness.ru.data.db.dao.RagChunkDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Long-term-memory DAO providers: FTS notes, RAG chunks and the vector/person/entity graphs. */
@Module
@InstallIn(SingletonComponent::class)
object MemoryDbModule {

    @Provides
    fun provideNoteFtsDao(database: AppDatabase): NoteFtsDao = database.noteFtsDao()

    @Provides
    fun provideRagChunkDao(database: AppDatabase): RagChunkDao = database.ragChunkDao()

    @Provides
    fun provideMemoryVectorDao(database: AppDatabase): MemoryVectorDao = database.memoryVectorDao()

    @Provides
    fun provideMemoryPersonDao(database: AppDatabase): MemoryPersonDao = database.memoryPersonDao()

    @Provides
    fun provideMemoryEntityDao(database: AppDatabase): MemoryEntityDao = database.memoryEntityDao()
}
